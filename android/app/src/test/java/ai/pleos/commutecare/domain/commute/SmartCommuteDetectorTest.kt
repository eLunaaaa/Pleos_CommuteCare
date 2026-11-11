/**
 * @file SmartCommuteDetectorTest.kt
 * @brief 하이브리드 출퇴근 감지 엔진 단위 테스트 (NaviHelper 명시적 목적지 vs 무설정 시계열/GPS 자율 추론)
 */

package ai.pleos.commutecare.domain.commute

import ai.pleos.commutecare.data.navi.NaviDestinationAnalysis
import ai.pleos.commutecare.domain.CommuteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SmartCommuteDetectorTest {

    private lateinit var detector: SmartCommuteDetector

    @Before
    fun setUp() {
        detector = SmartCommuteDetector(
            homeLocation = LocationPoint("자택 (강남권)", 37.5172, 127.0473),
            officeLocation = LocationPoint("현대자동차 양재 본사", 37.4640, 127.0434)
        )
    }

    /**
     * @brief [전략 1] Pleos NaviHelper 명시적 목적지 연동 테스트 (출근)
     */
    @Test
    fun testExplicitNaviDestination_Work() {
        val naviAnalysis = NaviDestinationAnalysis(
            destinationName = "현대자동차 남양연구소",
            address = "경기도 화성시 남양읍 현대연구소로 150",
            latitude = 37.1950,
            longitude = 126.8300,
            intentType = CommuteType.MORNING,
            remainDistanceM = 45000,
            remainTimeSec = 3200,
            isTravelDestination = false
        )

        val prompt = detector.evaluateCommuteIntent(
            naviAnalysis = naviAnalysis,
            currentLat = 37.5172,
            currentLon = 127.0473,
            hourOfDay = 8
        )

        assertNotNull("내비 목적지가 회사일 때 출근 프롬프트가 생성되어야 함", prompt)
        assertEquals(CommuteType.MORNING, prompt!!.commuteType)
        assertEquals(CommuteDetectionSource.NAVI_EXPLICIT, prompt.source)
        assertEquals(1.0f, prompt.confidence, 0.001f)
    }

    /**
     * @brief [전략 1] Pleos NaviHelper 명시적 목적지 연동 테스트 (주말 여행)
     */
    @Test
    fun testExplicitNaviDestination_Travel() {
        val naviAnalysis = NaviDestinationAnalysis(
            destinationName = "속초 델피노 리조트",
            address = "강원도 고성군 토성면 미시령옛길 1153",
            latitude = 38.2107,
            longitude = 128.5135,
            intentType = CommuteType.TRAVEL,
            remainDistanceM = 180000,
            remainTimeSec = 7500,
            isTravelDestination = true
        )

        val prompt = detector.evaluateCommuteIntent(
            naviAnalysis = naviAnalysis,
            currentLat = 37.5172,
            currentLon = 127.0473,
            hourOfDay = 10
        )

        assertNotNull("내비 목적지가 리조트일 때 여행 프롬프트가 생성되어야 함", prompt)
        assertEquals(CommuteType.TRAVEL, prompt!!.commuteType)
        assertEquals(CommuteDetectionSource.NAVI_EXPLICIT, prompt.source)
    }

    /**
     * @brief [전략 2] 내비를 찍지 않은 경우 (자율 추론) - 오전 출근 시간대 + 자택 출발 이동
     */
    @Test
    fun testImplicitInference_MorningCommute() {
        // 내비 미설정 (naviAnalysis = null), 오전 8시, 자택 반경 내(강남권)
        val prompt = detector.evaluateCommuteIntent(
            naviAnalysis = null,
            currentLat = 37.5170, // 자택 근처
            currentLon = 127.0470,
            hourOfDay = 8
        )

        assertNotNull("내비 미설정 시에도 시간대+위치로 출근 감지되어야 함", prompt)
        assertEquals(CommuteType.MORNING, prompt!!.commuteType)
        assertEquals(CommuteDetectionSource.IMPLICIT_INFERENCE, prompt.source)
    }

    /**
     * @brief [전략 2] 내비를 찍지 않은 경우 (자율 추론) - 저녁 퇴근 시간대 + 회사 출발 이동
     */
    @Test
    fun testImplicitInference_EveningCommute() {
        // 내비 미설정, 저녁 18:30 (18시), 회사 반경 내(양재동)
        val prompt = detector.evaluateCommuteIntent(
            naviAnalysis = null,
            currentLat = 37.4641, // 양재 본사 근처
            currentLon = 127.0435,
            hourOfDay = 18
        )

        assertNotNull("내비 미설정 시에도 퇴근 시간대 귀가 감지되어야 함", prompt)
        assertEquals(CommuteType.EVENING, prompt!!.commuteType)
        assertEquals(CommuteDetectionSource.IMPLICIT_INFERENCE, prompt.source)
    }

    /**
     * @brief 무관한 낮 시간대(오후 2시) 및 중간 지점일 때는 오탐하지 않고 null 반환
     */
    @Test
    fun testNoCommute_Midday() {
        val prompt = detector.evaluateCommuteIntent(
            naviAnalysis = null,
            currentLat = 37.5000,
            currentLon = 127.0300,
            hourOfDay = 14
        )

        assertNull("출퇴근 시간이 아닌 낮 2시에는 감지되지 않아야 함", prompt)
    }
}
