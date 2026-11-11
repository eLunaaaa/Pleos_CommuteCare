/**
 * @file VehicleMusicManagerTest.kt
 * @brief YouTube Music 및 심리음향학 기반 음원 큐레이션 엔진 단위 테스트.
 *
 * [테스트 시나리오]
 *  1. MORNING (출근): 도파민 분비 및 각성을 위한 120+ BPM 트랙 매핑 검증
 *  2. EVENING (퇴근): 부교감 신경 활성화 및 심신 안정을 위한 90~100 BPM 트랙 매핑 검증
 *  3. TRAVEL (주말 리조트/여행): 경쾌한 리듬의 주말 드라이브 트랙 매핑 검증
 *  4. RAIN (우천): 노면 소음 마스킹 및 차분한 재즈 트랙 매핑 검증
 *  5. YouTube Music 유효 URI 형식 및 플랫폼 식별자 검증
 */

package ai.pleos.commutecare.domain.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VehicleMusicManagerTest {

    private lateinit var musicManager: VehicleMusicManager

    @Before
    fun setUp() {
        musicManager = VehicleMusicManager()
    }

    /**
     * @brief 출근 모드(MORNING) 시 122 BPM 각성 트랙 및 YouTube Music URI 반환 검증
     */
    @Test
    fun testMorningCommutePresetTrack() {
        val track = musicManager.getPresetTrack("MORNING")

        assertEquals("Viva La Vida", track.title)
        assertEquals("Coldplay", track.artist)
        assertEquals(122, track.targetBpm)
        assertEquals(MusicPlatform.YOUTUBE_MUSIC, track.sourcePlatform)
        assertTrue(track.streamingUri.startsWith("https://music.youtube.com/watch?v="))
        assertEquals("출근 에너지", track.contextTag)
    }

    /**
     * @brief 퇴근 모드(EVENING) 시 95 BPM 이완 트랙 및 YouTube Music URI 반환 검증
     */
    @Test
    fun testEveningCommutePresetTrack() {
        val track = musicManager.getPresetTrack("EVENING")

        assertEquals("Fly Me to the Moon", track.title)
        assertEquals("Frank Sinatra", track.artist)
        assertEquals(95, track.targetBpm)
        assertEquals(MusicPlatform.YOUTUBE_MUSIC, track.sourcePlatform)
        assertTrue(track.streamingUri.startsWith("https://music.youtube.com/watch?v="))
        assertEquals("퇴근 힐링", track.contextTag)
    }

    /**
     * @brief 여행 모드(TRAVEL) 시 118 BPM 주말 드라이브 트랙 반환 검증
     */
    @Test
    fun testTravelModePresetTrack() {
        val track = musicManager.getPresetTrack("TRAVEL")

        assertEquals("Sunday Best", track.title)
        assertEquals("Surfaces", track.artist)
        assertEquals(118, track.targetBpm)
        assertEquals(MusicPlatform.YOUTUBE_MUSIC, track.sourcePlatform)
        assertTrue(track.streamingUri.startsWith("https://music.youtube.com/watch?v="))
        assertEquals("주말 드라이브", track.contextTag)
    }

    /**
     * @brief 우천 모드(RAIN) 시 82 BPM 감성 트랙 반환 검증
     */
    @Test
    fun testRainModePresetTrack() {
        val track = musicManager.getPresetTrack("RAIN")

        assertEquals("You Must Believe in Spring", track.title)
        assertEquals("Bill Evans", track.artist)
        assertEquals(82, track.targetBpm)
        assertEquals(MusicPlatform.YOUTUBE_MUSIC, track.sourcePlatform)
        assertTrue(track.streamingUri.startsWith("https://music.youtube.com/watch?v="))
        assertEquals("우천 감성", track.contextTag)
    }

    /**
     * @brief 알 수 없는 태그 입력 시 기본(출근 에너지) 트랙 폴백 검증
     */
    @Test
    fun testUnknownTagFallback() {
        val track = musicManager.getPresetTrack("UNKNOWN_TAG")

        assertEquals("Viva La Vida", track.title)
        assertEquals(122, track.targetBpm)
    }
}
