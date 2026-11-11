/**
 * @file CommutePatternEngineTest.kt
 * @brief 출퇴근 패턴 학습 엔진 단위 테스트 (RNN-Style 재귀적 온도 수렴 및 자동 인사 승격 검증)
 */

package ai.pleos.commutecare.domain.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class CommutePatternEngineTest {

    private lateinit var engine: CommutePatternEngine

    @Before
    fun setUp() {
        engine = CommutePatternEngine()
    }

    /**
     * @brief 초기 상태에서는 학습 표본 수가 부족하므로 자동 인사가 비활성화되어야 함
     */
    @Test
    fun testInitialState_NoAutoGreeting() {
        val pattern = engine.predictPattern(Calendar.MONDAY, 815, true)
        assertFalse("초기에는 자동 인사가 잠겨있어야 함", pattern.isAutoGreetingUnlocked)
        assertEquals(22.0f, pattern.personalizedTargetTemp, 0.1f)
    }

    /**
     * @brief 3회 이상 동일 요일/시간대 출근 기록 시 자동 인사("화이팅/수고하셨습니다") 잠금 해제 검증
     */
    @Test
    fun testAutoGreetingUnlockAfterThreeTrips() {
        engine.recordTripEvent(Calendar.MONDAY, 815, 21.5f, true)
        engine.recordTripEvent(Calendar.MONDAY, 820, 21.0f, true)
        val beforeUnlock = engine.predictPattern(Calendar.MONDAY, 815, true)
        assertFalse("2회 기록 시에는 아직 해제되지 않아야 함", beforeUnlock.isAutoGreetingUnlocked)

        engine.recordTripEvent(Calendar.MONDAY, 818, 21.2f, true)
        val afterUnlock = engine.predictPattern(Calendar.MONDAY, 815, true)
        assertTrue("3회 이상 기록 시 자동 인사가 해제되어야 함", afterUnlock.isAutoGreetingUnlocked)
    }

    /**
     * @brief RNN 지수이동평균(EMA) 공식에 의해 수동 설정 온도가 점진적으로 수렴하는지 검증
     * H_t = (1 - alpha) * H_{t-1} + alpha * X_t
     */
    @Test
    fun testTemperatureConvergence() {
        // 기본 22.0℃ 상태에서 사용자가 20.0℃로 3회 연속 낮춤
        engine.recordTripEvent(Calendar.MONDAY, 815, 20.0f, true)
        engine.recordTripEvent(Calendar.TUESDAY, 815, 20.0f, true)
        engine.recordTripEvent(Calendar.WEDNESDAY, 815, 20.0f, true)

        val pattern = engine.predictPattern(Calendar.THURSDAY, 815, true)
        // 22.0에서 20.0 방향으로 수렴하여 21.0 미만으로 내려왔는지 확인
        assertTrue("목표 온도가 사용자의 선호(20℃) 방향으로 수렴해야 함", pattern.personalizedTargetTemp < 21.0f)
    }
}
