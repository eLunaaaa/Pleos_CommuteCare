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
import ai.pleos.commutecare.domain.news.AdaptiveNewsEngine
import ai.pleos.commutecare.domain.news.NewsItem
import ai.pleos.commutecare.domain.news.UserAgeGroup
import ai.pleos.commutecare.domain.news.UserGender
import ai.pleos.commutecare.vehicle.CommuteVehicleManager
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var vehicleManager: CommuteVehicleManager
    private val commuteDetector = SmartCommuteDetector()
    private val newsEngine = AdaptiveNewsEngine(UserAgeGroup.THIRTIES, UserGender.MALE)
    private val calendarMailManager = CalendarMailManager()

    private var currentCommuteMode = CommuteType.MORNING
    private var currentWeather: RealtimeWeather? = null
    private var currentAcousticPreset: AcousticPreset? = null
    private var currentNewsItem: NewsItem? = null

    // UI Components
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvWeatherDetails: TextView
    private lateinit var tvCommutePrompt: TextView
    private lateinit var btnConfirmCommute: Button
    private lateinit var tvAiBriefing: TextView
    private lateinit var btnRefreshAiBriefing: Button
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        initVehicleManager()
        loadRealtimeWeather()
        checkSmartCommuteIntent()
    }

    private fun initVehicleManager() {
        vehicleManager = CommuteVehicleManager(
            context = this,
            onLog = { msg -> appendLog(msg) },
            onSpeedChanged = { speedKmh ->
                tvSpeed.text = "현재 차속: %.1f km/h".format(speedKmh)
            },
            onRestrictionChanged = { isRestricted ->
                // 주행 중 운전자 주의분산 최적화 모드
            }
        )
        vehicleManager.connect()
    }

    /**
     * 1. 기상청 실시간 API 조회 (b51c7376... 키 사용)
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
            updateNewsRecommendation()
            requestAiBriefing()
        }
    }

    /**
     * 2. 네비/장소 기반 "출근/퇴근 중이십니까?" 지능형 감지
     */
    private fun checkSmartCommuteIntent() {
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
                updateNewsRecommendation()
                requestAiBriefing()
                Toast.makeText(this, "${prompt.destination.name} 맞춤 루틴 활성화", Toast.LENGTH_SHORT).show()
            }
        } else {
            tvCommutePrompt.visibility = View.GONE
            btnConfirmCommute.visibility = View.GONE
        }
    }

    /**
     * 3. OpenAI GPT-4o-mini 기반 하이브리드 인공지능 모닝/이브닝 브리핑 생성
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
     * 3. 차량 음향학(Acoustic Masking) 및 계절/날씨 근거 기반 사운드 & EQ 세팅
     */
    private fun updateAcousticEnvironment() {
        val condition = currentWeather?.conditionTag ?: WeatherConditionTag.CLEAR
        val preset = PsychoacousticEngine.determineAudioPreset(currentCommuteMode, condition)
        currentAcousticPreset = preset

        val bassSign = if (preset.bassGainDb >= 0) "+" else ""
        val trebleSign = if (preset.trebleGainDb >= 0) "+" else ""
        tvAudioInfo.text = "🎵 추천곡: ${preset.recommendedMusic}\n장르: ${preset.genreDescription}\n프리셋: ${preset.presetName}\n[EQ: Bass ${bassSign}%.1fdB, Mid +%.1fdB, Treble ${trebleSign}%.1fdB]".format(
            preset.bassGainDb,
            preset.midGainDb,
            preset.trebleGainDb
        )
        tvAcousticBasis.text = "🔬 음향학적 세팅 근거 (차량 환경 연구):\n${preset.scientificBasis}"
    }

    /**
     * 4. 나이/성별 기반 뉴스 추천 -> 요약 여부 확인(피드백 학습)
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

        // 스마트 출퇴근 감지 알림 배너
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
        layout.addView(createSectionHeader("🌡️ 스마트 프리컨디셔닝 (Pleos Vehicle SDK)"))
        val btnPrecondition = Button(this).apply {
            text = "목표 공조 22℃ + 열선 2단 원격 실행"
            setBackgroundColor(0xFF1E88E5.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                vehicleManager.startPreconditioning(22.0f, 2) { success ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, if (success) "프리컨디셔닝 완료" else "차량 SDK 응답 확인 필요", Toast.LENGTH_SHORT).show()
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

        // 4. 나이/성별 맞춤 뉴스 및 선호도 학습
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

        // 5. 진단 콘솔
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
