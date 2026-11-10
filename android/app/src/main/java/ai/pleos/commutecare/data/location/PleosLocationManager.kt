/**
 * @file PleosLocationManager.kt
 * @brief Pleos FusedLocation SDK 1.1.3 기반 차량 실시간 GPS 수신 및 주기적 기상청 날씨 동기화 매니저
 *
 * [주요 동작 흐름]
 * 1. 현대자동차 Pleos Connect의 공식 FusedLocation SDK(ai.pleos.playground.location.fused.FusedLocation) 초기화
 * 2. 차량 고정밀 복합 측위 콜백 등록(GNSS + IMU 센서 융합)으로 실시간 위경도/헤딩각 수신
 * 3. 지정된 주기(기본 10분) 또는 차량 이동 거리(2.0km 이상 이동) 감지 시 기상청 실시간 기상 관측 동기화 트리거
 * 4. 네트워크 단절 또는 에뮬레이터 위치 미수신 시 양재 본사 모의 좌표로 안전하게 로컬 폴백
 */

package ai.pleos.commutecare.data.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PleosLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "PleosLocationManager"

        // 기본 날씨 갱신 주기: 10분 (600,000ms)
        const val DEFAULT_WEATHER_REFRESH_INTERVAL_MS = 10 * 60 * 1000L

        // 날씨 갱신을 트리거할 최소 이동 거리: 2.0 km
        private const val MIN_DISTANCE_FOR_WEATHER_REFRESH_KM = 2.0

        // 폴백 기본 위치: 현대자동차 양재 본사
        private const val DEFAULT_LAT = 37.4640
        private const val DEFAULT_LON = 127.0434
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var fusedLocation: FusedLocation? = null
    private var isConnected: Boolean = false

    // 현재 실시간 위치 좌표
    var currentLatitude: Double = DEFAULT_LAT
        private set
    var currentLongitude: Double = DEFAULT_LON
        private set
    var currentHeading: Float = 0.0f
        private set

    // 마지막으로 날씨 API를 조회한 위치 및 시각
    private var lastWeatherFetchLat: Double = 0.0
    private var lastWeatherFetchLon: Double = 0.0
    private var lastWeatherFetchTimeMs: Long = 0L

    // 콜백 리스너
    var onLocationChangedListener: ((lat: Double, lon: Double, heading: Float) -> Unit)? = null
    var onWeatherRefreshNeededListener: ((lat: Double, lon: Double, reason: String) -> Unit)? = null

    // 주기적 타이머 러너블 (10분 간격 자동 검사)
    private val periodicWeatherRunnable = object : Runnable {
        override fun run() {
            checkAndTriggerWeatherRefresh("주기적 10분 타이머 만료")
            mainHandler.postDelayed(this, DEFAULT_WEATHER_REFRESH_INTERVAL_MS)
        }
    }

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(locationData: LocationData?) {
            if (locationData == null) return
            val lat = locationData.latitude
            val lon = locationData.longitude
            val heading = locationData.heading

            // 유효하지 않은 0,0 좌표 필터링
            if (lat == 0.0 && lon == 0.0) return

            currentLatitude = lat
            currentLongitude = lon
            currentHeading = heading

            Log.d(TAG, "[FusedLocation] 실시간 위치 수신: Lat=%.5f, Lon=%.5f, Heading=%.1f°".format(lat, lon, heading))

            mainHandler.post {
                onLocationChangedListener?.invoke(lat, lon, heading)
            }

            // 이동 거리 기반 날씨 갱신 검사
            val distanceKm = calculateDistanceKm(lastWeatherFetchLat, lastWeatherFetchLon, lat, lon)
            if (distanceKm >= MIN_DISTANCE_FOR_WEATHER_REFRESH_KM) {
                checkAndTriggerWeatherRefresh("차량 %.2fkm 이동 감지".format(distanceKm))
            }
        }

        override fun onError(e: Exception) {
            Log.w(TAG, "[FusedLocation] 위치 수신 예외 발생: ${e.message}")
        }
    }

    /**
     * @brief Pleos FusedLocation SDK 연결 및 주기적 날씨 갱신 타이머를 가동합니다.
     */
    fun startLocationTracking() {
        Log.i(TAG, "Starting Pleos FusedLocation SDK tracking...")
        try {
            fusedLocation = FusedLocation(context).apply {
                initialize()
                registerFusedLocationCallback(locationListener)
                getLastKnownLocation(locationListener)
            }
            isConnected = true
            Log.i(TAG, "Pleos FusedLocation SDK successfully registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Pleos FusedLocation, using default coordinates", e)
            isConnected = false
        }

        // 주기적 날씨 갱신 타이머 시작
        mainHandler.removeCallbacks(periodicWeatherRunnable)
        mainHandler.postDelayed(periodicWeatherRunnable, DEFAULT_WEATHER_REFRESH_INTERVAL_MS)
    }

    /**
     * @brief 기상청 날씨 동기화 필요 여부를 검사하고 콜백을 호출합니다.
     * @param reason 갱신 트리거 사유 (거리 이동 또는 주기적 타이머)
     */
    fun checkAndTriggerWeatherRefresh(reason: String) {
        lastWeatherFetchLat = currentLatitude
        lastWeatherFetchLon = currentLongitude
        lastWeatherFetchTimeMs = System.currentTimeMillis()

        Log.i(TAG, "Triggering Weather Refresh: reason='$reason' at (%.4f, %.4f)".format(currentLatitude, currentLongitude))
        mainHandler.post {
            onWeatherRefreshNeededListener?.invoke(currentLatitude, currentLongitude, reason)
        }
    }

    /**
     * @brief 두 위경도 좌표 간의 구면 대원 거리(Haversine 공식)를 계산합니다.
     * @param lat1 지점1 위도
     * @param lon1 지점1 경도
     * @param lat2 지점2 위도
     * @param lon2 지점2 경도
     * @return 두 지점 사이의 거리 (km)
     */
    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 && lon1 == 0.0) return 999.0 // 최초 조회 시 강제 갱신 유도
        val r = 6371.0 // 지구 반경 (km)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * @brief 위치 추적 리스너 등록을 해제하고 타이머 자원을 정리합니다.
     */
    fun stopLocationTracking() {
        Log.i(TAG, "Stopping Pleos FusedLocation SDK tracking...")
        mainHandler.removeCallbacks(periodicWeatherRunnable)
        try {
            fusedLocation?.unregisterFusedLocationCallback(locationListener)
            fusedLocation?.release()
            fusedLocation = null
            isConnected = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping FusedLocation", e)
        }
    }
}
