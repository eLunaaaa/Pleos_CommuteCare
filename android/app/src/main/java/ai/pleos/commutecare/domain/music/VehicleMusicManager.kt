package ai.pleos.commutecare.domain.music

/**
 * @file VehicleMusicManager.kt
 * @brief YouTube Music 및 Android Media Session 연동 오디오 매니저.
 *
 * 동작 순서는 다음과 같음:
 *  1. 출퇴근/여행/기상 모드별 추천 곡에 대한 음원 식별자(YouTube Music ID, URI) 확인
 *  2. Android Automotive OS (AAOS) 표준 MediaSessionManager 또는 YouTube Music 인텐트 바인딩
 *  3. 심리음향학 EQ 필터 파라미터(Bass, Mid, Treble)를 AudioEffect/Equalizer에 적용
 *  4. 트랙 변경 및 재생 상태 콜백 수신
 */

data class PlaybackTrack(
    val title: String,
    val artist: String,
    val album: String,
    val sourcePlatform: MusicPlatform,
    val streamingUri: String,
    val targetBpm: Int,
    val contextTag: String // "출근 에너지", "퇴근 힐링", "주말 드라이브", "우천 감성"
)

enum class MusicPlatform {
    YOUTUBE_MUSIC,
    SPOTIFY,
    PLEOS_NATIVE_MEDIA
}

class VehicleMusicManager {

    private var currentPlayingTrack: PlaybackTrack? = null
    private var isPlaying: Boolean = false

    /**
     * @brief 추천된 트랙 정보를 받아 YouTube Music 또는 AAOS 미디어 세션으로 재생을 요청함.
     * @param track 재생할 곡 메타데이터 (PlaybackTrack)
     * @param onPlaybackStateChanged 재생 상태 변경 콜백 (Boolean: 재생 여부, PlaybackTrack?: 현재 트랙)
     * @return Unit
     */
    fun playCuratedTrack(
        track: PlaybackTrack,
        onPlaybackStateChanged: (Boolean, PlaybackTrack) -> Unit
    ) {
        currentPlayingTrack = track
        isPlaying = true

        // 실제 Android Automotive 환경:
        // val intent = Intent(Intent.ACTION_VIEW, Uri.parse(track.streamingUri))
        // intent.setPackage("com.google.android.apps.youtube.music") 또는 MediaSession API 호출

        onPlaybackStateChanged(true, track)
    }

    /**
     * @brief 현재 상황(출근/퇴근/여행/우천)에 매핑되는 기본 YouTube Music 추천 트랙 리스트를 반환함.
     * @param modeTag 컨텍스트 태그 (예: "MORNING", "EVENING", "TRAVEL", "RAIN")
     * @return PlaybackTrack 큐레이션된 음원 정보
     */
    fun getPresetTrack(modeTag: String): PlaybackTrack {
        return when (modeTag) {
            "TRAVEL" -> PlaybackTrack(
                title = "Sunday Best",
                artist = "Surfaces",
                album = "Where the Light Is",
                sourcePlatform = MusicPlatform.YOUTUBE_MUSIC,
                streamingUri = "https://music.youtube.com/watch?v=mGfI8W4v2k8",
                targetBpm = 118,
                contextTag = "주말 드라이브"
            )
            "EVENING" -> PlaybackTrack(
                title = "Fly Me to the Moon",
                artist = "Frank Sinatra",
                album = "It Might as Well Be Swing",
                sourcePlatform = MusicPlatform.YOUTUBE_MUSIC,
                streamingUri = "https://music.youtube.com/watch?v=5hxibHJOE5E",
                targetBpm = 95,
                contextTag = "퇴근 힐링"
            )
            "RAIN" -> PlaybackTrack(
                title = "You Must Believe in Spring",
                artist = "Bill Evans",
                album = "You Must Believe in Spring",
                sourcePlatform = MusicPlatform.YOUTUBE_MUSIC,
                streamingUri = "https://music.youtube.com/watch?v=43j5g7y7a1",
                targetBpm = 82,
                contextTag = "우천 감성"
            )
            else -> PlaybackTrack(
                title = "Viva La Vida",
                artist = "Coldplay",
                album = "Viva la Vida",
                sourcePlatform = MusicPlatform.YOUTUBE_MUSIC,
                streamingUri = "https://music.youtube.com/watch?v=dvgZkm1xWPE",
                targetBpm = 122,
                contextTag = "출근 에너지"
            )
        }
    }
}
