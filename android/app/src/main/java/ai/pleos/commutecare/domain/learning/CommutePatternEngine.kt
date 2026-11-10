package ai.pleos.commutecare.domain.learning

import java.util.Calendar
import kotlin.math.roundToInt

/**
 * @file CommutePatternEngine.kt
 * @brief 운전자의 출퇴근 요일·시간대 및 수동 온도 설정 이력을 학습하는 적응형 시계열 예측 엔진.
 *
 * 동작 순서는 다음과 같음:
 *  1. 탑승 시 요일, 출발 시각, 수동 설정 온도를 시계열 히스토리(`TripLog`)에 기록
 *  2. 일정 횟수(임계치 3회 이상) 반복되면 질문을 생략하고 "오늘도 수고하셨습니다/화이팅입니다" 능동 인사로 자동 승격
 *  3. 운전자가 수동 조절한 온도 데이터를 RNN 지수이동평균(EMA / Recurrent Weighted State)으로 가중 반영하여 최적 선호 온도 자동 수렴
 *  4. 출근 시간 신뢰도가 충분히 쌓이면 원격 시동 자동 예약 신호를 발행
 */

data class TripLog(
    val dayOfWeek: Int,        // Calendar.MONDAY ~ SUNDAY
    val hourMinute: Int,       // HHmm 형식 (예: 0815 -> 8시 15분)
    val manualSetTempCelsius: Float,
    val isMorningCommute: Boolean
)

data class LearnedCommutePattern(
    val isAutoGreetingUnlocked: Boolean,     // 질문 없이 자동 인사("화이팅/수고하셨습니다") 모드 활성화 여부
    val predictedDepartureHourMinute: Int,   // 예측 출발 시각 (HHmm)
    val personalizedTargetTemp: Float,       // 개인화 수렴 목표 온도 (℃)
    val autoPreconditioningEligible: Boolean // 자동 원격 시동 대기 적격 여부
)

class CommutePatternEngine {

    // 운전자 운행 및 온도 조절 시계열 기록 리스트
    private val tripHistory = mutableListOf<TripLog>()

    // 기본 기준 온도 (22.0℃)
    private var recurrentStateTemp: Float = 22.0f

    // EMA 학습률 (새로운 수동 조절값의 반영 가중치)
    private val alpha: Float = 0.35f

    /**
     * @brief 운전자의 실제 탑승 및 수동 공조 조절 이벤트를 시계열에 기록하고 선호 온도를 업데이트함.
     * @param dayOfWeek 요일 (Calendar.MONDAY..SUNDAY)
     * @param hourMinute 탑승 시각 (HHmm, 예: 820 -> 08:20)
     * @param userAdjustedTemp 사용자가 직접 조작한 최종 공조 온도 (℃)
     * @param isMorning 출근 모드 여부 (true: 출근, false: 퇴근)
     * @return Unit
     */
    fun recordTripEvent(dayOfWeek: Int, hourMinute: Int, userAdjustedTemp: Float, isMorning: Boolean) {
        tripHistory.add(
            TripLog(
                dayOfWeek = dayOfWeek,
                hourMinute = hourMinute,
                manualSetTempCelsius = userAdjustedTemp,
                isMorningCommute = isMorning
            )
        )

        // RNN의 은닉 상태(Hidden State) 갱신과 동일한 재귀적 지수이동평균(Exponential Moving Average) 수식:
        // H_t = (1 - alpha) * H_{t-1} + alpha * X_t
        recurrentStateTemp = (1.0f - alpha) * recurrentStateTemp + alpha * userAdjustedTemp
    }

    /**
     * @brief 현재 요일 및 시간대와 누적된 시계열 데이터를 분석하여 개인화된 패턴을 도출함.
     * @param currentDayOfWeek 현재 요일
     * @param currentHourMinute 현재 시각 (HHmm)
     * @param isMorning 출퇴근 구분
     * @return LearnedCommutePattern 학습된 자동 인사 여부, 예측 시각, 맞춤 목표 온도, 원격 시동 자격
     */
    fun predictPattern(currentDayOfWeek: Int, currentHourMinute: Int, isMorning: Boolean): LearnedCommutePattern {
        val matchingTrips = tripHistory.filter { it.dayOfWeek == currentDayOfWeek && it.isMorningCommute == isMorning }

        // 동일 요일/동일 시간대 반복 운행 횟수 (3회 이상이면 신뢰도 확보)
        val sampleCount = matchingTrips.size
        val isUnlocked = sampleCount >= 3

        val predictedTime = if (matchingTrips.isNotEmpty()) {
            (matchingTrips.map { it.hourMinute }.average()).roundToInt()
        } else {
            if (isMorning) 800 else 1830
        }

        return LearnedCommutePattern(
            isAutoGreetingUnlocked = isUnlocked,
            predictedDepartureHourMinute = predictedTime,
            personalizedTargetTemp = (recurrentStateTemp * 10).roundToInt() / 10.0f,
            autoPreconditioningEligible = sampleCount >= 5 // 5회 이상 완벽 반복 시 자동 시동 대기 가능
        )
    }

    /**
     * @brief 시연 및 초기 기동을 위해 가상 이력(Mock History)을 사전 로딩함.
     * @return Unit
     */
    fun seedDemonstrationHistory() {
        // 월, 화, 수 08:15분 출근, 운전자가 약간 서늘하게 21.0℃로 내린 이력 3회 주입
        recordTripEvent(Calendar.MONDAY, 815, 21.5f, true)
        recordTripEvent(Calendar.TUESDAY, 820, 21.0f, true)
        recordTripEvent(Calendar.WEDNESDAY, 810, 21.0f, true)
    }
}
