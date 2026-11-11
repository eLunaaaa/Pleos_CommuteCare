/**
 * @file PsychoacousticEngine.kt
 * @brief 기상·계절·통근·차속별 EQ 추천값을 계산한다. 실제 오디오 효과 적용은 수행하지 않는다.
 */
package ai.pleos.commutecare.domain.audio

import ai.pleos.commutecare.data.weather.WeatherConditionTag
import ai.pleos.commutecare.domain.CommuteType
import java.util.Calendar

data class AcousticPreset(
    val presetName: String,
    val genreDescription: String,
    val recommendedMusic: String,
    val scientificBasis: String,
    val bassGainDb: Float,    // 60Hz - 250Hz
    val midGainDb: Float,     // 1kHz - 3kHz
    val trebleGainDb: Float   // 4kHz - 8kHz
)

/**
 * 음향 음향학 및 차량 주행 환경 음향 마스킹(Vehicle Acoustic Masking & Psychoacoustics) 연구 기반
 * 날씨, 계절 및 출퇴근 상황별 이퀄라이저 & 음악 큐레이션 엔진
 */
object PsychoacousticEngine {

    /**
     * @brief 기존 심리음향학 프리셋에 차속별 저역 추천 보정을 더한다 (실제 오디오 미적용).
     * @param commuteType 출퇴근 모드
     * @param weatherCondition 기상 상태
     * @param month 현재 월 (1~12)
     * @param speedKmh 실제 차속 (km/h)
     * @return 추천 EQ 프리셋
     * @throws 없음
     */
    fun determineAudioPreset(
        commuteType: CommuteType,
        weatherCondition: WeatherConditionTag,
        month: Int = Calendar.getInstance().get(Calendar.MONTH) + 1,
        speedKmh: Float = 0f
    ): AcousticPreset {
        val preset = when {
            // 1. 우천/강설 상황: 빗방울 충격음(2kHz~6kHz 고주파) 및 타이어 수막 노이즈 마스킹
            weatherCondition == WeatherConditionTag.RAINY -> AcousticPreset(
                presetName = "Rainy Comfort & Speech Clarity",
                genreDescription = "재즈, 어쿠스틱 피아노, 로파이(Lo-Fi)",
                recommendedMusic = "Bill Evans - You Must Believe in Spring",
                scientificBasis = "빗방울의 글래스 충격음(2k~5kHz) 및 타이어 수막 찰랑임 소음으로 인한 피로도를 줄이기 위해 하이엔드(+4~8kHz)를 살짝 억제하고, 보컬/멜로디 영역(1k~3kHz)을 선명하게 부스팅하여 청각 피로 방지",
                bassGainDb = 1.5f,
                midGainDb = 3.5f,
                trebleGainDb = -1.0f
            )

            // 2. 겨울철 / 한파 (12~2월, 기온 5도 이하): 차가운 캐빈 재질 강성 증가 + 서늘한 기분 보정
            weatherCondition == WeatherConditionTag.SNOWY || weatherCondition == WeatherConditionTag.COLD_WINTER || month in listOf(12, 1, 2) -> AcousticPreset(
                presetName = "Warm Hearth & Low-Mid Body",
                genreDescription = "인디 포크, 어쿠스틱 발라드, 웜 톤 팝",
                recommendedMusic = "Damien Rice - The Blower's Daughter",
                scientificBasis = "겨울철 도어 실링 경화 및 타이어 저주파 진동에 대응하고, 추위로 인한 긴장 완화를 위해 200~500Hz 저-중역대를 온화하게 보강(Warmth boost)하여 심리적 안정감 제공",
                bassGainDb = 3.5f,
                midGainDb = 2.0f,
                trebleGainDb = 0.5f
            )

            // 3. 여름철 / 무더위 (6~8월, 기온 28도 이상): 강력한 에어컨 팬 블로워 노이즈(백색소음) 마스킹
            weatherCondition == WeatherConditionTag.HOT_SUMMER || month in listOf(6, 7, 8) -> AcousticPreset(
                presetName = "Cool Breeze & HVAC Fan Masking",
                genreDescription = "트로피컬 하우스, 펑크, 시티팝, 업비트 인디",
                recommendedMusic = "Daft Punk - Get Lucky",
                scientificBasis = "최대 가동되는 에어컨 블로워의 광대역 팬 소음(Fan Hiss)을 뚫고 리듬감을 전달하기 위해 하만 타겟 커브 기반 V자형 사운드(베이스 펀치 및 명료한 트레블) 적용",
                bassGainDb = 4.0f,
                midGainDb = 1.0f,
                trebleGainDb = 3.5f
            )

            // 4. 일반 출근 모드 (오전 각성 & 도파민 촉진)
            commuteType == CommuteType.MORNING -> AcousticPreset(
                presetName = "Morning Dopamine & Vocal Focus",
                genreDescription = "모던 락, 팝, 업템포 어쿠스틱 (110~125 BPM)",
                recommendedMusic = "Coldplay - Viva La Vida",
                scientificBasis = "오전 수면 관성(Sleep Inertia) 해소를 위해 운전자 휴식 심박수를 살짝 상회하는 템포와 뇌 언어/주의력 영역을 자극하는 보컬 대역(1.5k~3kHz) 강화",
                bassGainDb = 3.0f,
                midGainDb = 3.0f,
                trebleGainDb = 2.0f
            )

            // 5. 일반 퇴근 모드 (하루의 피로 이완 및 코르티솔 감소)
            else -> AcousticPreset(
                presetName = "Evening Unwind & Low-Stress Soundstage",
                genreDescription = "보사노바, 다운템포, 네오소울",
                recommendedMusic = "Norah Jones - Don't Know Why",
                scientificBasis = "퇴근길 교감신경 긴장을 이완시키기 위해 자극적인 고음 피크를 다듬고 부드러운 중저음 사운드스테이지를 형성하여 운전자 스트레스 지수 완화",
                bassGainDb = 2.5f,
                midGainDb = 2.5f,
                trebleGainDb = 0.0f
            )
        }
        // 고속 노면 소음에 대한 추천 보정은 제품 튜닝 휴리스틱이며 논문 검증값이 아니다.
        val speed = if (speedKmh.isFinite()) speedKmh.coerceIn(0f, 130f) else 0f
        val bassCorrection = ((speed - 40f) / 45f).coerceIn(0f, 1f) * 1.5f
        return if (bassCorrection == 0f) preset else preset.copy(
            bassGainDb = preset.bassGainDb + bassCorrection,
            scientificBasis = preset.scientificBasis + " / 차속 기반 저역 추천 +%.1fdB (휴리스틱·미적용)".format(bassCorrection)
        )
    }
}
