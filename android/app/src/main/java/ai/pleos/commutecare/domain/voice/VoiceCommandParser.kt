/**
 * @file VoiceCommandParser.kt
 * @brief 음성 문구를 취소 우선, 기능별 명령, 문맥 의존 수락 순서로 분류한다.
 */
package ai.pleos.commutecare.domain.voice

/** @brief 음성 입력으로 요청할 수 있는 기능. */
enum class VoiceCommand {
    CANCEL, STOP_CLIMATE, DEMO, NEWS, TEMPERATURE_UP, TEMPERATURE_DOWN,
    TRAVEL, CLIMATE, MUSIC, MORNING, EVENING, CONFIRM, UNKNOWN
}

object VoiceCommandParser {
    /**
     * @brief 구체적인 기능을 일반적인 시작/수락보다 먼저 판별하고 부정 명령을 우선한다.
     * @param text 인식한 음성 문구
     * @return 분류된 명령 (단독 수락은 실행 계층에서 대기 중 질문에만 적용)
     * @throws 없음
     */
    fun parse(text: String): VoiceCommand {
        val command = text.replace(" ", "").trim().trimEnd('.', '!', '?')
        fun has(vararg words: String) = words.any { command.contains(it) }
        return when {
            has("꺼", "중지", "종료", "그만", "취소") && has("공조", "에어컨", "히터", "프리컨디셔닝") -> VoiceCommand.STOP_CLIMATE
            has("아니", "취소", "그만", "중지", "정지", "종료", "지마", "안해", "싫어", "꺼") -> VoiceCommand.CANCEL
            has("시연", "데모") -> VoiceCommand.DEMO
            has("뉴스", "요약") -> VoiceCommand.NEWS
            has("온도올려", "올려줘", "따뜻", "추워", "추워요") -> VoiceCommand.TEMPERATURE_UP
            has("온도내려", "내려줘", "시원", "더워", "더워요") -> VoiceCommand.TEMPERATURE_DOWN
            has("여행", "콘도", "리조트", "주말") -> VoiceCommand.TRAVEL
            has("공조", "에어컨", "히터", "프리컨디셔닝") -> VoiceCommand.CLIMATE
            has("음악", "노래", "뮤직", "재생") -> VoiceCommand.MUSIC
            has("퇴근", "귀가") -> VoiceCommand.EVENING
            has("출근") -> VoiceCommand.MORNING
            command in setOf("네", "예", "응", "좋아", "좋아요", "시작", "시작해", "시작해줘") -> VoiceCommand.CONFIRM
            else -> VoiceCommand.UNKNOWN
        }
    }
}
