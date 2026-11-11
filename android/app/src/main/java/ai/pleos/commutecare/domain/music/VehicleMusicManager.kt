/**
 * @file VehicleMusicManager.kt
 * @brief YouTube Music 및 AAOS 미디어 플랫폼 연동 오디오 매니저
 *
 * [주요 동작 흐름]
 * 1. 출퇴근/여행/우천 등 주행 컨텍스트 및 심리음향학(Psychoacoustics) EQ에 맞춘 최적 트랙 큐레이션
 * 2. 실제 차량에 YouTube Music 앱(com.google.android.apps.youtube.music)이 설치되어 있는지 패키지 매니저로 검사
 * 3. 설치된 경우:
 *    - YouTube Music 전용 Deep-link (Intent.ACTION_VIEW) 또는 MediaStore 검색 재생 인텐트 발송
 * 4. 미설치된 환경(에뮬레이터 및 순정 AAOS 환경):
 *    - 시스템 기본 브라우저/웹뷰(org.chromium.webview_shell 등)를 통한 YouTube Music 웹 재생으로 안전하게 폴백
 * 5. 주행 제한 시 화면 실행을 거절하고, 실행 가능한 앱이 없으면 실패를 반환한다.
 * 앱 실행 성공은 실제 음원 재생 완료를 의미하지 않는다.
 */

package ai.pleos.commutecare.domain.music

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.util.Log

/**
 * @brief 재생 트랙 메타데이터 클래스
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

/**
 * @brief 지원 음원 플랫폼
 */
enum class MusicPlatform {
    YOUTUBE_MUSIC,
    AAOS_SYSTEM_MEDIA,
    WEB_BROWSER_FALLBACK
}

/**
 * @brief 음원 재생 결과 상태
 */
sealed class MusicLaunchResult {
    data class SuccessApp(val packageName: String, val track: PlaybackTrack) : MusicLaunchResult()
    data class SuccessBrowser(val uri: String, val track: PlaybackTrack) : MusicLaunchResult()
    data class Failure(val reason: String) : MusicLaunchResult()
}

class VehicleMusicManager {

    companion object {
        private const val TAG = "VehicleMusicManager"
        const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }


    /**
     * @brief 기기에 YouTube Music 공식 앱이 설치되어 있는지 확인합니다.
     * @param context 안드로이드 컨텍스트
     * @return Boolean 설치 여부
     * @throws 없음
     */
    fun isYouTubeMusicInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(YOUTUBE_MUSIC_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * @brief 추천된 트랙 정보를 실제 YouTube Music 앱 또는 웹 플레이어로 연동하여 재생을 시도합니다.
     * @param context 안드로이드 컨텍스트
     * @param track 재생할 곡 메타데이터
     * @param isUxRestricted AAOS 제한 여부 (미확인은 true)
     * @throws 없음
     * @return MusicLaunchResult 실행 결과 (앱 직접 실행 / 브라우저 폴백 / 실패)
     */
    fun launchTrackPlayback(context: Context, track: PlaybackTrack, isUxRestricted: Boolean = true): MusicLaunchResult {
        if (isUxRestricted) return MusicLaunchResult.Failure("주행 제한 또는 차량 상태 미확인: 정차 후 음악 화면을 열어주세요.")
        Log.i(TAG, "음악 재생 요청: ${track.artist} - ${track.title} (URI: ${track.streamingUri})")

        // 1. YouTube Music 전용 앱이 설치되어 있는 경우 (실제 차량 환경)
        if (isYouTubeMusicInstalled(context)) {
            try {
                // Method A: YouTube Music Deep-Link View Intent
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(track.streamingUri)).apply {
                    setPackage(YOUTUBE_MUSIC_PACKAGE)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                Log.i(TAG, "[성공] YouTube Music 전용 앱으로 트랙 재생 시작: ${track.title}")
                return MusicLaunchResult.SuccessApp(YOUTUBE_MUSIC_PACKAGE, track)
            } catch (e: Exception) {
                Log.w(TAG, "YouTube Music 앱 직접 실행 실패, 검색 인텐트로 시도: ${e.message}")
                try {
                    // Method B: MediaStore Search Intent
                    val searchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                        setPackage(YOUTUBE_MUSIC_PACKAGE)
                        putExtra(MediaStore.EXTRA_MEDIA_FOCUS, MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE)
                        putExtra(MediaStore.EXTRA_MEDIA_TITLE, track.title)
                        putExtra(MediaStore.EXTRA_MEDIA_ARTIST, track.artist)
                        putExtra(SearchManager.QUERY, "${track.artist} ${track.title}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(searchIntent)
                    return MusicLaunchResult.SuccessApp(YOUTUBE_MUSIC_PACKAGE, track)
                } catch (ex: Exception) {
                    Log.w(TAG, "YouTube Music 검색 인텐트 실패: ${ex.message}")
                }
            }
        }

        // 2. YouTube Music 전용 앱 미설치 시 브라우저 및 웹뷰로 안전하게 폴백
        // Attempt 2-A: AAOS 기본 탑재 Chromium WebView Shell 명시적 실행
        try {
            val shellIntent = Intent(Intent.ACTION_VIEW, Uri.parse(track.streamingUri)).apply {
                setClassName("org.chromium.webview_shell", "org.chromium.webview_shell.WebViewBrowserActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(shellIntent)
            Log.i(TAG, "[성공] AAOS Chromium WebView Shell을 통해 YouTube Music 웹 플레이어 연동 성공")
            return MusicLaunchResult.SuccessBrowser(track.streamingUri, track)
        } catch (e: Exception) {
            Log.w(TAG, "Chromium Shell 실행 실패, 일반 시스템 브라우저 탐색으로 대체: ${e.message}")
        }

        // Attempt 2-B: 범용 BROWSABLE Intent 실행
        try {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(track.streamingUri)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
            Log.i(TAG, "[성공] 시스템 기본 브라우저를 통해 YouTube Music 연동 성공")
            return MusicLaunchResult.SuccessBrowser(track.streamingUri, track)
        } catch (e: Exception) {
            Log.w(TAG, "시스템 브라우저 실행 실패: ${e.message}")
        }

        return MusicLaunchResult.Failure("음악 앱이나 웹 플레이어를 실행할 수 없습니다.")
    }

    /**
     * @brief 현재 상황(출근/퇴근/여행/우천)에 매핑되는 심리음향학 기반 추천 트랙 리스트를 반환합니다.
     * @param modeTag 컨텍스트 태그 (예: "MORNING", "EVENING", "TRAVEL", "RAIN")
     * @return PlaybackTrack 큐레이션된 음원 정보
     * @throws 없음
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
