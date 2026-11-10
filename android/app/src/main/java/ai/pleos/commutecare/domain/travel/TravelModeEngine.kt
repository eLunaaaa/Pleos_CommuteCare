package ai.pleos.commutecare.domain.travel

import ai.pleos.commutecare.domain.audio.AcousticPreset

/**
 * @file TravelModeEngine.kt
 * @brief 주말/공휴일 또는 타 지역 리조트·콘도 목적지 설정 시 활성화되는 여행 케어 엔진.
 *
 * 동작 순서는 다음과 같음:
 *  1. 내비게이션 목적지 키워드("리조트", "호텔", "콘도", "펜션", "해수욕장", "국립공원" 등) 및 주말 여부 검사
 *  2. "여행을 떠나시나요?" 지능형 여행 모드 제안
 *  3. 드라이브 흥을 돋우는 신나는 업비트/여행 플레이리스트 & 오픈 에어 사운드스테이지 EQ 자동 매칭
 *  4. 도착지 주변의 대표 명소(POI) 및 현지 로컬 맛집 추천 브리핑 제공
 */

data class DestinationPoi(
    val name: String,
    val category: String, // "관광명소", "로컬 맛집", "뷰 포인트"
    val description: String
)

data class TravelModeRecommendation(
    val destinationName: String,
    val travelAudioPreset: AcousticPreset,
    val topPois: List<DestinationPoi>,
    val travelBriefingMessage: String
)

class TravelModeEngine {

    // 여행지/휴양지 판별 키워드
    private val travelKeywords = listOf("리조트", "호텔", "콘도", "펜션", "해수욕장", "캠핑", "글램핑", "수목원", "비발디", "하이원", "설악", "제주", "속초", "강릉")

    /**
     * @brief 입력된 목적지 명칭이 여행/레저 목적지인지 분석함.
     * @param destinationName 목적지 명칭 (예: "소노펠리체 비발디파크", "강릉 씨마크 호텔")
     * @param isWeekend 주말 여부
     * @return Boolean 여행 모드 대상 여부
     */
    fun isTravelDestination(destinationName: String, isWeekend: Boolean): Boolean {
        val matchesKeyword = travelKeywords.any { destinationName.contains(it) }
        return matchesKeyword || isWeekend
    }

    /**
     * @brief 여행 모드 활성화 시 맞춤 드라이브 사운드 및 주변 맛집/명소 데이터를 생성함.
     * @param destinationName 목적지 명칭
     * @return TravelModeRecommendation 여행 모드 음악 프리셋, POI 목록, 음성 브리핑
     */
    fun buildTravelRecommendation(destinationName: String): TravelModeRecommendation {
        val travelAcousticPreset = AcousticPreset(
            presetName = "Weekend Getaway & Road Trip Punch",
            genreDescription = "트로피컬 팝, 펑키 디스코, 90s 드라이브 명곡",
            recommendedMusic = "Surfaces - Sunday Best / Dua Lipa - Levitating",
            scientificBasis = "장거리 고속도로 주행 시의 단조로움을 타파하기 위해 120~128 BPM의 고흥분도 리듬과 넓은 사운드스테이지(Airy Treble + Punchy Bass)를 형성하여 운전자 각성 및 승차감 향상",
            bassGainDb = 4.0f,
            midGainDb = 1.5f,
            trebleGainDb = 3.5f
        )

        val pois = listOf(
            DestinationPoi(
                name = "해변 오션뷰 전망대",
                category = "관광명소",
                description = "차량 진입 후 탁 트인 동해 바다를 조망할 수 있는 사진 명소입니다."
            ),
            DestinationPoi(
                name = "30년 전통 초당 순두부 본점",
                category = "로컬 맛집",
                description = "현지인들이 추천하는 대표 맛집으로 얼큰 순두부 전골이 유명합니다."
            ),
            DestinationPoi(
                name = "솔숲 힐링 산책로",
                category = "뷰 포인트",
                description = "도착 후 가볍게 운전 피로를 풀 수 있는 20분 코스의 피톤치드 산책길입니다."
            )
        )

        val briefing = "신나는 주말 여행길이 되실 수 있도록 드라이브 전용 사운드와 도착지 주변 유명 맛집 및 명소를 준비했습니다. 안전하고 즐거운 여행 되세요!"

        return TravelModeRecommendation(
            destinationName = destinationName,
            travelAudioPreset = travelAcousticPreset,
            topPois = pois,
            travelBriefingMessage = briefing
        )
    }
}
