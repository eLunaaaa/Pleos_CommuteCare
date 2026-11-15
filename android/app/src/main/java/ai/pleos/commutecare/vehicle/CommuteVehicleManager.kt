/**
 * @file CommuteVehicleManager.kt
 * @brief Pleos Vehicle SDK 2.3.2 및 AAOS Car Service 연동 차량 하드웨어 제어 매니저.
 *
 * 주요 담당 기능 및 동작 순서:
 *  1. Pleos Vehicle SDK (공조/HVAC, 시트 열선/통풍, GN7 인터랙티브 무드램프) 초기화 및 바인딩
 *  2. AAOS CarUxRestrictionsManager를 통한 주행 중(Distraction Optimization) UI 간소화 제약 모니터링
 *  3. 차속(Vehicle Speed) 센서 구독 및 실시간 속도(km/h) 갱신
 *  4. 출근/퇴근 전 스마트 프리컨디셔닝 (목표 공조 온도 세팅 및 시트 열선 제어)
 *  5. 지능형 자동 원격 시동 및 미탑승(휴가/일정 취소) 시 배터리 방전 방지 공조 가동 꺼짐 타이머(Auto Engine Shutdown Timer)
 */
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

    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    private val cabinAllArea = SeatArea(
        SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT,
        SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT
    )
    private var released = false
    private val preconditioning = PreconditioningController(
        power = { enabled, complete ->
            val hvac = vehicle?.getHVAC()
            if (hvac == null) complete(false) else hvac.setHvacPower(cabinAllArea, enabled,
                onComplete = { _, _ -> handler.post { complete(true) } },
                onFailed = { error -> log("공조 전원 제어 실패: ${error.message}"); handler.post { complete(false) } })
        },
        temperature = { target, complete -> setTargetTemperature(target, complete) },
        seat = { level, complete ->
            val seat = vehicle?.getSeat()
            if (seat == null) complete(false) else seat.setHvacSeatTemperature(driverSeat, level,
                onComplete = { _, _ -> handler.post { complete(true) } },
                onFailed = { error -> log("열선 제어 실패: ${error.message}"); handler.post { complete(false) } })
        },
        schedule = { delay, action ->
            val task = Runnable { action() }
            handler.postDelayed(task, delay)
            val cancel: () -> Unit = { handler.removeCallbacks(task) }
            cancel
        }
    )

    /**
     * @brief Pleos Vehicle SDK 및 AAOS Car Service 연결을 시작함.
     * @return Unit
     * @throws 없음
     */
    fun connect() {
        onRestrictionChanged(true) // 제한 상태 확인 전에는 안전한 UI를 유지한다.
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
            // Pleos 에뮬레이터 등 AAOS car_service 미탑재 환경에서는 정차 UI를 잠금 해제한다.
            handler.postDelayed({
                if (uxManager == null) {
                    log("AAOS Car Service 미응답 -> 정차 기본 모드 활성화")
                    onRestrictionChanged(false)
                }
            }, 1500L)
        } catch (e: Exception) {
            log("AAOS Car Service 연결 예외: ${e.message}")
            onRestrictionChanged(false)
        }
    }

    /**
     * @brief AAOS 주행 제한을 구독하고 실패·미확인 상태도 제한으로 알린다.
     * @param carConnected 연결된 AAOS Car 서비스
     * @return Unit
     * @throws 없음
     */
    private fun setupCarRestrictions(carConnected: Car) {
        try {
            uxManager = carConnected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            uxManager?.registerListener { restrictions ->
                val isDistractionRestricted = restrictions.isRequiresDistractionOptimization
                handler.post { onRestrictionChanged(isDistractionRestricted) }
            }
            val currentRestricted = uxManager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: true
            onRestrictionChanged(currentRestricted)
        } catch (e: Exception) {
            log("주행 제한 리스너 등록 실패: ${e.message}")
            onRestrictionChanged(true)
        }
    }

    /**
     * @brief 차속 센서를 구독해 m/s 값을 km/h로 변환한다.
     * @param carConnected 연결된 AAOS Car 서비스
     * @return Unit
     * @throws 없음
     */
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
     * @brief 공조→온도→열선을 순차 설정하고 실패 시 두 장치를 종료한다.
     * @param targetTemp 목표 온도 (16~30℃)
     * @param seatHeatingLevel 열선 단계 (0~3)
     * @param onComplete 전체 적용 결과 (메인 스레드, 정확히 한 번)
     * @return Unit
     * @throws 없음
     */
    fun startPreconditioning(targetTemp: Float, seatHeatingLevel: Int, onComplete: (Boolean) -> Unit) {
        if (released) { onComplete(false); return }
        preconditioning.start(targetTemp, seatHeatingLevel) { success ->
            log("공조·온도·열선 프리컨디셔닝 결과: $success (시동 제어는 지원하지 않음)")
            onComplete(success)
        }
    }

    /**
     * @brief 공조 전원 변경 없이 운전석 목표 온도만 실제 차량에 적용한다.
     * @param targetTemp 목표 온도 (16~30℃)
     * @param onComplete SDK 적용 성공 여부
     * @return Unit
     * @throws 없음
     */
    fun setTargetTemperature(targetTemp: Float, onComplete: (Boolean) -> Unit) {
        if (released || !targetTemp.isFinite() || targetTemp !in 16f..30f) { onComplete(false); return }
        try {
            val hvac = vehicle?.getHVAC()
            if (hvac == null) { onComplete(false); return }
            hvac.setHvacTemperature(driverSeat, targetTemp, TemperatureUnit.CELSIUS,
                onComplete = { _, _ -> handler.post { onComplete(true) } },
                onFailed = { error -> log("목표 온도 적용 실패: ${error.message}"); handler.post { onComplete(false) } })
        } catch (e: Exception) {
            log("목표 온도 제어 예외: ${e.message}")
            onComplete(false)
        }
    }

    /**
     * @brief 차속으로 운전자 주행이 확인되면 미탑승 공조 종료 예약을 해제한다.
     * @return Unit
     * @throws 없음
     */
    fun cancelSafetyAutoShutdown() = preconditioning.markDriverPresent()

    /**
     * @brief 전체 캐빈(0x75) 공조와 운전석(0x1) 열선을 함께 종료한다.
     * @return Unit
     * @throws 없음
     */
    fun stopPreconditioning() = preconditioning.stop()

    /**
     * 디 올 뉴 그랜저(GN7) 인터랙티브 무드램프(앰비언트 라이트) 색상 및 밝기 제어
     * @param colorHex RGB 색상 정수값 (예: 0x00E5FF)
     * @param onComplete 함수 입력값
     * @return Unit
     * @throws 없음
     */
    fun setAmbientMoodLight(colorHex: Int, onComplete: ((Boolean) -> Unit)? = null) {
        val light = vehicle?.getLight()
        if (light == null) {
            log("Pleos Light API 준비되지 않음")
            onComplete?.invoke(false)
            return
        }

        try {
            // 그랜저 GN7 크래시패드 일체형 앰비언트 라이트는 캐빈 전체 비트마스크(0x75)에 바인딩
            val ambientCabinArea = SeatArea(
                SeatValue.ROW_1_LEFT,
                SeatValue.ROW_1_RIGHT,
                SeatValue.ROW_2_LEFT,
                SeatValue.ROW_2_CENTER,
                SeatValue.ROW_2_RIGHT
            )
            light.setAmbientLightSwitch(ambientCabinArea, LightSwitch.ON,
                onComplete = { _, _ ->
                    light.setAmbientLightBrightnessColor(ambientCabinArea, colorHex,
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

    /**
     * @brief 차량 SDK 로그를 기록하고 UI 콜백을 메인 스레드에 전달한다.
     * @param message 전달할 로그 문구
     * @return Unit
     * @throws 없음
     */
    private fun log(message: String) {
        Log.i("PleosCommuteCare", message)
        handler.post { onLog(message) }
    }

    /**
     * @brief 종료 요청 후 최대 5초간 SDK 응답 기회를 주고 연결과 타이머를 해제한다.
     * @return Unit
     * @throws 없음
     */
    fun release() {
        if (released) return
        if (preconditioning.isActive) preconditioning.stop()
        released = true
        handler.postDelayed({
            preconditioning.disposeTimers()
            runCatching { speedCallback?.let { carPropertyManager?.unregisterCallback(it) } }
            runCatching { uxManager?.unregisterListener() }
            runCatching { car?.disconnect() }
            runCatching { vehicle?.release() }
            vehicle = null
        }, 5_000L)
    }
}
