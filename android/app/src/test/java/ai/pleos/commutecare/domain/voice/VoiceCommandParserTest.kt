/**
 * @file VoiceCommandParserTest.kt
 * @brief 취소 우선순위, 구체적 시작 명령, 체감 온도 방향 및 단독 수락을 회귀 검증한다.
 */
package ai.pleos.commutecare.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandParserTest {
    /** @brief 기능 키워드가 포함되어도 부정을 먼저 해석한다. @return Unit @throws 없음 */
    @Test fun cancellationWinsOverStart() {
        listOf("출근 취소", "여행 시작하지마", "음악 그만", "음악 꺼줘", "에어컨 켜지마", "뉴스 요약 취소").forEach {
            assertEquals(it, VoiceCommand.CANCEL, VoiceCommandParser.parse(it))
        }
        assertEquals(VoiceCommand.STOP_CLIMATE, VoiceCommandParser.parse("에어컨 꺼줘"))
    }

    /** @brief 일반 시작 단어가 기능별 명령을 가로채지 않는다. @return Unit @throws 없음 */
    @Test fun specificCommandsWinOverGenericStart() {
        assertEquals(VoiceCommand.TRAVEL, VoiceCommandParser.parse("여행 시작"))
        assertEquals(VoiceCommand.MUSIC, VoiceCommandParser.parse("음악 재생 시작"))
        assertEquals(VoiceCommand.DEMO, VoiceCommandParser.parse("시연 시작"))
        assertEquals(VoiceCommand.EVENING, VoiceCommandParser.parse("퇴근 시작"))
    }

    /** @brief 더우면 낮추고 추우면 높인다. @return Unit @throws 없음 */
    @Test fun temperatureDirectionMatchesDiscomfort() {
        assertEquals(VoiceCommand.TEMPERATURE_DOWN, VoiceCommandParser.parse("더워"))
        assertEquals(VoiceCommand.TEMPERATURE_UP, VoiceCommandParser.parse("추워"))
        assertEquals(VoiceCommand.TEMPERATURE_UP, VoiceCommandParser.parse("온도 올려줘"))
        assertEquals(VoiceCommand.TEMPERATURE_DOWN, VoiceCommandParser.parse("온도 내려줘"))
    }

    /** @brief 수락은 독립된 짧은 응답에만 매칭한다. @return Unit @throws 없음 */
    @Test fun confirmationRequiresStandaloneResponse() {
        assertEquals(VoiceCommand.CONFIRM, VoiceCommandParser.parse("네!"))
        assertEquals(VoiceCommand.UNKNOWN, VoiceCommandParser.parse("네비게이션"))
        assertEquals(VoiceCommand.UNKNOWN, VoiceCommandParser.parse("시동"))
    }
}
