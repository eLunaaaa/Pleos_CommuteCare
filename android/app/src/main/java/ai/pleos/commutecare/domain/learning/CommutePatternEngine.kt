/**
 * @file CommutePatternEngine.kt
 * @brief 운전자의 출퇴근 요일·시간대 및 수동 온도 설정 이력을 학습하는 적응형 시계열 예측 엔진.
 *
 * 동작 순서는 다음과 같음:
 *  1. 탑승 시 요일, 출발 시각, 수동 설정 온도를 시계열 히스토리(`TripLog`)에 기록
 *  2. 동일 요일·출발 시간대 통근이 3회 이상 쌓이면 제안 앞에 능동 인사를 덧붙임 (사용자 수락 유지)
 *  3. 운전자가 수동 조절한 온도 데이터를 RNN 지수이동평균(EMA / Recurrent Weighted State)으로 가중 반영하여 최적 선호 온도 자동 수렴
 *  4. 학습 상태를 데이터 계층에 전달해 재시작 후에도 복원 (자동 시동 예약은 미구현)
 */

package ai.pleos.commutecare.domain.learning

import java.util.Calendar
import kotlin.math.roundToInt


data class TripLog(
    val dayOfWeek: Int,        // Calendar.MONDAY ~ SUNDAY
    val hourMinute: Int,       // HHmm 형식 (예: 0815 -> 8시 15분)
    val manualSetTempCelsius: Float,
    val isMorningCommute: Boolean
)

data class LearnedCommutePattern(
    val isAutoGreetingUnlocked: Boolean,     // 통근 제안에 능동 인사를 추가할 수 있는지 여부
    val predictedDepartureHourMinute: Int,   // 예측 출발 시각 (HHmm)
    val personalizedTargetTemp: Float,       // 개인화 수렴 목표 온도 (℃)
    val autoPreconditioningEligible: Boolean // 향후 자동 케어 검토용 학습 지표 (자동 시동 미구현)
)

class CommutePatternEngine(
    initialHistory: List<TripLog> = emptyList(),
    private val onHistoryChanged: (List<TripLog>) -> Unit = {},
    initialPreferredTemp: Float? = null,
    private val onPreferredTempChanged: (Float) -> Unit = {}
) {

    // 운전자 운행 및 온도 조절 시계열 기록 리스트
    private val tripHistory = mutableListOf<TripLog>()

    // 기본 기준 온도 (22.0℃)
    private var recurrentStateTemp: Float = 22.0f

    // EMA 학습률 (새로운 수동 조절값의 반영 가중치)
    private val alpha: Float = 0.35f

    init {
        // 복원 중에는 저장 콜백을 호출하지 않아 저장된 EMA 상태를 덮어쓰지 않는다.
        initialHistory.takeLast(200).forEach {
            tripHistory.add(it)
            recurrentStateTemp = (1.0f - alpha) * recurrentStateTemp + alpha * it.manualSetTempCelsius
        }
        if (initialPreferredTemp != null && initialPreferredTemp.isFinite() && initialPreferredTemp in 16f..30f) {
            recurrentStateTemp = initialPreferredTemp
        }
    }

    /**
     * @brief 통근 횟수 증가 없이 실제 적용된 수동 온도만 EMA에 반영하고 저장한다.
     * @param temperature 실제 차량에 적용된 목표 온도 (16~30℃)
     * @return Unit
     * @throws 없음
     */
    fun updateTemperaturePreference(temperature: Float) {
        if (!temperature.isFinite() || temperature !in 16f..30f) return
        recurrentStateTemp = (1.0f - alpha) * recurrentStateTemp + alpha * temperature
        onPreferredTempChanged(recurrentStateTemp)
    }

    /**
     * @brief 운전자의 실제 탑승 및 수동 공조 조절 이벤트를 시계열에 기록하고 선호 온도를 업데이트함.
     * @param dayOfWeek 요일 (Calendar.MONDAY..SUNDAY)
     * @param hourMinute 탑승 시각 (HHmm, 예: 820 -> 08:20)
     * @param userAdjustedTemp 사용자가 직접 조작한 최종 공조 온도 (℃)
     * @param isMorning 출근 모드 여부 (true: 출근, false: 퇴근)
     * @return Unit
     * @throws 없음
     */
    fun recordTripEvent(dayOfWeek: Int, hourMinute: Int, userAdjustedTemp: Float, isMorning: Boolean) {
        if (dayOfWeek !in 1..7 || hourMinute / 100 !in 0..23 || hourMinute % 100 !in 0..59 ||
            !userAdjustedTemp.isFinite() || userAdjustedTemp !in 16f..30f) return
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
        updateTemperaturePreference(userAdjustedTemp)
        if (tripHistory.size > 200) tripHistory.removeAt(0)
        onHistoryChanged(tripHistory.toList())
    }

    /**
     * @brief 현재 요일 및 시간대와 누적된 시계열 데이터를 분석하여 개인화된 패턴을 도출함.
     * @param currentDayOfWeek 현재 요일
     * @param currentHourMinute 현재 시각 (HHmm)
     * @param isMorning 출퇴근 구분
     * @return LearnedCommutePattern 학습된 자동 인사 여부, 예측 시각, 맞춤 목표 온도, 원격 시동 자격
     * @throws 없음
     */
    fun predictPattern(currentDayOfWeek: Int, currentHourMinute: Int, isMorning: Boolean): LearnedCommutePattern {
        val currentMinutes = currentHourMinute / 100 * 60 + currentHourMinute % 100
        val matchingTrips = tripHistory.filter {
            val minutes = it.hourMinute / 100 * 60 + it.hourMinute % 100
            it.dayOfWeek == currentDayOfWeek && it.isMorningCommute == isMorning &&
                kotlin.math.abs(minutes - currentMinutes) <= 60
        }

        // 동일 요일/동일 시간대 반복 운행 횟수 (3회 이상이면 신뢰도 확보)
        val sampleCount = matchingTrips.size
        val isUnlocked = sampleCount >= 3

        val predictedTime = if (matchingTrips.isNotEmpty()) {
            val minutes = matchingTrips.map { it.hourMinute / 100 * 60 + it.hourMinute % 100 }.average().roundToInt()
            minutes / 60 * 100 + minutes % 60
        } else {
            if (isMorning) 800 else 1830
        }

        return LearnedCommutePattern(
            isAutoGreetingUnlocked = isUnlocked,
            predictedDepartureHourMinute = predictedTime,
            personalizedTargetTemp = (recurrentStateTemp * 10).roundToInt() / 10.0f,
            autoPreconditioningEligible = sampleCount >= 5 // 미래 자동 케어 판단 지표, 실제 자동 시동 예약은 하지 않는다.
        )
    }

}
