package ai.pleos.commutecare.domain.commute

import ai.pleos.commutecare.domain.CommuteType
import java.util.Calendar
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationPoint(
    val name: String,
    val latitude: Double,
    val longitude: Double
)

data class CommutePrompt(
    val commuteType: CommuteType,
    val message: String,
    val destination: LocationPoint
)

/**
 * 시간대 + GPS 위치/이동 방향 기반 스마트 출퇴근 감지기
 * - 복잡한 설정 없이, 일정한 시간대(오전 7~10시 또는 오후 5~8시)에 집/회사 방면으로 이동 시작 시
 *   "출근 중이십니까?" / "퇴근 중이십니까?"를 팝업 및 음성으로 묻고 루틴을 제안합니다.
 */
class SmartCommuteDetector(
    var homeLocation: LocationPoint = LocationPoint("자택", 37.5172, 127.0473), // 서울 강남권
    var officeLocation: LocationPoint = LocationPoint("현대자동차 양재 본사", 37.4640, 127.0434) // 양재동
) {

    /**
     * 현재 시각 및 차량 위치를 기반으로 출퇴근 감지 및 질문 여부 판단
     */
    fun evaluateCommuteIntent(
        currentLat: Double,
        currentLon: Double,
        hourOfDay: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): CommutePrompt? {
        val distToHomeKm = calculateDistanceKm(currentLat, currentLon, homeLocation.latitude, homeLocation.longitude)
        val distToOfficeKm = calculateDistanceKm(currentLat, currentLon, officeLocation.latitude, officeLocation.longitude)

        // 1. 오전 시간대 (07:00 ~ 10:30): 자택 반경 근처이거나 회사로 향하는 중인 경우
        if (hourOfDay in 7..10) {
            if (distToHomeKm < 3.0 || distToOfficeKm > 2.0) {
                return CommutePrompt(
                    commuteType = CommuteType.MORNING,
                    message = "평소 출근 시간대입니다. 현대차 본사로 출근 중이십니까?",
                    destination = officeLocation
                )
            }
        }

        // 2. 오후/저녁 시간대 (17:30 ~ 21:00): 회사 반경 근처이거나 집으로 향하는 중인 경우
        if (hourOfDay in 17..21) {
            if (distToOfficeKm < 3.0 || distToHomeKm > 2.0) {
                return CommutePrompt(
                    commuteType = CommuteType.EVENING,
                    message = "퇴근 시간대입니다. 자택으로 귀가 중이십니까?",
                    destination = homeLocation
                )
            }
        }

        return null
    }

    /**
     * Haversine 공식을 사용한 위경도 간 거리(km) 계산
     */
    private fun calculateDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // 지구 반지름 (km)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
