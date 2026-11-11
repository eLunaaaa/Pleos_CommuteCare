/**
 * @file PleosNaviManager.kt
 * @brief 현대자동차 Pleos NaviHelper SDK(3.0.5) 연동 및 목적지 기반 통근/여행 의도 감지 매니저
 *
 * [주요 동작 흐름]
 * 1. Pleos Connect의 NaviService(ai.pleos.playground.service.navi)와 AIDL로 바인딩합니다.
 * 2. 내비게이션 목적지(DestinationInfo) 및 경로 탐색(RouteStartInfo), 제한속도 이벤트를 실시간 수신합니다.
 * 3. 목적지 명칭 및 주소를 지능형 키워드 사전(회사/사옥/연구소 vs 집 vs 리조트/여행지)과 매칭합니다.
 * 4. 사용자가 내비게이션을 설정했을 때(Explicit Intent) 가장 높은 우선순위로 출퇴근 및 여행 모드를 자동 판정합니다.
 * 5. 내비게이션 목적지가 없을 때는 하이브리드 폴백 구조로 스마트 패턴 추론 엔진으로 위임합니다.
 */

package ai.pleos.commutecare.data.navi

import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.commute.LocationPoint
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log

/**
 * @brief 내비게이션 목적지 분석 결과 데이터 클래스
 * @param destinationName 목적지 명칭 (예: "현대자동차 남양연구소", "자택", "속초 델피노 리조트")
 * @param address 목적지 도로명/지번 주소
 * @param latitude 목적지 위도
 * @param longitude 목적지 경도
 * @param intentType 감지된 주행 의도 (출근, 퇴근, 주말 여행, 일반)
 * @param remainDistanceM 남은 거리 (단위: 미터)
 * @param remainTimeSec 남은 시간 (단위: 초)
 */
data class NaviDestinationAnalysis(
    val destinationName: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val intentType: CommuteType?,
    val remainDistanceM: Int,
    val remainTimeSec: Int,
    val isTravelDestination: Boolean = false
)

/**
 * @brief Pleos NaviHelper SDK 연동 매니저
 */
class PleosNaviManager(
    private val context: Context,
    private val onDestinationUpdated: (NaviDestinationAnalysis?) -> Unit = {},
    private val onSpeedLimitUpdated: (Int) -> Unit = {}
) {
    companion object {
        private const val TAG = "PleosNaviManager"

        // 출근 관련 목적지 키워드
        private val WORK_KEYWORDS = listOf(
            "회사", "직장", "연구소", "사옥", "테크노밸리", "오피스", "캠퍼스",
            "본사", "현대차", "현대자동차", "남양연구소", "양재사옥", "마북", "의왕", "스마트시티"
        )

        // 퇴근 관련 목적지 키워드
        private val HOME_KEYWORDS = listOf(
            "자택", "우리집", "거주지", "스위트홈"
        )

        // 여행/레저/리조트 관련 목적지 키워드
        private val TRAVEL_KEYWORDS = listOf(
            "리조트", "호텔", "콘도", "펜션", "해수욕장", "국립공원", "골프", "스키장",
            "글램핑", "캠핑장", "워터파크"
        )

        /**
         * @brief 목적지 키워드를 분류하고 알려지지 않은 목적지는 일반 주행으로 남긴다.
         * @param name 목적지 명칭
         * @param address 목적지 주소
         * @return 알려진 통근/여행 모드, 일반 목적지는 null
         * @throws 없음
         */
        internal fun classifyDestination(name: String, address: String): CommuteType? {
            val text = "$name $address"
            return when {
                TRAVEL_KEYWORDS.any { text.contains(it, ignoreCase = true) } -> CommuteType.TRAVEL
                WORK_KEYWORDS.any { text.contains(it, ignoreCase = true) } -> CommuteType.MORNING
                name.trim() == "집" || HOME_KEYWORDS.any { text.contains(it, ignoreCase = true) } -> CommuteType.EVENING
                else -> null
            }
        }

    }

    private var naviHelper: NaviHelper? = null
    private var isConnected: Boolean = false
    private var currentDestination: DestinationInfo? = null

    /**
     * @brief NaviHelper SDK를 초기화하고 내비게이션 서비스에 바인딩합니다.
     * @return Unit
     * @throws 없음
     */
    fun initialize() {
        try {
            Log.d(TAG, "[NaviHelper] SDK 초기화 시작...")
            naviHelper = NaviHelper(context).apply {
                initialize(
                    onConnected = {
                        isConnected = true
                        Log.i(TAG, "[NaviHelper] Pleos NaviService 바인딩 성공")
                        registerListener()
                        // 초기 목적지 정보 요청
                        try {
                            getDestinationInfo()
                        } catch (e: Exception) {
                            Log.w(TAG, "[NaviHelper] 초기 목적지 정보 요청 실패: ${e.message}")
                        }
                    },
                    onFailed = { error ->
                        isConnected = false
                        Log.w(TAG, "[NaviHelper] 바인딩 실패 (로컬 하이브리드 추론 모드로 전환): ${error.message}")
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "[NaviHelper] 초기화 예외 발생: ${e.message}", e)
        }
    }

    /**
     * @brief NaviHelper 이벤트 리스너를 등록합니다.
     * @return Unit
     * @throws 없음
     */
    private fun registerListener() {
        val helper = naviHelper ?: return
        try {
            helper.addListener(object : NaviHelperEventListener {
                override fun onDestinationInfo(destinationInfo: DestinationInfo) {
                    Log.i(TAG, "[NaviHelper] 목적지 정보 갱신: ${destinationInfo.name} (${destinationInfo.address})")
                    currentDestination = destinationInfo
                    val analysis = analyzeDestination(destinationInfo)
                    onDestinationUpdated(analysis)
                }

                override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                    Log.i(TAG, "[NaviHelper] 경로 탐색 시작됨")
                    try {
                        helper.getDestinationInfo()
                    } catch (e: Exception) {
                        Log.w(TAG, "[NaviHelper] getDestinationInfo failed: ${e.message}")
                    }
                }

                override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
                    if (speedLimit > 0) {
                        onSpeedLimitUpdated(speedLimit)
                    }
                }

                override fun onTripInfo(tripInfo: TripInfo) {
                    Log.d(TAG, "[NaviHelper] 트립 정보 수신: 총거리=${tripInfo.distance}m, ETA=${tripInfo.eta}s")
                }
            })
            Log.d(TAG, "[NaviHelper] 이벤트 리스너 등록 완료")
        } catch (e: Exception) {
            Log.e(TAG, "[NaviHelper] 리스너 등록 실패: ${e.message}", e)
        }
    }

    /**
     * @brief 수신된 목적지 정보를 분석하여 출근/퇴근/여행 의도를 판정합니다.
     * @param destination 목적지 정보 객체
     * @return NaviDestinationAnalysis 판정 분석 결과 (목적지 미설정 시 null)
     * @throws 없음
     */
    fun analyzeDestination(destination: DestinationInfo): NaviDestinationAnalysis? {
        val name = destination.name.trim()
        val address = destination.address.trim()
        if (name.isBlank() && address.isBlank()) {
            return null
        }
        val intentType = classifyDestination(name, address)

        return NaviDestinationAnalysis(
            destinationName = name,
            address = address,
            latitude = destination.latitude,
            longitude = destination.longitude,
            intentType = intentType,
            remainDistanceM = destination.remainDistance,
            remainTimeSec = destination.remainTime,
            isTravelDestination = intentType == CommuteType.TRAVEL
        )
    }

    /**
     * @brief 현재 설정된 내비게이션 목적지 분석 정보를 반환합니다.
     * @return 목적지가 설정되어 있으면 NaviDestinationAnalysis, 없으면 null
     * @throws 없음
     */
    fun getCurrentDestinationAnalysis(): NaviDestinationAnalysis? {
        val dest = currentDestination ?: return null
        return analyzeDestination(dest)
    }

    /**
     * @brief 심사위원 시연 및 테스트를 위해 가상의 내비게이션 목적지를 강제 설정/시뮬레이션합니다.
     * @param name 목적지 명칭
     * @param address 목적지 주소
     * @param lat 위도
     * @param lon 경도
     * @return Unit
     * @throws 없음
     */
    fun simulateDestination(name: String, address: String, lat: Double, lon: Double) {
        val fakeDest = DestinationInfo(
            latitude = lat,
            longitude = lon,
            name = name,
            address = address,
            remainDistance = 14500,
            remainTime = 1800,
            tollFare = 1200,
            poiId = "SIM_POI_01",
            poiSubId = null,
            poiProvider = "PleosNavi"
        )
        currentDestination = fakeDest
        val analysis = analyzeDestination(fakeDest)
        if (analysis != null) {
            onDestinationUpdated(analysis)
        }
    }

    /**
     * @brief 내비게이션 목적지를 해제(미설정) 상태로 전환합니다. (스마트 자율 추론 모드 복귀)
     * @return Unit
     * @throws 없음
     */
    fun clearDestination() {
        currentDestination = null
        onDestinationUpdated(null)
        Log.i(TAG, "[NaviHelper] 목적지 설정 해제됨 -> 스마트 자율 추론 모드로 전환")
    }

    /**
     * @brief NaviHelper SDK 리소스를 해제합니다.
     * @return Unit
     * @throws 없음
     */
    fun release() {
        try {
            naviHelper?.release()
            naviHelper = null
            isConnected = false
            Log.d(TAG, "[NaviHelper] SDK 리소스 해제 완료")
        } catch (e: Exception) {
            Log.e(TAG, "[NaviHelper] SDK 해제 중 예외 발생: ${e.message}")
        }
    }
}
