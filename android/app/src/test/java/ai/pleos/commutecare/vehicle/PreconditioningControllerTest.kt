/**
 * @file PreconditioningControllerTest.kt
 * @brief 비동기 차량 제어의 전체 성공, 부분 실패, 응답 누락, 늦은 응답과 종료 재시도를 검증한다.
 */
package ai.pleos.commutecare.vehicle

import org.junit.Assert.*
import org.junit.Test

class PreconditioningControllerTest {
    private class Fixture {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false)
        val calls = mutableListOf<Pair<String, (Boolean) -> Unit>>()
        val tasks = mutableListOf<Task>()
        val results = mutableListOf<Boolean>()
        val controller = PreconditioningController(
            power = { value, done -> calls.add("power:$value" to done) },
            temperature = { value, done -> calls.add("temp:$value" to done) },
            seat = { value, done -> calls.add("seat:$value" to done) },
            schedule = { delay, action ->
                val task = Task(delay, action)
                tasks.add(task)
                val cancel: () -> Unit = { task.cancelled = true }
                cancel
            }
        )
        fun start() = controller.start(22f, 2, results::add)
        fun respond(name: String, success: Boolean): (Boolean) -> Unit {
            val index = calls.indexOfFirst { it.first == name }
            assertTrue("Expected operation $name; actual ${calls.map { it.first }}", index >= 0)
            val callback = calls.removeAt(index).second
            callback(success)
            return callback
        }
        fun finishStart() {
            start()
            respond("power:true", true)
            respond("temp:22.0", true)
            respond("seat:2", true)
        }
        fun fire(delay: Long) {
            val task = tasks.last { it.delay == delay && !it.cancelled }
            task.cancelled = true
            task.action()
        }
    }

    /** @brief 열선까지 성공해야 한 번만 완료한다. @return Unit @throws 없음 */
    @Test fun waitsForAllThreeSteps() {
        val f = Fixture()
        f.start()
        f.respond("power:true", true)
        assertTrue(f.results.isEmpty())
        f.respond("temp:22.0", true)
        assertTrue(f.results.isEmpty())
        val done = f.respond("seat:2", true)
        done(true)
        assertEquals(listOf(true), f.results)
        assertTrue(f.controller.isActive)
    }

    /** @brief 어느 단계의 실패든 성공으로 오보고하지 않고 공조와 열선을 종료한다. @return Unit @throws 없음 */
    @Test fun rollsBackEveryPartialFailure() {
        for (failedStep in listOf("power:true", "temp:22.0", "seat:2")) {
            val f = Fixture()
            f.start()
            if (failedStep != "power:true") f.respond("power:true", true)
            if (failedStep == "seat:2") f.respond("temp:22.0", true)
            f.respond(failedStep, false)
            assertEquals(listOf(false), f.results)
            f.respond("power:false", true)
            assertTrue(f.controller.isActive)
            f.respond("seat:0", true)
            assertFalse(f.controller.isActive)
            assertTrue(f.calls.isEmpty())
        }
    }

    /** @brief 요청 시간 초과 후 늦게 전원이 켜져도 다음 단계로 진행하지 않고 다시 끈다. @return Unit @throws 없음 */
    @Test fun timesOutAndUndoesLatePowerSuccess() {
        val f = Fixture()
        f.start()
        f.fire(15_000L)
        assertEquals(listOf(false), f.results)
        f.respond("power:false", true)
        f.respond("seat:0", true)
        f.respond("power:true", true)
        assertEquals(setOf("power:false", "seat:0"), f.calls.map { it.first }.toSet())
        assertEquals(listOf(false), f.results)
    }

    /** @brief 안전 종료는 성공한 프리컨디셔닝의 두 장치를 모두 끈다. @return Unit @throws 없음 */
    @Test fun safetyTimeoutStopsPowerAndSeat() {
        val f = Fixture()
        f.finishStart()
        f.fire(600_000L)
        assertEquals(setOf("power:false", "seat:0"), f.calls.map { it.first }.toSet())
        assertEquals(listOf(true), f.results)
    }

    /** @brief 운전자 확인은 미탑승 타이머만 해제하고 요청 응답 시간 제한을 유지한다. @return Unit @throws 없음 */
    @Test fun driverPresenceDoesNotCancelRequestTimeout() {
        val f = Fixture()
        f.start()
        f.controller.markDriverPresent()
        assertTrue(f.tasks.first { it.delay == 600_000L }.cancelled)
        assertFalse(f.tasks.first { it.delay == 15_000L }.cancelled)
        f.fire(15_000L)
        assertEquals(listOf(false), f.results)
    }

    /** @brief 종료 실패 시 active를 유지하고 재시도하며 새 시작을 거절한다. @return Unit @throws 없음 */
    @Test fun retriesFailedShutdownWithoutClaimingStopped() {
        val f = Fixture()
        f.finishStart()
        f.controller.stop()
        f.respond("power:false", false)
        f.respond("seat:0", true)
        assertTrue(f.controller.isActive)
        f.start()
        assertEquals(listOf(true, false), f.results)
        f.fire(30_000L)
        f.respond("power:false", true)
        f.respond("seat:0", true)
        assertFalse(f.controller.isActive)
    }

    /** @brief 동기 SDK 예외도 실패와 종료 요청으로 처리한다. @return Unit @throws 없음 */
    @Test fun catchesSynchronousSdkFailure() {
        val results = mutableListOf<Boolean>()
        val controller = PreconditioningController(
            power = { enabled, callback -> if (enabled) error("SDK unavailable") else callback(true) },
            temperature = { _, callback -> callback(true) },
            seat = { _, callback -> callback(true) },
            schedule = { _, _ -> {} }
        )
        controller.start(22f, 2, results::add)
        assertEquals(listOf(false), results)
        assertFalse(controller.isActive)
    }

    /** @brief 중복 시작과 유효하지 않은 입력은 새 장치 제어를 발생시키지 않는다. @return Unit @throws 없음 */
    @Test fun rejectsOverlappingAndInvalidRequests() {
        val f = Fixture()
        f.controller.start(Float.NaN, 2, f.results::add)
        f.controller.start(31f, 2, f.results::add)
        assertTrue(f.calls.isEmpty())
        f.start()
        f.start()
        assertEquals(listOf(false, false, false), f.results)
        assertEquals(listOf("power:true"), f.calls.map { it.first })
    }

    /** @brief 연결 해제 뒤 늦은 응답은 새 안전 타이머나 SDK 작업을 만들지 않는다. @return Unit @throws 없음 */
    @Test fun disposedControllerIgnoresLateCallbacks() {
        val f = Fixture()
        f.start()
        f.controller.stop()
        f.controller.disposeTimers()
        val count = f.tasks.size
        f.respond("power:true", true)
        assertEquals(count, f.tasks.size)
        assertTrue(f.tasks.all { it.cancelled })
        assertEquals(listOf(false), f.results)
    }
}
