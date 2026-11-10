/**
 * @file MainActivity.kt
 * @brief Pleos CommuteCare 대화면 IVI 인포테인먼트 메인 화면 및 에이전트 오케스트레이션.
 *
 * 전체 라이프사이클 및 동작 순서:
 *  1. Pleos Vehicle SDK (공조/시트/조명) 및 AAOS Car Service 연결 초기화
 *  2. Pleos TextToSpeech SDK 및 소음 대비 인터랙티브 터치 패널 초기화
 *  3. 기상청 초단기실황 API 호출 및 위경도->격자 변환을 통한 실시간 기상 데이터 취득
 *  4. 출퇴근 패턴 학습 엔진(CommutePatternEngine)을 통한 개인화 목표 온도 및 자동 인사 상태 확인
 *  5. 소음 환경 대응: "출근 중이십니까?" 음성 안내 + 대화면 원터치 퀵 리플라이 카드 노출
 *  6. OpenAI GPT-4o-mini 기반 하이브리드 개인 비서 음성 브리핑 생성 및 TTS 실시간 낭독
 *  7. 심리음향학 기반 YouTube Music 트랙 매칭 및 디 올 뉴 그랜저(GN7) 무드램프 색상 동기화
 */

package ai.pleos.commutecare.presentation

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.pleos.commutecare.data.weather.KmaWeatherClient
import ai.pleos.commutecare.data.weather.RealtimeWeather
import ai.pleos.commutecare.data.weather.WeatherConditionTag
import ai.pleos.commutecare.domain.CommuteSchedule
import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.ai.AiBriefingEngine
import ai.pleos.commutecare.domain.audio.AcousticPreset
import ai.pleos.commutecare.domain.audio.PsychoacousticEngine
import ai.pleos.commutecare.domain.calendar.CalendarMailManager
import ai.pleos.commutecare.domain.commute.CommutePrompt
import ai.pleos.commutecare.domain.commute.SmartCommuteDetector
import ai.pleos.commutecare.domain.learning.CommutePatternEngine
import ai.pleos.commutecare.domain.lighting.AmbientLightingEngine
import ai.pleos.commutecare.domain.lighting.AmbientLightingPreset
import ai.pleos.commutecare.domain.music.VehicleMusicManager
import ai.pleos.commutecare.domain.news.AdaptiveNewsEngine
import ai.pleos.commutecare.domain.news.NewsItem
import ai.pleos.commutecare.domain.news.UserAgeGroup
import ai.pleos.commutecare.domain.news.UserGender
import ai.pleos.commutecare.domain.travel.TravelModeEngine
import ai.pleos.commutecare.domain.voice.PleosVoiceManager
import ai.pleos.commutecare.vehicle.CommuteVehicleManager
import kotlinx.coroutines.launch
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var vehicleManager: CommuteVehicleManager
    private lateinit var voiceManager: PleosVoiceManager

    private val commuteDetector = SmartCommuteDetector()
    private val newsEngine = AdaptiveNewsEngine(UserAgeGroup.THIRTIES, UserGender.MALE)
    private val calendarMailManager = CalendarMailManager()
    private val patternEngine = CommutePatternEngine()
    private val travelEngine = TravelModeEngine()
    private val musicManager = VehicleMusicManager()

    private var currentCommuteMode = CommuteType.MORNING
    private var currentWeather: RealtimeWeather? = null
    private var currentAcousticPreset: AcousticPreset? = null
    private var currentLightingPreset: AmbientLightingPreset? = null
    private var currentNewsItem: NewsItem? = null
    private var isTravelModeActive: Boolean = false
    private var latestBriefingText: String = ""

    // UI Components - Header & Voice Panel (소음 대처 인터랙티브 터치 패널)
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvVoiceStatus: TextView
    private lateinit var btnVoiceStop: Button
    private lateinit var btnVoiceReplay: Button

    // Quick Reply Action Card (소음으로 인한 음성 인식 실패 대비 패널 터치 답변 UI)
    private lateinit var layoutQuickReplyCard: LinearLayout
    private lateinit var tvQuickReplyQuestion: TextView
    private lateinit var btnQuickReplyYes: Button
    private lateinit var btnQuickReplyNo: Button
    private lateinit var btnQuickReplyAlt: Button

    // Core Content Views
    private lateinit var tvWeatherDetails: TextView
    private lateinit var tvCommutePrompt: TextView
    private lateinit var btnConfirmCommute: Button
    private lateinit var tvAiBriefing: TextView
    private lateinit var btnRefreshAiBriefing: Button
    private lateinit var tvTravelBanner: TextView
    private lateinit var btnTriggerTravelMode: Button
    private lateinit var tvTargetTempDisplay: TextView
    private lateinit var btnTempUp: Button
    private lateinit var btnTempDown: Button
    private lateinit var tvLightingInfo: TextView
    private lateinit var tvLightingBasis: TextView
    private lateinit var tvAudioInfo: TextView
    private lateinit var tvAcousticBasis: TextView
    private lateinit var tvNewsTitle: TextView
    private lateinit var tvNewsSummary: TextView
    private lateinit var btnNewsAccept: Button
    private lateinit var btnNewsDecline: Button
    private lateinit var tvLogConsole: TextView

    // Mock Location (Seoul Yangjae)
    private var currentLat = 37.4640
    private var currentLon = 127.0434
    private var currentTargetTemp = 22.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        initVoiceManager()
        patternEngine.seedDemonstrationHistory()
        initVehicleManager()
        loadRealtimeWeather()
        checkSmartCommuteIntent()
    }

    /**
     * @brief Pleos Voice Manager (TTS) 초기화 및 UI 발화 상태 리스너를 등록합니다.
     * @return Unit
     */
    private fun initVoiceManager() {
        voiceManager = PleosVoiceManager(this)
        voiceManager.onSpeechStateChangeListener = { isSpeaking, speakingText ->
            if (isSpeaking) {
                val preview = if (speakingText != null) " - \"${speakingText.take(24)}...\"" else ""
                tvVoiceStatus.text = "🔊 [음성 안내 중] Pleos TTS 출력$preview"
                btnVoiceStop.visibility = View.VISIBLE
            } else {
                tvVoiceStatus.text = "🔈 [음성 대기] 음성 또는 패널 터치로 응답 가능합니다."
                btnVoiceStop.visibility = View.GONE
            }
        }
    }

    /**
     * @brief Pleos Vehicle SDK 및 차속/주행제한 리스너를 초기화합니다.
     * @return Unit
     */
    private fun initVehicleManager() {
        vehicleManager = CommuteVehicleManager(
            context = this,
            onLog = { msg -> appendLog(msg) },
            onSpeedChanged = { speedKmh ->
                tvSpeed.text = "현재 차속: %.1f km/h".format(speedKmh)
                if (speedKmh > 5.0f) {
                    vehicleManager.cancelSafetyAutoShutdown()
                }
            },
            onRestrictionChanged = { _ -> }
        )
        vehicleManager.connect()
    }

    /**
     * @brief 기상청 초단기실황 API를 호출하여 실시간 날씨를 갱신합니다.
     * @return Unit
     */
    private fun loadRealtimeWeather() {
        appendLog("기상청 단기예보 실황 API 호출 중...")
        lifecycleScope.launch {
            val weather = KmaWeatherClient.fetchCurrentWeather(currentLat, currentLon)
            currentWeather = weather
            if (weather != null) {
                tvWeatherDetails.text = "⛅ [기상청 초단기실황]\n${weather.description}"
                appendLog("기상청 날씨 수신 성공: ${weather.temperatureCelsius}℃, PTY=${weather.precipitationType}")
            } else {
                tvWeatherDetails.text = "⛅ [기상청 API 수신 대기] 14.5℃, 맑음 (로컬 캐시)"
                appendLog("기상청 API 응답 대기/캐시 전환")
            }
            updateAcousticEnvironment()
            updateLightingEnvironment()
            updateNewsRecommendation()
            requestAiBriefing()
        }
    }

    /**
     * @brief 시계열 학습 및 GPS 위치 기반으로 출퇴근 또는 자동 인사를 판별하고 음성 및 터치 패널에 제안합니다.
     * @return Unit
     */
    private fun checkSmartCommuteIntent() {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val hourMinute = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
        val pattern = patternEngine.predictPattern(dayOfWeek, hourMinute, currentCommuteMode == CommuteType.MORNING)

        currentTargetTemp = pattern.personalizedTargetTemp
        tvTargetTempDisplay.text = "목표 공조: ${currentTargetTemp}℃ (학습된 최적 온도)"

        if (pattern.isAutoGreetingUnlocked) {
            val greeting = if (currentCommuteMode == CommuteType.MORNING)
                "오늘도 힘찬 하루 보내세요! 출발 준비가 완료되었습니다."
            else
                "오늘 하루도 정말 수고 많으셨습니다. 안전하게 귀가하세요."
            tvCommutePrompt.text = "🌟 패턴 학습 완료: $greeting"
            tvCommutePrompt.visibility = View.VISIBLE
            btnConfirmCommute.visibility = View.GONE
            tvStatusBadge.text = "스마트 자동 케어 활성"

            // 소음 대비 패널 터치 완료 표시
            layoutQuickReplyCard.visibility = View.GONE

            // TTS 음성 인사
            voiceManager.speak("반갑습니다. $greeting")
        } else {
            val prompt = commuteDetector.evaluateCommuteIntent(currentLat, currentLon)
            if (prompt != null) {
                tvCommutePrompt.text = "💡 스마트 주행 감지: ${prompt.message}"
                tvCommutePrompt.visibility = View.VISIBLE
                btnConfirmCommute.visibility = View.VISIBLE

                // 소음 환경 대비 대화면 퀵 리플라이 패널 활성화
                showQuickReplyPanel(
                    question = "📢 \"${prompt.message}\"",
                    yesLabel = "네, 맞춤 출근 시작",
                    noLabel = "아닙니다 (일반 주행)",
                    altLabel = "목적지 변경",
                    onYes = { executeCommuteRoutine(prompt) },
                    onNo = { dismissQuickReply() },
                    onAlt = { toggleTravelMode() }
                )

                // 음성으로 운전자에게 질문
                val spokenQuestion = if (prompt.commuteType == CommuteType.MORNING) {
                    "현대자동차 양재 본사로 출근 중이십니까? 화면 터치나 음성으로 알려주세요."
                } else {
                    "자택으로 퇴근 중이십니까? 맞춤 프리컨디셔닝을 시작할까요?"
                }
                voiceManager.speak(spokenQuestion)

                btnConfirmCommute.setOnClickListener {
                    executeCommuteRoutine(prompt)
                }
            } else {
                tvCommutePrompt.visibility = View.GONE
                btnConfirmCommute.visibility = View.GONE
                layoutQuickReplyCard.visibility = View.GONE
            }
        }
    }

    /**
     * @brief 출퇴근 맞춤 프리컨디셔닝 및 루틴을 실행합니다.
     * @param prompt 감지된 출퇴근 정보
     */
    private fun executeCommuteRoutine(prompt: CommutePrompt) {
        currentCommuteMode = prompt.commuteType
        tvStatusBadge.text = if (prompt.commuteType == CommuteType.MORNING) "출근 집중 모드 가동 중" else "퇴근 릴랙스 모드 가동 중"
        tvCommutePrompt.text = "✅ ${prompt.destination.name} 경로 설정 및 맞춤 케어 루틴이 활성화되었습니다."
        btnConfirmCommute.visibility = View.GONE
        layoutQuickReplyCard.visibility = View.GONE

        // 원격 프리컨디셔닝 및 시동 제어
        vehicleManager.startPreconditioning(currentTargetTemp, 2) { success ->
            runOnUiThread {
                appendLog("출퇴근 케어 공조/시동 자동 가동: ${currentTargetTemp}℃ (결과: $success)")
            }
        }

        updateAcousticEnvironment()
        updateLightingEnvironment()
        updateNewsRecommendation()
        requestAiBriefing()
        Toast.makeText(this, "${prompt.destination.name} 맞춤 루틴 활성화", Toast.LENGTH_SHORT).show()
    }

    /**
     * @brief OpenAI GPT-4o-mini 기반 하이브리드 음성 브리핑을 요청하고 TTS로 자동 낭독합니다.
     * @return Unit
     */
    private fun requestAiBriefing() {
        tvAiBriefing.text = "🤖 탑승자 맞춤 AI 브리핑 생성 중 (날씨, 캘린더, 메일 분석 중)..."
        btnVoiceReplay.visibility = View.GONE

        lifecycleScope.launch {
            val events = calendarMailManager.fetchTodayEvents()
            val emails = calendarMailManager.fetchUnreadImportantEmails()
            val briefing = AiBriefingEngine.generateBriefing(
                currentCommuteMode,
                currentWeather,
                events,
                emails
            )
            latestBriefingText = briefing
            tvAiBriefing.text = "🎙️ [Pleos AI 맞춤 음성 브리핑]\n$briefing"
            btnVoiceReplay.visibility = View.VISIBLE
            appendLog("AI 브리핑 생성 완료 (GPT-4o-mini)")

            // Pleos Voice Manager를 통한 자동 음성 낭독 (운전자 시선 분산 방지)
            voiceManager.speak(briefing)
        }
    }

    /**
     * @brief 심리음향학 연구 근거 및 YouTube Music 기반 음악 큐레이션 적용
     * @return Unit
     */
    private fun updateAcousticEnvironment() {
        if (isTravelModeActive) {
            val rec = travelEngine.buildTravelRecommendation("속초 설악 리조트")
            currentAcousticPreset = rec.travelAudioPreset
        } else {
            val condition = currentWeather?.conditionTag ?: WeatherConditionTag.CLEAR
            currentAcousticPreset = PsychoacousticEngine.determineAudioPreset(currentCommuteMode, condition)
        }

        val preset = currentAcousticPreset!!
        val bassSign = if (preset.bassGainDb >= 0) "+" else ""
        val trebleSign = if (preset.trebleGainDb >= 0) "+" else ""

        val track = musicManager.getPresetTrack(if (isTravelModeActive) "TRAVEL" else if (currentCommuteMode == CommuteType.MORNING) "MORNING" else "EVENING")

        tvAudioInfo.text = "🎵 [YouTube Music 연동]: ${track.title} - ${track.artist} (${track.contextTag})\n프리셋: ${preset.presetName}\n[EQ: Bass ${bassSign}%.1fdB, Mid +%.1fdB, Treble ${trebleSign}%.1fdB]".format(
            preset.bassGainDb,
            preset.midGainDb,
            preset.trebleGainDb
        )
        tvAcousticBasis.text = "🔬 음향학적 세팅 근거 (차량 환경 연구):\n${preset.scientificBasis}"
    }

    /**
     * @brief 그랜저(GN7) 앰비언트 라이트 색상 동기화
     * @return Unit
     */
    private fun updateLightingEnvironment() {
        val condition = currentWeather?.conditionTag ?: WeatherConditionTag.CLEAR
        val preset = AmbientLightingEngine.determineLightingPreset(currentCommuteMode, condition)
        currentLightingPreset = preset

        tvLightingInfo.text = "💡 무드램프 색상: ${preset.colorNameKo} (${preset.hexString})\n프리셋: ${preset.presetName}"
        tvLightingBasis.text = "🔬 조명 공학 & 색채심리 근거:\n${preset.scientificBasis}\n[출처 논문: ${preset.referencePaper}]"

        vehicleManager.setAmbientMoodLight(preset.colorHex) { success ->
            if (success) appendLog("GN7 무드램프 색상 자동 동기화 완료: ${preset.colorNameKo}")
        }
    }

    /**
     * @brief 뉴스 추천 및 피드백 학습 (소음 대비 터치 퀵 리플라이 연동)
     * @return Unit
     */
    private fun updateNewsRecommendation() {
        val topNews = newsEngine.recommendTopNews()
        currentNewsItem = topNews

        tvNewsTitle.text = "📰 [${topNews.category}] ${topNews.title}\n(추천 이유: 30대 남성 관심 키워드 '${topNews.relevanceTag}' 및 선호 점수)"
        tvNewsSummary.text = "이 뉴스를 음성 요약 브리핑으로 들으시겠습니까?"
        btnNewsAccept.visibility = View.VISIBLE
        btnNewsDecline.visibility = View.VISIBLE

        btnNewsAccept.setOnClickListener {
            onAcceptNewsSummary(topNews)
        }

        btnNewsDecline.setOnClickListener {
            onDeclineNewsSummary(topNews)
        }
    }

    /**
     * @brief 뉴스 요약 수락 시 음성 낭독 및 선호도 가중치 학습
     * @param newsItem 요약 대상 뉴스 아이템
     */
    private fun onAcceptNewsSummary(newsItem: NewsItem) {
        newsEngine.recordUserFeedback(newsItem, true)
        tvNewsSummary.text = "🎙️ [요약 브리핑]:\n${newsItem.summary}\n\n(✨ 선호도 학습 완료: '${newsItem.category}' 가중치 상승)"
        btnNewsAccept.visibility = View.GONE
        btnNewsDecline.visibility = View.GONE
        Toast.makeText(this, "선호 뉴스 카테고리로 학습되었습니다.", Toast.LENGTH_SHORT).show()
        appendLog("사용자 관심사 학습: [${newsItem.category}] 선호도 +3")

        // 요약 내용을 TTS 음성으로 낭독
        voiceManager.speak("선택하신 ${newsItem.category} 뉴스 요약입니다. ${newsItem.summary}")
    }

    /**
     * @brief 뉴스 요약 거절 시 피드백 학습
     * @param newsItem 거절 대상 뉴스 아이템
     */
    private fun onDeclineNewsSummary(newsItem: NewsItem) {
        newsEngine.recordUserFeedback(newsItem, false)
        tvNewsSummary.text = "이해했습니다. 다른 카테고리 뉴스를 우선 추천해 드릴게요."
        btnNewsAccept.visibility = View.GONE
        btnNewsDecline.visibility = View.GONE
        appendLog("사용자 관심사 조정: [${newsItem.category}] 비선호 반영")
        voiceManager.speak("알겠습니다. 다른 관심 뉴스를 준비하겠습니다.")
    }

    /**
     * @brief 소음 환경 대비 대화면 퀵 리플라이 패널을 표시합니다.
     * @param question 질문 텍스트
     * @param yesLabel 긍정 버튼 문구
     * @param noLabel 부정 버튼 문구
     * @param altLabel 대안/전환 버튼 문구
     */
    private fun showQuickReplyPanel(
        question: String,
        yesLabel: String,
        noLabel: String,
        altLabel: String? = null,
        onYes: () -> Unit,
        onNo: () -> Unit,
        onAlt: (() -> Unit)? = null
    ) {
        tvQuickReplyQuestion.text = question
        btnQuickReplyYes.text = yesLabel
        btnQuickReplyYes.setOnClickListener { onYes() }

        btnQuickReplyNo.text = noLabel
        btnQuickReplyNo.setOnClickListener { onNo() }

        if (altLabel != null && onAlt != null) {
            btnQuickReplyAlt.text = altLabel
            btnQuickReplyAlt.visibility = View.VISIBLE
            btnQuickReplyAlt.setOnClickListener { onAlt() }
        } else {
            btnQuickReplyAlt.visibility = View.GONE
        }

        layoutQuickReplyCard.visibility = View.VISIBLE
    }

    /**
     * @brief 퀵 리플라이 패널을 닫습니다.
     */
    private fun dismissQuickReply() {
        layoutQuickReplyCard.visibility = View.GONE
        voiceManager.speak("일반 주행 모드를 유지합니다.")
    }

    /**
     * @brief 사용자의 수동 공조 온도 조절을 학습 엔진에 피드백함.
     * @param delta 온도 변화량 (+0.5 또는 -0.5)
     */
    private fun adjustTargetTemperature(delta: Float) {
        currentTargetTemp += delta
        currentTargetTemp = (currentTargetTemp * 10).toInt() / 10.0f
        tvTargetTempDisplay.text = "목표 공조: ${currentTargetTemp}℃ (실시간 수동 조절)"

        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val hourMinute = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)

        patternEngine.recordTripEvent(dayOfWeek, hourMinute, currentTargetTemp, currentCommuteMode == CommuteType.MORNING)
        appendLog("온도 취향 피드백 학습 반영: ${currentTargetTemp}℃")
        Toast.makeText(this, "사용자 선호 온도로 재학습되었습니다 (${currentTargetTemp}℃)", Toast.LENGTH_SHORT).show()
    }

    /**
     * @brief 주말/외곽 리조트 목적지 입력 시 여행 모드를 활성화함.
     */
    private fun toggleTravelMode() {
        isTravelModeActive = !isTravelModeActive
        if (isTravelModeActive) {
            val rec = travelEngine.buildTravelRecommendation("속초 설악 한화리조트")
            tvTravelBanner.text = "🏖️ [여행 모드 활성화 - ${rec.destinationName}]\n${rec.travelBriefingMessage}\n\n📍 주변 꼭 가봐야 할 명소 & 맛집:\n" +
                    rec.topPois.joinToString("\n") { "• [${it.category}] ${it.name}: ${it.description}" }
            tvTravelBanner.visibility = View.VISIBLE
            btnTriggerTravelMode.text = "출퇴근 일상 모드로 복귀"
            tvStatusBadge.text = "주말 힐링 드라이브 모드"

            // 여행 모드 음성 안내
            voiceManager.speak("속초 설악 리조트로 여행을 떠나시나요? 힐링 음악과 목적지 주변 꼭 봐야 할 명소를 준비했습니다.")
        } else {
            tvTravelBanner.visibility = View.GONE
            btnTriggerTravelMode.text = "모의 목적지: 주말 콘도/리조트 여행 모드 시연"
            tvStatusBadge.text = "출퇴근 케어 에이전트 대기 중"
            voiceManager.speak("출퇴근 케어 일상 모드로 복귀합니다.")
        }
        updateAcousticEnvironment()
    }

    private fun appendLog(msg: String) {
        val current = tvLogConsole.text.toString()
        tvLogConsole.text = "• $msg\n$current"
    }

    /**
     * @brief 대화면 IVI에 최적화된 동적 레이아웃을 생성합니다.
     * @return 렌더링할 루트 뷰
     */
    private fun buildContentView(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(0xFF10131A.toInt())
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        val title = TextView(this).apply {
            text = "Pleos CommuteCare"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            paint.isFakeBoldText = true
        }
        layout.addView(title)

        tvStatusBadge = TextView(this).apply {
            text = "출퇴근 케어 에이전트 대기 중"
            textSize = 14f
            setTextColor(0xFF00E5FF.toInt())
            setPadding(0, 8, 0, 4)
        }
        layout.addView(tvStatusBadge)

        tvSpeed = TextView(this).apply {
            text = "현재 차속: 0.0 km/h"
            setTextColor(0xFFBBDEFB.toInt())
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSpeed)

        // ========================================================
        // [🔊 음성 에이전트 & 터치 퀵 리플라이 패널 (소음 안전 대책)]
        // ========================================================
        layout.addView(createSectionHeader("🔊 Pleos 음성 비서 & 패널 터치 퀵 리플라이 (소음 안심 케어)"))

        val voiceStatusLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4, 0, 8)
        }
        tvVoiceStatus = TextView(this).apply {
            text = "🔈 [음성 대기] Pleos TTS 엔진 준비 완료"
            setTextColor(0xFF81D4FA.toInt())
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        btnVoiceStop = Button(this).apply {
            text = "⏹ 음성 멈춤"
            setBackgroundColor(0xFFB71C1C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
            setOnClickListener { voiceManager.stop() }
        }
        btnVoiceReplay = Button(this).apply {
            text = "▶️ 브리핑 다시 듣기"
            setBackgroundColor(0xFF00695C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            visibility = View.GONE
            setOnClickListener {
                if (latestBriefingText.isNotBlank()) {
                    voiceManager.speak(latestBriefingText)
                }
            }
        }
        voiceStatusLayout.addView(tvVoiceStatus)
        voiceStatusLayout.addView(btnVoiceStop)
        voiceStatusLayout.addView(btnVoiceReplay)
        layout.addView(voiceStatusLayout)

        // 소음 환경 대비 대화면 원터치 퀵 리플라이 카드
        layoutQuickReplyCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF1E2638.toInt())
            setPadding(24, 20, 24, 20)
            visibility = View.GONE
        }
        tvQuickReplyQuestion = TextView(this).apply {
            setTextColor(0xFFFFEB3B.toInt())
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 12)
        }
        val quickReplyButtonsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnQuickReplyYes = Button(this).apply {
            setBackgroundColor(0xFF1976D2.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = 8
            }
        }
        btnQuickReplyNo = Button(this).apply {
            setBackgroundColor(0xFF455A64.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f).apply {
                marginEnd = 8
            }
        }
        btnQuickReplyAlt = Button(this).apply {
            setBackgroundColor(0xFFE65100.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        quickReplyButtonsLayout.addView(btnQuickReplyYes)
        quickReplyButtonsLayout.addView(btnQuickReplyNo)
        quickReplyButtonsLayout.addView(btnQuickReplyAlt)

        layoutQuickReplyCard.addView(tvQuickReplyQuestion)
        layoutQuickReplyCard.addView(quickReplyButtonsLayout)
        layout.addView(layoutQuickReplyCard)

        // 스마트 출퇴근 감지 및 능동 인사 배너
        tvCommutePrompt = TextView(this).apply {
            setTextColor(0xFFFFD54F.toInt())
            textSize = 15f
            paint.isFakeBoldText = true
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvCommutePrompt)

        btnConfirmCommute = Button(this).apply {
            text = "네, 맞춤 출근 루틴 시작"
            setBackgroundColor(0xFF0D47A1.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        layout.addView(btnConfirmCommute)

        // 주말/외곽 콘도 여행 모드 배너
        layout.addView(createSectionHeader("🏖️ 여행 모드 (주말 콘도/리조트 감지)"))
        btnTriggerTravelMode = Button(this).apply {
            text = "모의 목적지: 주말 콘도/리조트 여행 모드 시연"
            setBackgroundColor(0xFFE65100.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { toggleTravelMode() }
        }
        layout.addView(btnTriggerTravelMode)

        tvTravelBanner = TextView(this).apply {
            setTextColor(0xFFFFE082.toInt())
            textSize = 13f
            visibility = View.GONE
            setPadding(0, 8, 0, 16)
        }
        layout.addView(tvTravelBanner)

        // 1. 기상청 날씨 섹션
        layout.addView(createSectionHeader("🌤️ 기상청 실시간 기상 관측 (단기예보)"))
        tvWeatherDetails = TextView(this).apply {
            text = "기상청 날씨 수신 중..."
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        layout.addView(tvWeatherDetails)

        // 2. 차량 공조 & 시트 프리컨디셔닝
        layout.addView(createSectionHeader("🌡️ 스마트 프리컨디셔닝 & 온도 취향 학습"))
        tvTargetTempDisplay = TextView(this).apply {
            text = "목표 공조: 22.0℃ (시계열 학습 대기)"
            setTextColor(0xFFECEFF1.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvTargetTempDisplay)

        val tempControlLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnTempDown = Button(this).apply {
            text = "온도 내리기 (-0.5℃)"
            setBackgroundColor(0xFF37474F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { adjustTargetTemperature(-0.5f) }
        }
        btnTempUp = Button(this).apply {
            text = "온도 올리기 (+0.5℃)"
            setBackgroundColor(0xFF37474F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { adjustTargetTemperature(+0.5f) }
        }
        tempControlLayout.addView(btnTempDown)
        tempControlLayout.addView(btnTempUp)
        layout.addView(tempControlLayout)

        val btnPrecondition = Button(this).apply {
            text = "자동 원격 시동 및 프리컨디셔닝 실행 (10분 미탑승 자동OFF)"
            setBackgroundColor(0xFF1E88E5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                vehicleManager.startPreconditioning(currentTargetTemp, 2) { success ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, if (success) "원격 시동 및 공조 가동 완료 (10분 미탑승 시 자동 시동 OFF)" else "차량 SDK 응답 확인 필요", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        layout.addView(btnPrecondition)

        // 3. AI 맞춤 브리핑 (OpenAI GPT-4o-mini)
        layout.addView(createSectionHeader("🎙️ Pleos 하이브리드 AI 브리핑 (날씨·일정·메일)"))
        tvAiBriefing = TextView(this).apply {
            text = "AI 브리핑 대기 중..."
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvAiBriefing)

        btnRefreshAiBriefing = Button(this).apply {
            text = "AI 브리핑 다시 생성 (GPT-4o-mini)"
            setBackgroundColor(0xFF37474F.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { requestAiBriefing() }
        }
        layout.addView(btnRefreshAiBriefing)

        // 4. 음향학적 계절/날씨 맞춤 사운드 & EQ
        layout.addView(createSectionHeader("🎧 날씨·계절·마스킹 기반 오디오 & EQ"))
        tvAudioInfo = TextView(this).apply {
            setTextColor(0xFF80D8FF.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(tvAudioInfo)

        tvAcousticBasis = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            setPadding(0, 4, 0, 16)
        }
        layout.addView(tvAcousticBasis)

        // 5. 그랜저 GN7 앰비언트 무드램프 제어 및 논문 근거
        layout.addView(createSectionHeader("💡 그랜저(GN7) 앰비언트 라이트 (조명공학 & 심리 연구)"))
        tvLightingInfo = TextView(this).apply {
            setTextColor(0xFFFFD54F.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(tvLightingInfo)

        tvLightingBasis = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 12f
            setPadding(0, 4, 0, 16)
        }
        layout.addView(tvLightingBasis)

        // 6. 나이/성별 맞춤 뉴스 및 선호도 학습
        layout.addView(createSectionHeader("📰 맞춤형 뉴스 브리핑 & 취향 학습"))
        tvNewsTitle = TextView(this).apply {
            setTextColor(0xFFECEFF1.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(tvNewsTitle)

        tvNewsSummary = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
            setPadding(0, 4, 0, 8)
        }
        layout.addView(tvNewsSummary)

        val newsBtnLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnNewsAccept = Button(this).apply {
            text = "네, 요약해주세요"
            setBackgroundColor(0xFF2E7D32.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        btnNewsDecline = Button(this).apply {
            text = "괜찮습니다"
            setBackgroundColor(0xFF424242.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }
        newsBtnLayout.addView(btnNewsAccept)
        newsBtnLayout.addView(btnNewsDecline)
        layout.addView(newsBtnLayout)

        // 7. 진단 콘솔
        layout.addView(createSectionHeader("💻 이벤트 & 알고리즘 진단 로그"))
        tvLogConsole = TextView(this).apply {
            text = "• Pleos CommuteCare 시작됨"
            setTextColor(0xFF78909C.toInt())
            textSize = 12f
            setPadding(0, 8, 0, 32)
        }
        layout.addView(tvLogConsole)

        root.addView(layout)
        return root
    }

    private fun createSectionHeader(title: String): TextView {
        return TextView(this).apply {
            text = title
            textSize = 15f
            paint.isFakeBoldText = true
            setTextColor(0xFF00E5FF.toInt())
            setPadding(0, 24, 0, 4)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.release()
        vehicleManager.release()
    }
}
