package ai.pleos.commutecare.presentation

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import ai.pleos.commutecare.vehicle.CommuteVehicleManager
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * @file MainActivity.kt
 * @brief Pleos CommuteCare 대화면 IVI 인포테인먼트 메인 화면 및 에이전트 오케스트레이션.
 *
 * 전체 라이프사이클 및 동작 순서:
 *  1. Pleos Vehicle SDK (공조/시트/조명) 및 AAOS Car Service 연결 초기화
 *  2. 기상청 초단기실황 API 호출 및 위경도->격자 변환을 통한 실시간 기상 데이터 취득
 *  3. 출퇴근 패턴 학습 엔진(CommutePatternEngine)을 통한 개인화 목표 온도 및 자동 인사 상태 확인
 *  4. 목적지 검사 및 여행 모드(TravelModeEngine) 감지 ("여행을 떠나시나요?")
 *  5. OpenAI GPT-4o-mini 기반 하이브리드 개인 비서 음성 브리핑 생성
 *  6. 심리음향학 기반 YouTube Music 트랙 매칭 및 디 올 뉴 그랜저(GN7) 무드램프 색상 동기화
 */
class MainActivity : AppCompatActivity() {

    private lateinit var vehicleManager: CommuteVehicleManager
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

    // UI Components
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvSpeed: TextView
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

        patternEngine.seedDemonstrationHistory()
        initVehicleManager()
        loadRealtimeWeather()
        checkSmartCommuteIntent()
    }

    /**
     * @brief Pleos Vehicle SDK 및 차속/주행제한 리스너를 초기화함.
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
     * @brief 기상청 초단기실황 API를 호출하여 실시간 날씨를 갱신함.
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
     * @brief 시계열 학습 및 GPS 위치 기반으로 출퇴근 또는 자동 인사를 판별함.
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
                "🌟 패턴 학습 완료: 오늘도 힘찬 하루 보내세요! 출발 준비가 완료되었습니다."
            else
                "🌙 패턴 학습 완료: 오늘 하루도 정말 수고 많으셨습니다. 안전하게 귀가하세요."
            tvCommutePrompt.text = greeting
            tvCommutePrompt.visibility = View.VISIBLE
            btnConfirmCommute.visibility = View.GONE
            tvStatusBadge.text = "스마트 자동 케어 활성"
        } else {
            val prompt = commuteDetector.evaluateCommuteIntent(currentLat, currentLon)
            if (prompt != null) {
                tvCommutePrompt.text = "💡 스마트 주행 감지: ${prompt.message}"
                tvCommutePrompt.visibility = View.VISIBLE
                btnConfirmCommute.visibility = View.VISIBLE
                btnConfirmCommute.setOnClickListener {
                    currentCommuteMode = prompt.commuteType
                    tvStatusBadge.text = if (prompt.commuteType == CommuteType.MORNING) "출근 집중 모드 가동 중" else "퇴근 릴랙스 모드 가동 중"
                    tvCommutePrompt.text = "✅ ${prompt.destination.name} 경로 설정 및 맞춤 케어 루틴이 활성화되었습니다."
                    btnConfirmCommute.visibility = View.GONE
                    updateAcousticEnvironment()
                    updateLightingEnvironment()
                    updateNewsRecommendation()
                    requestAiBriefing()
                    Toast.makeText(this, "${prompt.destination.name} 맞춤 루틴 활성화", Toast.LENGTH_SHORT).show()
                }
            } else {
                tvCommutePrompt.visibility = View.GONE
                btnConfirmCommute.visibility = View.GONE
            }
        }
    }

    /**
     * @brief OpenAI GPT-4o-mini 기반 하이브리드 음성 브리핑 생성
     */
    private fun requestAiBriefing() {
        tvAiBriefing.text = "🤖 탑승자 맞춤 AI 브리핑 생성 중 (날씨, 캘린더, 메일 분석 중)..."
        lifecycleScope.launch {
            val events = calendarMailManager.fetchTodayEvents()
            val emails = calendarMailManager.fetchUnreadImportantEmails()
            val briefing = AiBriefingEngine.generateBriefing(
                currentCommuteMode,
                currentWeather,
                events,
                emails
            )
            tvAiBriefing.text = "🎙️ [Pleos AI 맞춤 음성 브리핑]\n$briefing"
            appendLog("AI 브리핑 생성 완료 (GPT-4o-mini)")
        }
    }

    /**
     * @brief 심리음향학 연구 근거 및 YouTube Music 기반 음악 큐레이션 적용
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
     * @brief 뉴스 추천 및 피드백 학습
     */
    private fun updateNewsRecommendation() {
        val topNews = newsEngine.recommendTopNews()
        currentNewsItem = topNews

        tvNewsTitle.text = "📰 [${topNews.category}] ${topNews.title}\n(추천 이유: 30대 남성 관심 키워드 '${topNews.relevanceTag}' 및 선호 점수)"
        tvNewsSummary.text = "이 뉴스를 음성 요약 브리핑으로 들으시겠습니까?"
        btnNewsAccept.visibility = View.VISIBLE
        btnNewsDecline.visibility = View.VISIBLE

        btnNewsAccept.setOnClickListener {
            newsEngine.recordUserFeedback(topNews, true)
            tvNewsSummary.text = "🎙️ [요약 브리핑]:\n${topNews.summary}\n\n(✨ 선호도 학습 완료: '${topNews.category}' 가중치 상승)"
            btnNewsAccept.visibility = View.GONE
            btnNewsDecline.visibility = View.GONE
            Toast.makeText(this, "선호 뉴스 카테고리로 학습되었습니다.", Toast.LENGTH_SHORT).show()
            appendLog("사용자 관심사 학습: [${topNews.category}] 선호도 +3")
        }

        btnNewsDecline.setOnClickListener {
            newsEngine.recordUserFeedback(topNews, false)
            tvNewsSummary.text = "이해했습니다. 다른 카테고리 뉴스를 우선 추천해 드릴게요."
            btnNewsAccept.visibility = View.GONE
            btnNewsDecline.visibility = View.GONE
            appendLog("사용자 관심사 조정: [${topNews.category}] 비선호 반영")
        }
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
        } else {
            tvTravelBanner.visibility = View.GONE
            btnTriggerTravelMode.text = "모의 목적지: 주말 콘도/리조트 여행 모드 시연"
            tvStatusBadge.text = "출퇴근 케어 에이전트 대기 중"
        }
        updateAcousticEnvironment()
    }

    private fun appendLog(msg: String) {
        val current = tvLogConsole.text.toString()
        tvLogConsole.text = "• $msg\n$current"
    }

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
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvStatusBadge)

        tvSpeed = TextView(this).apply {
            text = "차속: 0.0 km/h"
            setTextColor(0xFFBBDEFB.toInt())
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvSpeed)

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
        vehicleManager.release()
    }
}
