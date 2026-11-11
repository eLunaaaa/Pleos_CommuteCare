/**
 * @file PsychoacousticEngineTest.kt
 * @brief 심리음향학 기반 차량 환경 오디오 EQ 및 프리셋 도출 엔진 단위 테스트
 */

package ai.pleos.commutecare.domain.audio

import ai.pleos.commutecare.data.weather.WeatherConditionTag
import ai.pleos.commutecare.domain.CommuteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PsychoacousticEngineTest {

    /**
     * @brief 우천 상황 시 빗방울 충격음 마스킹을 위해 트레블 감쇄(-1.0dB) 및 보컬 대역 부스팅 검증
     */
    @Test
    fun testRainyAcousticPreset() {
        val preset = PsychoacousticEngine.determineAudioPreset(
            commuteType = CommuteType.MORNING,
            weatherCondition = WeatherConditionTag.RAINY,
            month = 5
        )

        assertEquals("Rainy Comfort & Speech Clarity", preset.presetName)
        assertTrue("빗방울 고주파 피로 방지를 위해 트레블 게인이 0dB 이하이어야 함", preset.trebleGainDb <= 0.0f)
        assertTrue("음성 명료도를 위해 미드 게인이 양수이어야 함", preset.midGainDb > 2.0f)
    }

    /**
     * @brief 여름철/폭염 상황 시 에어컨 팬 블로워 노이즈 마스킹을 위한 V자형 EQ(베이스/트레블 부스팅) 검증
     */
    @Test
    fun testHotSummerHVACMaskingPreset() {
        val preset = PsychoacousticEngine.determineAudioPreset(
            commuteType = CommuteType.MORNING,
            weatherCondition = WeatherConditionTag.HOT_SUMMER,
            month = 7
        )

        assertEquals("Cool Breeze & HVAC Fan Masking", preset.presetName)
        assertTrue("블로워 팬 소음 극복을 위해 베이스 부스팅 필요", preset.bassGainDb >= 3.5f)
        assertTrue("명료한 비트 전달을 위해 트레블 부스팅 필요", preset.trebleGainDb >= 3.0f)
    }

    /**
     * @brief 일반 출근 모드 시 수면 관성 해소(BPM 110~125) 및 보컬 포커스 검증
     */
    @Test
    fun testMorningCommutePreset() {
        val preset = PsychoacousticEngine.determineAudioPreset(
            commuteType = CommuteType.MORNING,
            weatherCondition = WeatherConditionTag.CLEAR,
            month = 4
        )

        assertEquals("Morning Dopamine & Vocal Focus", preset.presetName)
        assertTrue(preset.scientificBasis.contains("수면 관성"))
    }
}
