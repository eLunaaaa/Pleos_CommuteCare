package ai.pleos.commutecare.domain.news

enum class UserAgeGroup {
    TWENTIES,   // 20대
    THIRTIES,   // 30대
    FORTIES,    // 40대
    FIFTY_PLUS  // 50대 이상
}

enum class UserGender {
    MALE,
    FEMALE,
    UNSPECIFIED
}

data class NewsItem(
    val id: String,
    val category: String,
    val title: String,
    val summary: String,
    val targetAges: List<UserAgeGroup>,
    val relevanceTag: String
)

/**
 * 인구통계학적 기본 추천(Cold-Start) -> 사용자 인터랙션(Yes/No 수락) 기반
 * 관심사 선호도 학습(Active Learning) 뉴스 추천 엔진
 */
class AdaptiveNewsEngine(
    var ageGroup: UserAgeGroup = UserAgeGroup.THIRTIES,
    var gender: UserGender = UserGender.MALE
) {
    // 카테고리별 사용자 선호도 점수 (수락 시 +3, 거절 시 -1)
    private val categoryWeights = mutableMapOf<String, Int>(
        "IT/모빌리티" to 5,
        "경제/재테크" to 4,
        "사회/라이프스타일" to 3,
        "글로벌 시사" to 3,
        "문화/트렌드" to 3
    )

    private val newsDatabase = listOf(
        NewsItem(
            id = "news_01",
            category = "IT/모빌리티",
            title = "현대차 SDV 전환 속도… 차량용 AI OS 탑재 가속화",
            summary = "소프트웨어 중심 자동차(SDV) 생태계를 위해 개발자 플랫폼 및 Pleos 인포테인먼트 오픈 API가 대폭 확대됩니다.",
            targetAges = listOf(UserAgeGroup.TWENTIES, UserAgeGroup.THIRTIES, UserAgeGroup.FORTIES),
            relevanceTag = "SDV/테크"
        ),
        NewsItem(
            id = "news_02",
            category = "경제/재테크",
            title = "미 연준 금리 향방 및 국내 증시 모빌리티 섹터 동향",
            summary = "글로벌 금리 기조 완화 전망 속에 친환경차 및 배터리 관련 핵심 부품주의 실적 개선세가 돋보이고 있습니다.",
            targetAges = listOf(UserAgeGroup.THIRTIES, UserAgeGroup.FORTIES, UserAgeGroup.FIFTY_PLUS),
            relevanceTag = "재테크/증시"
        ),
        NewsItem(
            id = "news_03",
            category = "사회/라이프스타일",
            title = "워라밸과 유연근무제 정착… 직장인 통근 패턴의 변화",
            summary = "자율 출퇴근제 확산으로 출근 피크 시간대가 분산되며 차량 내 개인 힐링 공간 활용 수요가 급증하고 있습니다.",
            targetAges = listOf(UserAgeGroup.TWENTIES, UserAgeGroup.THIRTIES),
            relevanceTag = "직장인/출퇴근"
        ),
        NewsItem(
            id = "news_04",
            category = "문화/트렌드",
            title = "최신 팝/음악 트렌드와 차량 내 공간 음향(Spatial Audio) 인기",
            summary = "몰입형 음향 기술의 보급으로 차량 내부가 콘서트홀 같은 맞춤형 감상 공간으로 진화하고 있습니다.",
            targetAges = listOf(UserAgeGroup.TWENTIES, UserAgeGroup.THIRTIES),
            relevanceTag = "문화/오디오"
        ),
        NewsItem(
            id = "news_05",
            category = "글로벌 시사",
            title = "글로벌 완성차 메이커들의 자율주행 및 커넥티드 얼라이언스",
            summary = "보안 및 안전 규제 표준화에 대응하기 위한 글로벌 제조사들의 소프트웨어 기술 제휴가 활발해지고 있습니다.",
            targetAges = listOf(UserAgeGroup.FORTIES, UserAgeGroup.FIFTY_PLUS),
            relevanceTag = "글로벌/자동차"
        )
    )

    /**
     * 사용자 나이/성별 초기 가중치 + 누적된 관심도 점수 기반 최우선 뉴스 추천
     */
    fun recommendTopNews(): NewsItem {
        return newsDatabase.sortedByDescending { item ->
            var score = categoryWeights[item.category] ?: 0
            if (item.targetAges.contains(ageGroup)) {
                score += 3
            }
            score
        }.first()
    }

    /**
     * "이 뉴스 요약해드릴까요?" 에 대한 사용자 피드백 반영
     */
    fun recordUserFeedback(item: NewsItem, accepted: Boolean) {
        val current = categoryWeights[item.category] ?: 0
        if (accepted) {
            categoryWeights[item.category] = current + 3
        } else {
            categoryWeights[item.category] = (current - 1).coerceAtLeast(0)
        }
    }

    fun getTopInterestCategory(): String {
        return categoryWeights.maxByOrNull { it.value }?.key ?: "IT/모빌리티"
    }
}
