/**
 * @file PleosVoiceManager.kt
 * @brief Pleos Playground TextToSpeech SDK 기반 차량 음성 안내 및 로컬 TTS 하이브리드 폴백 매니저
 *
 * [주요 동작 흐름]
 * 1. 현대자동차 Pleos Connect 생태계의 공식 TextToSpeech SDK(ai.pleos.playground.tts.TextToSpeech) 초기화
 * 2. CaaS On-Device TTS 엔진 바인딩 시도 및 상태 리스너 등록
 * 3. 온디바이스 엔진 미지원 또는 네트워크 장애 시 안드로이드 표준 TTS(android.speech.tts.TextToSpeech)로 자동 하이브리드 폴백
 * 4. 주행 소음 마스킹 및 IVI 안내 음성 큐잉/플러시 제어 및 재생 완료 이벤트 전파
 */

package ai.pleos.commutecare.domain.voice

import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech as AndroidTts
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class PleosVoiceManager(private val context: Context) {

    companion object {
        private const val TAG = "PleosVoiceManager"
        private const val PLEOS_CAAS_PACKAGE = "ai.pleos.playground.caas"
        private const val UTTERANCE_ID_PREFIX = "pleos_voice_"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // Pleos SDK TTS 엔진
    private var pleosTts: PleosTts? = null
    private var isPleosTtsReady: Boolean = false

    // 로컬 하이브리드 폴백용 Android 기본 TTS 엔진
    private var fallbackTts: AndroidTts? = null
    private var isFallbackReady: Boolean = false

    // 현재 발화 중 여부
    var isSpeaking: Boolean = false
        private set

    // 음성 발화 상태 콜백 리스너
    var onSpeechStateChangeListener: ((isSpeaking: Boolean, text: String?) -> Unit)? = null

    init {
        initializeEngines()
    }

    /**
     * @brief Pleos TTS 엔진 및 로컬 백업 폴백 엔진을 초기화합니다.
     */
    private fun initializeEngines() {
        Log.i(TAG, "Initializing Pleos TextToSpeech SDK (Mode.ON_DEVICE)...")
        try {
            val tts = PleosTts(context, Mode.ON_DEVICE)

            // initialize()를 먼저 호출해야 내부 TtsServiceManager 인스턴스가 연결됨
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    Log.i(TAG, "[PleosTTS] Pleos TextToSpeech SDK successfully initialized.")
                    try {
                        tts.addEventListener(object : EventListener {
                            override fun onStart() {
                                Log.d(TAG, "[PleosTTS] onStart")
                                mainHandler.post {
                                    isSpeaking = true
                                    onSpeechStateChangeListener?.invoke(true, null)
                                }
                            }

                            override fun onReady() {
                                Log.d(TAG, "[PleosTTS] onReady")
                            }

                            override fun onDone() {
                                Log.d(TAG, "[PleosTTS] onDone")
                                mainHandler.post {
                                    isSpeaking = false
                                    onSpeechStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onError(errorType: ErrorType) {
                                Log.w(TAG, "[PleosTTS] onError: $errorType")
                                mainHandler.post {
                                    isSpeaking = false
                                    onSpeechStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onStop() {
                                Log.d(TAG, "[PleosTTS] onStop")
                                mainHandler.post {
                                    isSpeaking = false
                                    onSpeechStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onUpdatedRms(rms: Double) {
                                // 음성 파형 레벨 실시간 모니터링 (UI 비주얼라이저 연동 가능)
                            }
                        })

                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts.setAudioAttributes(audioAttributes)
                        tts.setLocale(Locale.KOREAN)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply attributes post-init", e)
                    }
                    isPleosTtsReady = true
                }

                override fun onFailure(errorType: ErrorType) {
                    Log.w(TAG, "[PleosTTS] Initialization failed ($errorType). Activating fallback Android TTS.")
                    isPleosTtsReady = false
                    initFallbackTts()
                }
            })

            pleosTts = tts
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate PleosTts, activating local fallback", e)
            initFallbackTts()
        }
    }

    /**
     * @brief 하이브리드 가용성을 위한 로컬 Android 기본 TTS 엔진을 초기화합니다.
     */
    private fun initFallbackTts() {
        if (fallbackTts != null || isFallbackReady) return
        Log.i(TAG, "Initializing Android Standard TTS Fallback...")

        val initListener = AndroidTts.OnInitListener { status ->
            if (status == AndroidTts.SUCCESS) {
                fallbackTts?.language = Locale.KOREAN
                fallbackTts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        mainHandler.post {
                            isSpeaking = true
                            onSpeechStateChangeListener?.invoke(true, null)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post {
                            isSpeaking = false
                            onSpeechStateChangeListener?.invoke(false, null)
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post {
                            isSpeaking = false
                            onSpeechStateChangeListener?.invoke(false, null)
                        }
                    }
                })
                isFallbackReady = true
                Log.i(TAG, "Android Standard TTS Fallback successfully initialized.")
            } else {
                Log.w(TAG, "Android Standard TTS Fallback initialization status: $status")
                isFallbackReady = false
            }
        }

        try {
            fallbackTts = AndroidTts(context, initListener, PLEOS_CAAS_PACKAGE)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize TTS with $PLEOS_CAAS_PACKAGE, using default", e)
            fallbackTts = AndroidTts(context, initListener)
        }
    }

    /**
     * @brief 텍스트를 음성으로 변환하여 차량 오디오 시스템으로 출력합니다.
     * @param text 음성으로 출력할 텍스트 문구
     * @param isFlush true일 경우 기존 음성을 중단하고 즉시 새 문구를 출력하며, false일 경우 대기열에 추가합니다.
     */
    fun speak(text: String, isFlush: Boolean = true) {
        if (text.isBlank()) return
        Log.i(TAG, "speak() requested: \"$text\" (flush=$isFlush, pleosReady=$isPleosTtsReady)")

        mainHandler.post {
            onSpeechStateChangeListener?.invoke(true, text)
        }

        if (isPleosTtsReady && pleosTts != null) {
            try {
                pleosTts?.speak(text, isFlush)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking PleosTts.speak, falling back to local TTS", e)
            }
        }

        // Pleos TTS 사용 불가 시 Android 기본 TTS로 폴백
        if (!isFallbackReady) {
            initFallbackTts()
        }

        if (isFallbackReady && fallbackTts != null) {
            val queueMode = if (isFlush) AndroidTts.QUEUE_FLUSH else AndroidTts.QUEUE_ADD
            val utteranceId = "$UTTERANCE_ID_PREFIX${System.currentTimeMillis()}"
            fallbackTts?.speak(text, queueMode, null, utteranceId)
        } else {
            Log.w(TAG, "Both Pleos TTS and Fallback TTS are not ready yet.")
        }
    }

    /**
     * @brief 현재 출력 중인 음성을 즉시 중단합니다.
     */
    fun stop() {
        Log.d(TAG, "stop() called")
        try {
            if (isPleosTtsReady) {
                pleosTts?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping PleosTts", e)
        }

        try {
            if (isFallbackReady) {
                fallbackTts?.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping FallbackTts", e)
        }

        isSpeaking = false
        mainHandler.post {
            onSpeechStateChangeListener?.invoke(false, null)
        }
    }

    /**
     * @brief 액티비티 종료 시 오디오 및 TTS 서비스 바인딩 자원을 해제합니다.
     */
    fun release() {
        Log.i(TAG, "Releasing TTS resources...")
        stop()
        try {
            pleosTts?.release()
            pleosTts = null
            isPleosTtsReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing PleosTts", e)
        }

        try {
            fallbackTts?.shutdown()
            fallbackTts = null
            isFallbackReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down FallbackTts", e)
        }
    }
}
