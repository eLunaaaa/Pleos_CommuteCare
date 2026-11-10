package ai.pleos.commutecare.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.LightSwitch
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit

/**
 * @file CommuteVehicleManager.kt
 * @brief Pleos Vehicle SDK 2.3.2 및 AAOS Car Service 연동 차량 하드웨어 제어 매니저.
 *
 * 주요 담당 기능 및 동작 순서:
 *  1. Pleos Vehicle SDK (공조/HVAC, 시트 열선/통풍, GN7 인터랙티브 무드램프) 초기화 및 바인딩
 *  2. AAOS CarUxRestrictionsManager를 통한 주행 중(Distraction Optimization) UI 간소화 제약 모니터링
 *  3. 차속(Vehicle Speed) 센서 구독 및 실시간 속도(km/h) 갱신
 *  4. 출근/퇴근 전 스마트 프리컨디셔닝 (목표 공조 온도 세팅 및 시트 열선 제어)
 *  5. 지능형 자동 원격 시동 및 미탑승(휴가/일정 취소) 시 배터리 방전 방지 자동 시동 꺼짐 타이머(Auto Engine Shutdown Timer)
 */
@android.annotation.TargetApi(29)
class CommuteVehicleManager(
    context: Context,
    private val onLog: (String) -> Unit,
    private val onSpeedChanged: (Float) -> Unit,
    private val onRestrictionChanged: (Boolean) -> Unit
) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    private var car: Car? = null
    private var vehicle: Vehicle? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var carPropertyManager: CarPropertyManager? = null

    private var speedCallback: CarPropertyManager.CarPropertyEventCallback? = null

    // 자동 시동 후 미탑승 방전 방지 타이머 Runnable (기본 10분)
    private var autoShutdownRunnable: Runnable? = null
    private var isEnginePreconditionedOn: Boolean = false

    /**
     * @brief Pleos Vehicle SDK 및 AAOS Car Service 연결을 시작함.
     * @return Unit
     */
    fun connect() {
        // 1. Pleos Vehicle SDK 초기화
        try {
            vehicle = Vehicle(context).also { it.initialize() }
            log("Pleos Vehicle SDK 2.3.2 초기화 완료")
        } catch (e: Exception) {
            log("Pleos Vehicle SDK 초기화 실패: ${e.message}")
        }

        // 2. AAOS Car Service 연동 (속도 및 주행 안전 제한)
        try {
            car = Car.createCar(context, handler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { carConnected, ready ->
                if (!ready) {
                    log("AAOS Car Service 연결 해제")
                    onRestrictionChanged(true)
                } else {
                    log("AAOS Car Service 연결 성공")
                    setupCarRestrictions(carConnected)
                    setupSpeedSubscription(carConnected)
                }
            }
        } catch (e: Exception) {
            log("AAOS Car Service 연결 예외: ${e.message}")
        }
    }

    private fun setupCarRestrictions(carConnected: Car) {
        try {
            uxManager = carConnected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.registerListener { restrictions ->
                val isDistractionRestricted = restrictions.isRequiresDistractionOptimization
                handler.post { onRestrictionChanged(isDistractionRestricted) }
            }
            val currentRestricted = uxManager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false
            onRestrictionChanged(currentRestricted)
        } catch (e: Exception) {
            log("주행 제한 리스너 등록 실패: ${e.message}")
        }
    }

    private fun setupSpeedSubscription(carConnected: Car) {
        try {
            carPropertyManager = carConnected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                        val speedMps = (value.value as? Float) ?: 0f
                        val speedKmh = speedMps * 3.6f
                        handler.post { onSpeedChanged(speedKmh) }
                    }
                }

                override fun onErrorEvent(propId: Int, areaId: Int) {
                    log("차속 센서 수신 에러")
                }
            }

            carPropertyManager?.registerCallback(
                speedCallback!!,
                VehiclePropertyIds.PERF_VEHICLE_SPEED,
                CarPropertyManager.SENSOR_RATE_NORMAL
            )
        } catch (e: Exception) {
            log("차속 구독 등록 실패: ${e.message}")
        }
    }

    /**
     * 출퇴근 전 스마트 프리컨디셔닝 (공조 온도 및 시트 열선/통풍)
     */
    fun startPreconditioning(targetTemp: Float, seatHeatingLevel: Int, onComplete: (Boolean) -> Unit) {
        log("스마트 프리컨디셔닝 시작: 목표 온도 ${targetTemp}℃, 열선 ${seatHeatingLevel}단")
        val hvac = vehicle?.getHVAC()
        val seat = vehicle?.getSeat()

        if (hvac == null || seat == null) {
            log("Pleos Vehicle SDK가 준비되지 않았습니다.")
            onComplete(false)
            return
        }

        try {
            val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)

            // 1. HVAC 전원 및 목표 온도 세팅
            hvac.setHvacPower(driverSeat, true,
                onComplete = { _, _ ->
                    log("HVAC 전원 ON 완료")
                    hvac.setHvacTemperature(driverSeat, targetTemp, TemperatureUnit.CELSIUS,
                        onComplete = { _, _ -> log("HVAC 목표 온도 ${targetTemp}℃ 설정 완료") },
                        onFailed = { e -> log("HVAC 목표 온도 설정 실패: ${e.message}") }
                    )
                },
                onFailed = { e -> log("HVAC 전원 켜기 실패: ${e.message}") }
            )

            // 2. 시트 열선 설정
            seat.setHvacSeatTemperature(driverSeat, seatHeatingLevel,
                onComplete = { _, _ ->
                    log("운전석 열선 ${seatHeatingLevel}단 설정 완료")
                    isEnginePreconditionedOn = true
                    scheduleSafetyAutoShutdown(10 /* 10분 제한 */)
                    onComplete(true)
                },
                onFailed = { e ->
                    log("시트 열선 설정 실패: ${e.message}")
                    onComplete(false)
                }
            )

        } catch (e: Exception) {
            log("프리컨디셔닝 실행 중 예외: ${e.message}")
            onComplete(false)
        }
    }

    /**
     * @brief 자동 시동 및 프리컨디셔닝 후 운전자 미탑승(휴가, 일정 취소) 시 배터리 방전 방지를 위한 타이머.
     * @param timeoutMinutes 시동 유지 시간 (분 단위, 예: 10분)
     * @return Unit
     */
    fun scheduleSafetyAutoShutdown(timeoutMinutes: Int = 10) {
        autoShutdownRunnable?.let { handler.removeCallbacks(it) }
        log("🛡️ 배터리 방전 방지 안전 타이머 가동: ${timeoutMinutes}분 내 미탑승 시 자동 시동 OFF 예약")

        autoShutdownRunnable = Runnable {
            if (isEnginePreconditionedOn) {
                log("⚠️ [안전 알림] ${timeoutMinutes}분간 운전자 탑승이 감지되지 않아 배터리 보호를 위해 공조 및 시동을 자동 종료합니다.")
                stopPreconditioning()
            }
        }
        handler.postDelayed(autoShutdownRunnable!!, timeoutMinutes * 60 * 1000L)
    }

    /**
     * @brief 운전자 탑승 감지 시 안전 종료 타이머를 해제함.
     * @return Unit
     */
    fun cancelSafetyAutoShutdown() {
        autoShutdownRunnable?.let {
            handler.removeCallbacks(it)
            autoShutdownRunnable = null
            log("운전자 탑승 감지: 자동 시동 OFF 타이머 해제")
        }
    }

    /**
     * @brief 공조 및 시동 프리컨디셔닝을 안전하게 강제 종료함.
     * @return Unit
     */
    fun stopPreconditioning() {
        val hvac = vehicle?.getHVAC()
        try {
            val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
            hvac?.setHvacPower(driverSeat, false,
                onComplete = { _, _ -> log("공조 전원 OFF 완료") },
                onFailed = { e -> log("공조 OFF 실패: ${e.message}") }
            )
            isEnginePreconditionedOn = false
        } catch (e: Exception) {
            log("프리컨디셔닝 종료 예외: ${e.message}")
        }
    }

    /**
     * 디 올 뉴 그랜저(GN7) 인터랙티브 무드램프(앰비언트 라이트) 색상 및 밝기 제어
     * @param colorHex RGB 색상 정수값 (예: 0x00E5FF)
     */
    fun setAmbientMoodLight(colorHex: Int, onComplete: ((Boolean) -> Unit)? = null) {
        val light = vehicle?.getLight()
        if (light == null) {
            log("Pleos Light API 준비되지 않음")
            onComplete?.invoke(false)
            return
        }

        try {
            val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
            light.setAmbientLightSwitch(driverSeat, LightSwitch.ON,
                onComplete = { _, _ ->
                    light.setAmbientLightBrightnessColor(driverSeat, colorHex,
                        onComplete = { _, _ ->
                            log("GN7 앰비언트 무드램프 색상 적용 완료: 0x%06X".format(colorHex and 0xFFFFFF))
                            onComplete?.invoke(true)
                        },
                        onFailed = { e ->
                            log("앰비언트 밝기/색상 설정 실패: ${e.message}")
                            onComplete?.invoke(false)
                        }
                    )
                },
                onFailed = { e ->
                    log("앰비언트 라이트 스위치 ON 실패: ${e.message}")
                    onComplete?.invoke(false)
                }
            )
        } catch (e: Exception) {
            log("앰비언트 제어 예외: ${e.message}")
            onComplete?.invoke(false)
        }
    }

    private fun log(message: String) {
        Log.i("PleosCommuteCare", message)
        handler.post { onLog(message) }
    }

    fun release() {
        try {
            speedCallback?.let { carPropertyManager?.unregisterCallback(it) }
            uxManager?.unregisterListener()
            car?.disconnect()
            vehicle?.release()
        } catch (e: Exception) {
            Log.e("PleosCommuteCare", "리소스 해제 예외: ${e.message}")
        }
    }
}
