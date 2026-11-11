/**
 * @file AmbientLightingEngineTest.kt
 * @brief 그랜저 GN7 인터랙티브 무드램프 색상 튜닝 엔진 단위 테스트 (ipRGC, Hue-Heat, SAE 2018-01-0842)
 */

package ai.pleos.commutecare.domain.lighting

import ai.pleos.commutecare.data.weather.WeatherConditionTag
import ai.pleos.commutecare.domain.CommuteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLightingEngineTest {

    /**
     * @brief 아침 출근길 시 ipRGC 세포 자극을 위한 사이언/블루 파장(0x00B0FF) 도출 검증
     */
    @Test
    fun testMorningCircadianLighting() {
        val preset = AmbientLightingEngine.determineLightingPreset(
            commuteType = CommuteType.MORNING,
            weatherCondition = WeatherConditionTag.CLEAR,
            month = 4
        )

        assertEquals("Morning Alertness Cyan", preset.presetName)
        assertEquals(0x00B0FF, preset.colorHex)
        assertTrue(preset.scientificBasis.contains("ipRGC"))
    }

    /**
     * @brief 겨울철/한파 시 Hue-Heat 가설에 입각한 웜 앰버(0xFF7043) 도출 검증
     */
    @Test
    fun testWinterHueHeatLighting() {
        val preset = AmbientLightingEngine.determineLightingPreset(
            commuteType = CommuteType.MORNING,
            weatherCondition = WeatherConditionTag.COLD_WINTER,
            month = 1
        )

        assertEquals("Warm Hearth Amber", preset.presetName)
        assertEquals(0xFF7043, preset.colorHex)
        assertTrue(preset.scientificBasis.contains("Hue-Heat"))
    }

    /**
     * @brief 저녁 퇴근길 시 SAE 2018-01-0842 야간 암적응 가이드라인에 따른 소프트 골드(0xFFD54F) 도출 검증
     */
    @Test
    fun testEveningRelaxLighting() {
        val preset = AmbientLightingEngine.determineLightingPreset(
            commuteType = CommuteType.EVENING,
            weatherCondition = WeatherConditionTag.CLEAR,
            month = 4
        )

        assertEquals("Evening Soft Amber-Gold", preset.presetName)
        assertEquals(0xFFD54F, preset.colorHex)
        assertTrue(preset.referencePaper.contains("SAE Technical Paper 2018-01-0842"))
    }
}
