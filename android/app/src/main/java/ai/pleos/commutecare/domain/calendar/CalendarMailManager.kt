/**
 * @file CalendarMailManager.kt
 * @brief 계정 연결 전 브리핑 시연에 사용할 고정 일정·메일 데이터를 제공한다. 실제 계정 동기화는 미구현이다.
 */
package ai.pleos.commutecare.domain.calendar

data class WorkEvent(
    val id: String,
    val source: EventSource, // GOOGLE, OUTLOOK, ICAL
    val title: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val isUrgent: Boolean = false
)

data class WorkEmail(
    val id: String,
    val source: EventSource,
    val sender: String,
    val subject: String,
    val snippet: String,
    val receivedTime: String,
    val isImportant: Boolean = false
)

enum class EventSource {
    GOOGLE_CALENDAR,
    MICROSOFT_OUTLOOK,
    COMPANY_INTERNAL
}

/**
 * @brief 캘린더·메일 계정 연결을 대체하는 시연용 고정 데이터 제공자.
 * 실제 Google/Outlook OAuth 및 REST 동기화는 구현하지 않았다.
 */
class CalendarMailManager {

    /**
     * @brief 계정 연동 전 시연용 고정 일정을 반환한다.
     * @return 시연 일정 목록
     * @throws 없음
     */
    fun fetchTodayEvents(): List<WorkEvent> {
        return listOf(
            WorkEvent(
                id = "evt_01",
                source = EventSource.GOOGLE_CALENDAR,
                title = "현대차 커넥티드카 SW 플랫폼 아키텍처 주간 회의",
                startTime = "09:30",
                endTime = "10:30",
                location = "양재 본사 11층 회의실 A",
                isUrgent = true
            ),
            WorkEvent(
                id = "evt_02",
                source = EventSource.MICROSOFT_OUTLOOK,
                title = "Pleos 2.0 인포테인먼트 신규 기능 스프린트 리뷰",
                startTime = "14:00",
                endTime = "15:00",
                location = "Teams 온라인 미팅"
            ),
            WorkEvent(
                id = "evt_03",
                source = EventSource.GOOGLE_CALENDAR,
                title = "자율주행 센서 융합 프로젝트 싱크",
                startTime = "16:30",
                endTime = "17:15",
                location = "연구동 3층 프로젝트 룸"
            )
        )
    }

    /**
     * @brief 계정 연동 전 시연용 고정 메일을 반환한다.
     * @return 시연 메일 목록
     * @throws 없음
     */
    fun fetchUnreadImportantEmails(): List<WorkEmail> {
        return listOf(
            WorkEmail(
                id = "mail_01",
                source = EventSource.MICROSOFT_OUTLOOK,
                sender = "김수석 팀장 (SDV 개발팀)",
                subject = "[공지] 오늘 오전 9시 30분 아키텍처 회의실 변경 안내",
                snippet = "기존 9층 대신 11층 대회의실 A에서 오프라인 진행 예정이니 참고 바랍니다.",
                receivedTime = "07:45",
                isImportant = true
            ),
            WorkEmail(
                id = "mail_02",
                source = EventSource.GOOGLE_CALENDAR,
                sender = "Pleos DevRel Team",
                subject = "Pleos SDK 2.3.2 릴리즈 노트 및 추가 API 안내",
                snippet = "신규 조명 제어 및 도어/공조 센서 샘플 코드가 깃허브에 업데이트되었습니다.",
                receivedTime = "08:10",
                isImportant = false
            )
        )
    }
}
