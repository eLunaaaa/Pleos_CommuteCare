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
 * Pleos Vehicle SDK 2.3.2 + AAOS Car Service 연동 매니저
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
