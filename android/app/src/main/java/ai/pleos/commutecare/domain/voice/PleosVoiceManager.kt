/**
 * @file PleosVoiceManager.kt
 * @brief Pleos Playground TextToSpeech(TTS) 및 SpeechToText(STT) 3.3.1 SDK 기반 음성 양방향 대화 및 하이브리드 폴백 매니저
 *
 * [주요 동작 흐름]
 * 1. 현대자동차 Pleos Connect 공식 TTS SDK(3.3.1) 및 STT SDK(3.3.1) 초기화
 * 2. CaaS On-Device 음성 합성/인식 엔진 바인딩 시도 및 상태 리스너 등록
 * 3. 온디바이스 엔진 미지원 또는 장애 시 Android 표준 TTS 및 SpeechRecognizer로 자동 하이브리드 폴백
 * 4. 음성 브리핑/안내 발화 및 운전자 음성 명령("출근 준비", "뉴스 요약", "온도 조절", "여행 모드") 실시간 인식
 */

package ai.pleos.commutecare.domain.voice

import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttErrorType
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as OnSttInitListener
import ai.pleos.playground.stt.listener.ResultListener as SttResultListener
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsErrorType
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as OnTtsInitListener
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

    // ----------------------------------------------------
    // TTS (Text-to-Speech)
    // ----------------------------------------------------
    private var pleosTts: PleosTts? = null
    private var isPleosTtsReady: Boolean = false
    private var fallbackTts: AndroidTts? = null
    private var isFallbackTtsReady: Boolean = false

    var isSpeaking: Boolean = false
        private set

    // ----------------------------------------------------
    // STT (Speech-to-Text)
    // ----------------------------------------------------
    private var pleosStt: PleosStt? = null
    private var isPleosSttReady: Boolean = false
    private var fallbackSpeechRecognizer: SpeechRecognizer? = null
    private var isFallbackSttReady: Boolean = false

    var isListening: Boolean = false
        private set

    // ----------------------------------------------------
    // 콜백 리스너
    // ----------------------------------------------------
    var onSpeechStateChangeListener: ((isSpeaking: Boolean, text: String?) -> Unit)? = null
    var onListeningStateChangeListener: ((isListening: Boolean, partialText: String?) -> Unit)? = null
    var onVoiceCommandRecognizedListener: ((commandText: String) -> Unit)? = null

    init {
        initializeTtsEngine()
        initializeSttEngine()
    }

    // ====================================================
    // TTS 구현부
    // ====================================================

    /**
     * @brief Pleos TTS 엔진 및 로컬 백업 폴백 엔진을 초기화합니다.
     */
    private fun initializeTtsEngine() {
        Log.i(TAG, "Initializing Pleos TextToSpeech SDK (Mode.ON_DEVICE)...")
        try {
            val tts = PleosTts(context, TtsMode.ON_DEVICE)
            tts.initialize(object : OnTtsInitListener {
                override fun onSuccess() {
                    Log.i(TAG, "[PleosTTS] Pleos TextToSpeech SDK successfully initialized.")
                    try {
                        tts.addEventListener(object : TtsEventListener {
                            override fun onStart() {
                                mainHandler.post {
                                    isSpeaking = true
                                    onSpeechStateChangeListener?.invoke(true, null)
                                }
                            }

                            override fun onReady() {}

                            override fun onDone() {
                                mainHandler.post {
                                    isSpeaking = false
                                    onSpeechStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onError(errorType: TtsErrorType) {
                                Log.w(TAG, "[PleosTTS] onError: $errorType")
                                mainHandler.post {
                                    isSpeaking = false
                                    onSpeechStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onStop() {
                                mainHandler.post {
                                    isSpeaking = false
                                    onSpeechStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onUpdatedRms(rms: Double) {}
                        })

                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts.setAudioAttributes(audioAttributes)
                        tts.setLocale(Locale.KOREAN)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to apply TTS attributes post-init", e)
                    }
                    isPleosTtsReady = true
                }

                override fun onFailure(errorType: TtsErrorType) {
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

    private fun initFallbackTts() {
        if (fallbackTts != null || isFallbackTtsReady) return
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
                isFallbackTtsReady = true
                Log.i(TAG, "Android Standard TTS Fallback successfully initialized.")
            } else {
                isFallbackTtsReady = false
            }
        }

        try {
            fallbackTts = AndroidTts(context, initListener, PLEOS_CAAS_PACKAGE)
        } catch (e: Exception) {
            fallbackTts = AndroidTts(context, initListener)
        }
    }

    /**
     * @brief 텍스트를 음성으로 변환하여 차량 오디오 시스템으로 출력합니다.
     * @param text 음성 출력할 문구
     * @param isFlush true일 경우 기존 음성을 중단하고 즉시 새 문구를 출력합니다.
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

        if (!isFallbackTtsReady) {
            initFallbackTts()
        }

        if (isFallbackTtsReady && fallbackTts != null) {
            val queueMode = if (isFlush) AndroidTts.QUEUE_FLUSH else AndroidTts.QUEUE_ADD
            val utteranceId = "$UTTERANCE_ID_PREFIX${System.currentTimeMillis()}"
            fallbackTts?.speak(text, queueMode, null, utteranceId)
        }
    }

    /**
     * @brief 현재 출력 중인 음성을 중단합니다.
     */
    fun stopSpeaking() {
        try {
            if (isPleosTtsReady) pleosTts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping PleosTts", e)
        }

        try {
            if (isFallbackTtsReady) fallbackTts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping FallbackTts", e)
        }

        isSpeaking = false
        mainHandler.post {
            onSpeechStateChangeListener?.invoke(false, null)
        }
    }

    // ====================================================
    // STT (음성 인식) 구현부
    // ====================================================

    /**
     * @brief Pleos SpeechToText SDK 및 로컬 폴백 음성 인식기를 초기화합니다.
     */
    private fun initializeSttEngine() {
        Log.i(TAG, "Initializing Pleos SpeechToText SDK (Mode.ON_DEVICE)...")
        try {
            val stt = PleosStt(context, SttMode.ON_DEVICE)
            stt.initialize(object : OnSttInitListener {
                override fun onSuccess() {
                    Log.i(TAG, "[PleosSTT] Pleos SpeechToText SDK successfully initialized.")
                    try {
                        stt.addListener(object : SttResultListener {
                            override fun onStartedRecognition() {
                                Log.d(TAG, "[PleosSTT] onStartedRecognition")
                                mainHandler.post {
                                    isListening = true
                                    onListeningStateChangeListener?.invoke(true, "마이크 청취 중...")
                                }
                            }

                            override fun onUpdated(text: String, isFinal: Boolean) {
                                Log.d(TAG, "[PleosSTT] onUpdated: text='$text', isFinal=$isFinal")
                                mainHandler.post {
                                    onListeningStateChangeListener?.invoke(true, text)
                                    if (isFinal && text.isNotBlank()) {
                                        isListening = false
                                        onListeningStateChangeListener?.invoke(false, text)
                                        onVoiceCommandRecognizedListener?.invoke(text)
                                    }
                                }
                            }

                            override fun onEndedRecognition() {
                                Log.d(TAG, "[PleosSTT] onEndedRecognition")
                                mainHandler.post {
                                    isListening = false
                                    onListeningStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onError(errorType: SttErrorType) {
                                Log.w(TAG, "[PleosSTT] onError: $errorType")
                                mainHandler.post {
                                    isListening = false
                                    onListeningStateChangeListener?.invoke(false, null)
                                }
                            }

                            override fun onReady() {
                                Log.d(TAG, "[PleosSTT] onReady")
                            }

                            override fun onUpdatedRms(rms: Double) {}

                            override fun onUpdatedEpdData(startMs: Long, endMs: Long) {}
                        })
                        stt.setLocale(Locale.KOREAN)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to setup STT listener post-init", e)
                    }
                    isPleosSttReady = true
                }

                override fun onFailure(errorType: SttErrorType) {
                    Log.w(TAG, "[PleosSTT] Initialization failed ($errorType). Activating fallback SpeechRecognizer.")
                    isPleosSttReady = false
                    initFallbackStt()
                }
            })
            pleosStt = stt
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate PleosStt, activating local fallback", e)
            initFallbackStt()
        }
    }

    private fun initFallbackStt() {
        mainHandler.post {
            if (fallbackSpeechRecognizer != null || isFallbackSttReady) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Log.w(TAG, "Android SpeechRecognizer is not available on this device.")
                return@post
            }

            try {
                fallbackSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            isListening = true
                            onListeningStateChangeListener?.invoke(true, "마이크 청취 중...")
                        }

                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {
                            isListening = false
                            onListeningStateChangeListener?.invoke(false, null)
                        }

                        override fun onError(error: Int) {
                            Log.w(TAG, "[FallbackSTT] onError: $error")
                            isListening = false
                            onListeningStateChangeListener?.invoke(false, null)
                        }

                        override fun onResults(results: Bundle?) {
                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            val recognized = matches?.firstOrNull() ?: ""
                            Log.i(TAG, "[FallbackSTT] onResults: \"$recognized\"")
                            isListening = false
                            onListeningStateChangeListener?.invoke(false, recognized)
                            if (recognized.isNotBlank()) {
                                onVoiceCommandRecognizedListener?.invoke(recognized)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            matches?.firstOrNull()?.let { partial ->
                                onListeningStateChangeListener?.invoke(true, partial)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
                isFallbackSttReady = true
                Log.i(TAG, "Android Fallback SpeechRecognizer successfully initialized.")
            } catch (e: Exception) {
                Log.w(TAG, "Error creating fallback SpeechRecognizer", e)
            }
        }
    }

    /**
     * @brief 운전자의 음성 명령 청취를 시작합니다.
     */
    fun startListening() {
        Log.i(TAG, "startListening() called (pleosSttReady=$isPleosSttReady)")

        // 음성 안내 발화 중이라면 먼저 중단하여 에코 캔슬링 효과 보장
        stopSpeaking()

        if (isPleosSttReady && pleosStt != null) {
            try {
                pleosStt?.request()
                isListening = true
                mainHandler.post {
                    onListeningStateChangeListener?.invoke(true, "말씀해 주세요...")
                }
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error invoking pleosStt.request()", e)
            }
        }

        // 로컬 SpeechRecognizer 폴백
        mainHandler.post {
            if (!isFallbackSttReady) initFallbackStt()
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toString())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                fallbackSpeechRecognizer?.startListening(intent)
                isListening = true
                onListeningStateChangeListener?.invoke(true, "말씀해 주세요 (Android STT)...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start listening on fallback STT", e)
            }
        }
    }

    /**
     * @brief 음성 청취를 중단합니다.
     */
    fun stopListening() {
        Log.d(TAG, "stopListening() called")
        try {
            if (isPleosSttReady) pleosStt?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Pleos STT", e)
        }

        try {
            fallbackSpeechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Fallback STT", e)
        }

        isListening = false
        mainHandler.post {
            onListeningStateChangeListener?.invoke(false, null)
        }
    }

    /**
     * @brief 사전 생성된 사용자 TTS 음성 파일(res/raw)을 차량 오디오로 재생하고 STT 인식 파이프라인에 전달합니다.
     * @param rawResId res/raw 내 사용자 음성 wav 리소스 ID
     * @param transcript 해당 음성의 텍스트 전사본
     * @return Unit
     */
    fun playPreRecordedUserVoice(rawResId: Int, transcript: String) {
        stopSpeaking()
        isListening = true
        mainHandler.post {
            onListeningStateChangeListener?.invoke(true, "민석님 음성 인식 중: \"$transcript\"")
        }
        try {
            val mp = android.media.MediaPlayer.create(context, rawResId)
            if (mp != null) {
                mp.setOnCompletionListener { player ->
                    player.release()
                    isListening = false
                    mainHandler.post {
                        onListeningStateChangeListener?.invoke(false, transcript)
                        onVoiceCommandRecognizedListener?.invoke(transcript)
                    }
                }
                mp.start()
            } else {
                isListening = false
                mainHandler.postDelayed({
                    onListeningStateChangeListener?.invoke(false, transcript)
                    onVoiceCommandRecognizedListener?.invoke(transcript)
                }, 1800L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "playPreRecordedUserVoice fallback: ${e.message}")
            isListening = false
            mainHandler.postDelayed({
                onListeningStateChangeListener?.invoke(false, transcript)
                onVoiceCommandRecognizedListener?.invoke(transcript)
            }, 1800L)
        }
    }

    /**
     * @brief 액티비티 종료 시 TTS 및 STT 자원을 안전하게 해제합니다.
     */
    fun release() {
        Log.i(TAG, "Releasing Pleos Voice resources (TTS + STT)...")
        stopSpeaking()
        stopListening()

        try {
            pleosTts?.release()
            pleosTts = null
            isPleosTtsReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Pleos TTS", e)
        }

        try {
            fallbackTts?.shutdown()
            fallbackTts = null
            isFallbackTtsReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Fallback TTS", e)
        }

        try {
            pleosStt?.release()
            pleosStt = null
            isPleosSttReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Pleos STT", e)
        }

        try {
            fallbackSpeechRecognizer?.destroy()
            fallbackSpeechRecognizer = null
            isFallbackSttReady = false
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying Fallback STT", e)
        }
    }
}
