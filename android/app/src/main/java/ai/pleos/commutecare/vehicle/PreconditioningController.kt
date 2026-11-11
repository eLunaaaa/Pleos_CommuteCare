/**
 * @file PreconditioningController.kt
 * @brief 공조 전원→목표 온도→열선을 순차 적용하고 실패/시간 초과/종료 시 전원과 열선을 해제한다.
 */
package ai.pleos.commutecare.vehicle

/**
 * @brief SDK와 분리된 프리컨디셔닝 상태 관리. 모든 호출/콜백은 같은 스레드에서 실행한다.
 * @param power 캐빈 전체 공조 전원 제어
 * @param temperature 운전석 목표 온도 제어
 * @param seat 운전석 열선 제어
 * @param schedule 지연 실행 등록 후 취소 함수를 반환하는 타이머
 */
class PreconditioningController(
    private val power: (Boolean, (Boolean) -> Unit) -> Unit,
    private val temperature: (Float, (Boolean) -> Unit) -> Unit,
    private val seat: (Int, (Boolean) -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit)
) {
    private var generation = 0
    private var busy = false
    private var disposed = false
    private var pendingResult: ((Boolean) -> Unit)? = null
    private var cancelRequestTimeout: (() -> Unit)? = null
    private var cancelSafetyTimeout: (() -> Unit)? = null
    private var driverPresent = false
    var isActive = false
        private set

    /**
     * @brief 응답 시간 제한과 안전 타이머를 먼저 등록하고 세 제어를 순차 실행한다.
     * @param targetTemp 목표 온도 (16~30℃)
     * @param seatLevel 열선 단계 (0~3)
     * @param onComplete 세 단계 모두 성공했는지 한 번만 알리는 콜백
     * @return Unit
     * @throws 없음 (동기 SDK 예외도 실패로 처리)
     */
    fun start(targetTemp: Float, seatLevel: Int, onComplete: (Boolean) -> Unit) {
        if (disposed || busy || isActive || !targetTemp.isFinite() || targetTemp !in 16f..30f || seatLevel !in 0..3) {
            onComplete(false)
            return
        }
        busy = true
        isActive = true // 전원 요청이 이미 적용된 뒤 응답만 누락될 수도 있다.
        val request = ++generation
        pendingResult = onComplete
        if (!driverPresent) cancelSafetyTimeout = schedule(10 * 60 * 1000L) { stop() }
        cancelRequestTimeout = schedule(15_000L) { stop() }
        step(request, { power(true, it) }) {
            step(request, { temperature(targetTemp, it) }) {
                step(request, { seat(seatLevel, it) }) {
                    busy = false
                    cancelRequestTimeout?.invoke()
                    cancelRequestTimeout = null
                    val result = pendingResult
                    pendingResult = null
                    result?.invoke(true)
                }
            }
        }
    }

    /**
     * @brief 성공 응답만 다음 단계로 진행하고 중복/이전 세대 응답은 차단한다.
     * @param request 현재 요청 세대
     * @param action SDK 작업
     * @param next 다음 단계
     * @return Unit
     * @throws 없음
     */
    private fun step(request: Int, action: ((Boolean) -> Unit) -> Unit, next: () -> Unit) {
        var delivered = false
        try {
            action callback@{ success ->
                if (disposed) return@callback
                if (!delivered) {
                    delivered = true
                    if (request != generation) {
                        // 종료 뒤 늦은 성공은 장치 상태가 달라졌을 수 있어 종료를 재확인한다.
                        if (success) stop()
                    } else if (success) next() else stop()
                }
            }
        } catch (_: Exception) {
            if (!delivered && request == generation) stop()
        }
    }

    /**
     * @brief 운전자 주행 감지 시 미탑승 종료 예약을 해제한다. 요청 응답 제한은 유지한다.
     * @return Unit
     * @throws 없음
     */
    fun markDriverPresent() {
        driverPresent = true
        cancelSafetyTimeout?.invoke()
        cancelSafetyTimeout = null
    }

    /**
     * @brief 진행 중 요청을 실패로 완료하고 공조와 열선을 모두 끈다. 실패 시 안전 타이머로 재시도한다.
     * @return Unit
     * @throws 없음
     */
    fun stop() {
        if (disposed) return
        ++generation
        busy = false
        cancelRequestTimeout?.invoke()
        cancelRequestTimeout = null
        cancelSafetyTimeout?.invoke()
        // 종료 실패/응답 누락 때도 active를 유지하여 재시작과 성공 오보고를 막는다.
        cancelSafetyTimeout = schedule(30_000L) { stop() }
        val result = pendingResult
        pendingResult = null
        result?.invoke(false)
        var powerOff = false
        var seatOff = false
        val stopRequest = generation
        fun completeIfStopped() {
            if (stopRequest == generation && powerOff && seatOff) {
                isActive = false
                cancelSafetyTimeout?.invoke()
                cancelSafetyTimeout = null
            }
        }
        runCatching { power(false) { powerOff = it; completeIfStopped() } }
        runCatching { seat(0) { seatOff = it; completeIfStopped() } }
    }

    /**
     * @brief SDK 연결을 해제하기 직전에 예약을 제거한다. 호출자는 먼저 stop을 시도해야 한다.
     * @return Unit
     * @throws 없음
     */
    fun disposeTimers() {
        disposed = true
        ++generation
        val result = pendingResult
        pendingResult = null
        result?.invoke(false)
        cancelRequestTimeout?.invoke()
        cancelSafetyTimeout?.invoke()
        cancelRequestTimeout = null
        cancelSafetyTimeout = null
    }
}
