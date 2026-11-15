/**
 * @file MainActivity.kt
 * @brief Pleos CommuteCare 대화면 IVI 인포테인먼트 메인 화면 및 에이전트 오케스트레이션.
 *
 * 전체 라이프사이클 및 동작 순서:
 *  1. ViewBinding(ActivityMainBinding) 기반 대화면 2열 프리미엄 대시보드 뷰 팽창
 *  2. Pleos TextToSpeech(TTS) & SpeechToText(STT) SDK 3.3.1 기반 양방향 음성 대화 초기화
 *  3. Pleos FusedLocation SDK 1.1.3 기반 차량 실시간 위치 추적 및 주기적(10분/거리 이동) 기상청 날씨 동기화
 *  4. Pleos Vehicle SDK (공조/시트/조명) 및 AAOS Car Service 연결 초기화
 *  5. 출퇴근 패턴 학습 엔진(CommutePatternEngine)을 통한 개인화 목표 온도 및 능동 인사 판별
 *  6. 주행 소음 대응: 음성 질문 + STT 마이크 인식 + 대화면 원터치 퀵 리플라이 카드 하이브리드 지원
 *  7. OpenAI GPT-4o-mini 기반 하이브리드 음성 브리핑 생성 및 TTS 실시간 낭독
 *  8. 심리음향학 기반 YouTube Music 트랙 매칭 및 디 올 뉴 그랜저(GN7) 무드램프 색상 동기화
 */

package ai.pleos.commutecare.presentation

import ai.pleos.commutecare.data.learning.TripHistoryStore
import ai.pleos.commutecare.data.location.PleosLocationManager
import ai.pleos.commutecare.data.navi.PleosNaviManager
import ai.pleos.commutecare.data.weather.KmaWeatherClient
import ai.pleos.commutecare.data.weather.RealtimeWeather
import ai.pleos.commutecare.data.weather.WeatherConditionTag
import ai.pleos.commutecare.databinding.ActivityMainBinding
import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.ai.AiBriefingEngine
import ai.pleos.commutecare.domain.audio.AcousticPreset
import ai.pleos.commutecare.domain.audio.PsychoacousticEngine
import ai.pleos.commutecare.domain.calendar.CalendarMailManager
import ai.pleos.commutecare.domain.commute.CommuteDetectionSource
import ai.pleos.commutecare.domain.commute.CommutePrompt
import ai.pleos.commutecare.domain.commute.LocationPoint
import ai.pleos.commutecare.domain.commute.SmartCommuteDetector
import ai.pleos.commutecare.domain.learning.CommutePatternEngine
import ai.pleos.commutecare.domain.lighting.AmbientLightingEngine
import ai.pleos.commutecare.domain.lighting.AmbientLightingPreset
import ai.pleos.commutecare.domain.music.MusicLaunchResult
import ai.pleos.commutecare.domain.music.VehicleMusicManager
import ai.pleos.commutecare.domain.news.AdaptiveNewsEngine
import ai.pleos.commutecare.domain.news.NewsItem
import ai.pleos.commutecare.domain.news.UserAgeGroup
import ai.pleos.commutecare.domain.news.UserGender
import ai.pleos.commutecare.domain.travel.TravelModeEngine
import ai.pleos.commutecare.domain.voice.PleosVoiceManager
import ai.pleos.commutecare.domain.voice.VoiceCommand
import ai.pleos.commutecare.domain.voice.VoiceCommandParser
import ai.pleos.commutecare.vehicle.CommuteVehicleManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var vehicleManager: CommuteVehicleManager
    private lateinit var voiceManager: PleosVoiceManager
    private lateinit var locationManager: PleosLocationManager
    private lateinit var naviManager: PleosNaviManager

    private val commuteDetector = SmartCommuteDetector()
    private val newsEngine = AdaptiveNewsEngine(UserAgeGroup.THIRTIES, UserGender.MALE)
    private val calendarMailManager = CalendarMailManager()
    private lateinit var patternEngine: CommutePatternEngine
    private val travelEngine = TravelModeEngine()
    private val musicManager = VehicleMusicManager()

    private var currentCommuteMode = CommuteType.MORNING
    private var currentWeather: RealtimeWeather? = null
    private var currentAcousticPreset: AcousticPreset? = null
    private var currentLightingPreset: AmbientLightingPreset? = null
    private var currentNewsItem: NewsItem? = null
    private var isTravelModeActive: Boolean = false
    private var isCareActive = false
    private var pendingCommutePrompt: CommutePrompt? = null
    private var pendingQuickReplyYes: (() -> Unit)? = null
    private val handledPrompts = mutableSetOf<String>()
    private val greetedModes = mutableSetOf<CommuteType>()
    private var lastPromptKey: String? = null
    private var hasManualTemperature = false
    private var temperatureRequest = 0
    private var isTemperaturePending = false
    private val announcedPrompts = mutableSetOf<String>()
    private var isUxRestricted = true
    private var currentSpeedKmh = 0f
    private var currentSpeedLimit: Int? = null
    private var previousLocation: LocationPoint? = null
    private var demoJob: Job? = null
    private var briefingJob: Job? = null
    private var latestBriefingText: String = ""

    // 실시간 GPS 좌표 (FusedLocation 연동)
    private var currentLat = 37.4640
    private var currentLon = 127.0434
    private var hasLocationFix = false
    private var lastLocationFixMs = 0L
    private var currentTargetTemp = 22.0f

    /**
     * @brief 로컬 학습 상태를 복원하고 음성·내비·차량·위치 연결과 주행 제한 UI를 초기화한다.
     * @param savedInstanceState 재생성 전 저장한 화면 상태 또는 null
     * @return Unit
     * @throws 없음
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val historyStore = TripHistoryStore(this)
        patternEngine = CommutePatternEngine(historyStore.load(), historyStore::save,
            historyStore.loadPreferredTemperature(), historyStore::savePreferredTemperature)
        currentTargetTemp = savedInstanceState?.getFloat("targetTemp", 22f) ?: 22f
        hasManualTemperature = savedInstanceState?.getBoolean("manualTemp", false) ?: false
        savedInstanceState?.getStringArrayList("handledPrompts")?.let { handledPrompts.addAll(it) }
        savedInstanceState?.getStringArrayList("announcedPrompts")?.let { announcedPrompts.addAll(it) }
        initVoiceManager()
        setupClickListeners()
        setupGlassSpringFeedback()
        applyDrivingRestrictions(true)
        initNaviManager()
        initVehicleManager()
        initLocationManager()
        checkSmartCommuteIntent()

        // 탑승 시작 시 운전자 개인화 환영 음성
        lifecycleScope.launch {
            delay(1200)
            voiceManager.speak("민석님, 안녕하세요! Pleos 출퇴근 맞춤 케어를 시작합니다.")
        }

        if (intent?.getBooleanExtra("auto_demo", false) == true) {
            lifecycleScope.launch {
                delay(2200)
                runJudgeDemoTour()
            }
        }
    }

    /**
     * @brief Pleos NaviHelper SDK 3.0.5를 초기화하고 내비게이션 목적지 및 속도제한 이벤트를 구독합니다.
     * @return Unit
     * @throws 없음
     */
    private fun initNaviManager() {
        naviManager = PleosNaviManager(
            context = this,
            onDestinationUpdated = { analysis ->
                runOnUiThread {
                    binding.tvNaviStatus.text = analysis?.let { "목적지: ${it.destinationName}" }
                        ?: "목적지: 미설정 (자율 추론)"
                    appendLog("내비 목적지 갱신: ${analysis?.destinationName ?: "미설정"}")
                    checkSmartCommuteIntent()
                }
            },
            onSpeedLimitUpdated = { limit ->
                runOnUiThread {
                    currentSpeedLimit = limit
                    updateSpeedDisplay()
                }
            }
        )
        naviManager.initialize()
    }

    /**
     * @brief Pleos Voice Manager (TTS & STT) 초기화 및 음성 대화 리스너를 등록합니다.
     * @return Unit
     * @throws 없음
     */
    private fun initVoiceManager() {
        voiceManager = PleosVoiceManager(this)

        // 1. TTS 음성 출력 상태 콜백
        voiceManager.onSpeechStateChangeListener = { isSpeaking, speakingText ->
            runOnUiThread {
                if (isSpeaking) {
                    binding.tvVoiceStatus.text = "음성 안내 중"
                    binding.btnVoiceStop.visibility = View.VISIBLE
                } else {
                    if (!voiceManager.isListening) {
                        binding.tvVoiceStatus.text = "음성 비서 대기 중"
                        binding.btnVoiceStop.visibility = View.GONE
                    }
                }
            }
        }

        // 2. STT 음성 인식 청취 상태 및 부분 인식 콜백
        voiceManager.onListeningStateChangeListener = { isListening, partialText ->
            runOnUiThread {
                if (isListening) {
                    binding.tvVoiceStatus.text = partialText ?: "듣는 중..."
                    binding.btnVoiceMic.text = "중지"
                    binding.btnVoiceMic.setBackgroundResource(ai.pleos.commutecare.R.drawable.bg_button_orange)
                } else {
                    binding.btnVoiceMic.text = "음성 명령"
                    binding.btnVoiceMic.setBackgroundResource(ai.pleos.commutecare.R.drawable.bg_button_blue)
                    if (!voiceManager.isSpeaking) {
                        binding.tvVoiceStatus.text = "음성 비서 대기 중"
                    }
                }
            }
        }

        // 3. STT 최종 인식 음성 명령어 디스패처
        voiceManager.onVoiceCommandRecognizedListener = { command ->
            runOnUiThread {
                handleVoiceCommand(command)
            }
        }
    }

    /**
     * @brief 운전자의 음성 인식 명령어를 파싱하여 해당 차량/에이전트 기능을 실행합니다.
     * @param command 인식된 음성 텍스트
     * @return Unit
     * @throws 없음
     */
    private fun handleVoiceCommand(command: String) {
        val trimmed = command.trim()
        appendLog("[음성 명령 인식] \"$trimmed\"")
        Toast.makeText(this, "음성 명령: \"$trimmed\"", Toast.LENGTH_SHORT).show()

        when (VoiceCommandParser.parse(trimmed)) {
            VoiceCommand.CANCEL -> {
                demoJob?.cancel()
                voiceManager.stopSpeaking()
                dismissQuickReply()
            }
            VoiceCommand.STOP_CLIMATE -> {
                vehicleManager.stopPreconditioning()
                voiceManager.speak("공조와 열선 종료를 요청했습니다.")
            }
            VoiceCommand.DEMO -> runJudgeDemoTour()
            VoiceCommand.NEWS -> currentNewsItem?.let { onAcceptNewsSummary(it) }
                ?: voiceManager.speak("추천된 뉴스가 없습니다.")
            VoiceCommand.TEMPERATURE_UP -> adjustTargetTemperature(0.5f)
            VoiceCommand.TEMPERATURE_DOWN -> adjustTargetTemperature(-0.5f)
            VoiceCommand.TRAVEL -> toggleTravelMode()
            VoiceCommand.CLIMATE -> vehicleManager.startPreconditioning(currentTargetTemp, 2) { success ->
                voiceManager.speak(if (success) "공조와 열선이 설정되었습니다." else "공조 설정을 완료하지 못했습니다. 차량 상태를 확인해 주세요.")
            }
            VoiceCommand.MUSIC -> playCurrentCuratedMusic()
            VoiceCommand.MORNING, VoiceCommand.EVENING -> {
                val type = if (VoiceCommandParser.parse(trimmed) == VoiceCommand.MORNING) CommuteType.MORNING else CommuteType.EVENING
                val destination = if (type == CommuteType.MORNING) commuteDetector.officeLocation else commuteDetector.homeLocation
                executeCommuteRoutine(CommutePrompt(type, "사용자 음성 요청", destination, CommuteDetectionSource.NAVI_EXPLICIT))
            }
            VoiceCommand.CONFIRM -> pendingQuickReplyYes?.invoke()
                ?: voiceManager.speak("현재 수락을 기다리는 질문이 없습니다.")
            VoiceCommand.UNKNOWN -> voiceManager.speak("지원하는 명령을 확인하지 못했습니다. 출근, 퇴근, 온도 조절, 뉴스 요약을 말씀해 주세요.")
        }
    }

    /**
     * @brief Pleos FusedLocation SDK 1.1.3을 초기화하고 실시간 위치 및 주기적 날씨 동기화를 시작합니다.
     * @return Unit
     * @throws 없음
     */
    private fun initLocationManager() {
        locationManager = PleosLocationManager(this)

        // 실시간 위치 수신 콜백
        locationManager.onLocationChangedListener = { lat, lon, _ ->
            // 정차한 동일 좌표도 새 샘플로 반영하여 과거 이동 벡터가 남지 않게 한다.
            // 최초 기본 좌표는 실제 이동 이력으로 사용하지 않는다.
            previousLocation = if (hasLocationFix) LocationPoint("이전 위치", currentLat, currentLon) else null
            hasLocationFix = true
            lastLocationFixMs = SystemClock.elapsedRealtime()
            currentLat = lat
            currentLon = lon

            binding.tvLocationGps.text = "%.2f°N, %.2f°E".format(lat, lon)
            checkSmartCommuteIntent()
        }

        // 주기적(10분) 또는 거리 이동(2km)에 따른 날씨 갱신 트리거
        locationManager.onWeatherRefreshNeededListener = { lat, lon, reason ->
            appendLog("날씨 동기화 트리거: $reason (Lat=%.4f, Lon=%.4f)".format(lat, lon))
            loadRealtimeWeather(lat, lon)
        }

        locationManager.startLocationTracking()

        // 최초 1회 실시간 날씨 로드
        loadRealtimeWeather(currentLat, currentLon)
    }

    /**
     * @brief UI 버튼 및 인터랙션 리스너를 일괄 바인딩합니다.
     * @return Unit
     * @throws 없음
     */
    private fun setupClickListeners() {
        // 음성 마이크 토글 (STT)
        binding.btnVoiceMic.setOnClickListener {
            if (voiceManager.isListening) {
                voiceManager.stopListening()
            } else {
                voiceManager.startListening()
            }
        }

        // 음성 정지 및 다시 듣기 (TTS)
        binding.btnVoiceStop.setOnClickListener {
            voiceManager.stopSpeaking()
        }

        binding.btnVoiceReplay.setOnClickListener {
            if (latestBriefingText.isNotBlank()) {
                voiceManager.speak(latestBriefingText)
            }
        }

        // AI 브리핑 재요청
        binding.btnRefreshAiBriefing.setOnClickListener {
            requestAiBriefing()
        }

        // 공조 온도 수동 조절
        binding.btnTempDown.setOnClickListener {
            adjustTargetTemperature(-0.5f)
        }
        binding.btnTempUp.setOnClickListener {
            adjustTargetTemperature(+0.5f)
        }

        // 원격 프리컨디셔닝 실행
        binding.btnPrecondition.setOnClickListener {
            vehicleManager.startPreconditioning(currentTargetTemp, 2) { success ->
                runOnUiThread {
                    if (success) {
                        isCareActive = true
                        binding.teslaVehicleView.updateTelemetry(currentTargetTemp, true, currentSpeedKmh)
                    }
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "공조·열선 설정 완료 (미탑승 시 10분 후 종료)" else "차량 SDK 응답 확인 필요",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 여행 모드 토글
        binding.btnTriggerTravelMode.setOnClickListener {
            toggleTravelMode()
        }

        // ----------------------------------------------------
        // Pleos NaviHelper 연동 및 심사위원 시연 버튼 바인딩
        // ----------------------------------------------------
        binding.btnNaviSimWork.setOnClickListener {
            naviManager.simulateDestination("현대자동차 양재 본사", "서울특별시 서초구 헌릉로 12", 37.4640, 127.0434)
            Toast.makeText(this, "내비 출근 목적지 설정 (양재 본사)", Toast.LENGTH_SHORT).show()
        }

        binding.btnNaviSimClear.setOnClickListener {
            naviManager.clearDestination()
            binding.tvNaviStatus.text = "[내비게이션] 미설정 (지능형 자율 추론 모드 전환)"
            checkSmartCommuteIntent()
            Toast.makeText(this, "내비 해제: 지능형 자율 추론 모드 가동", Toast.LENGTH_SHORT).show()
        }

        binding.btnNaviSimTravel.setOnClickListener {
            naviManager.simulateDestination("속초 델피노 리조트", "강원도 고성군 토성면 미시령옛길 1153", 38.2107, 128.5135)
            Toast.makeText(this, "내비 여행 목적지 설정 (속초 델피노)", Toast.LENGTH_SHORT).show()
        }

        binding.btnJudgeDemoTour.setOnClickListener {
            runJudgeDemoTour()
        }

        // YouTube Music 재생 버튼 바인딩
        binding.btnPlayMusic.setOnClickListener {
            playCurrentCuratedMusic()
        }

        // 대시보드 위젯 터치 시 상세 팝업 오픈
        binding.cardBriefingTile.setOnClickListener {
            showBriefingPopup()
        }
        binding.cardAudioTile.setOnClickListener {
            showAudioPopup()
        }
        binding.cardModeTile.setOnClickListener {
            showModeAndLogPopup()
        }
    }

    /**
     * @brief AI 브리핑·날씨·맞춤 뉴스 상세 팝업을 표시한다.
     * @return Unit
     * @throws 없음
     */
    private fun showBriefingPopup() {
        val message = buildString {
            append("[기상 현황]\n${binding.tvWeatherDetails.text}\n\n")
            append("[AI 맞춤 브리핑]\n${binding.tvAiBriefing.text}\n\n")
            append("[맞춤 뉴스 추천]\n${binding.tvNewsTitle.text}\n${binding.tvNewsSummary.text}")
        }
        AlertDialog.Builder(this)
            .setTitle("AI 브리핑 및 뉴스")
            .setMessage(message)
            .setPositiveButton("뉴스 요약 듣기") { _, _ ->
                currentNewsItem?.let { onAcceptNewsSummary(it) } ?: requestAiBriefing()
            }
            .setNeutralButton("브리핑 갱신") { _, _ ->
                requestAiBriefing()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /**
     * @brief 심리음향학 추천 EQ 및 트랙 상세 팝업을 표시한다.
     * @return Unit
     * @throws 없음
     */
    private fun showAudioPopup() {
        val message = buildString {
            append("${binding.tvAudioInfo.text}\n\n")
            append("${binding.tvAcousticBasis.text}")
        }
        AlertDialog.Builder(this)
            .setTitle("추천 사운드 · EQ 설정")
            .setMessage(message)
            .setPositiveButton("YouTube Music 재생") { _, _ ->
                playCurrentCuratedMusic()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /**
     * @brief 목적지 설정·여행 모드·무드램프·시스템 로그 상세 팝업을 표시한다.
     * @return Unit
     * @throws 없음
     */
    private fun showModeAndLogPopup() {
        val logs = binding.tvLogConsole.text.toString().lines().take(6).joinToString("\n")
        val message = buildString {
            append("${binding.tvNaviStatus.text}\n")
            append("${binding.tvLightingInfo.text} (${binding.tvLightingBasis.text})\n")
            if (binding.tvTravelBanner.visibility == View.VISIBLE) {
                append("\n${binding.tvTravelBanner.text}\n")
            }
            append("\n[최근 시스템 로그]\n$logs")
        }
        AlertDialog.Builder(this)
            .setTitle("주행 모드 및 시스템 상태")
            .setMessage(message)
            .setPositiveButton("출근 목적지 설정") { _, _ ->
                naviManager.simulateDestination("현대자동차 양재 본사", "서울특별시 서초구 헌릉로 12", 37.4640, 127.0434)
                Toast.makeText(this, "출근 목적지 설정 완료", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("여행 모드 전환") { _, _ ->
                toggleTravelMode()
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    /**
     * @brief Pleos Vehicle SDK 및 차속/주행제한 리스너를 초기화합니다.
     * @return Unit
     * @throws 없음
     */
    private fun initVehicleManager() {
        vehicleManager = CommuteVehicleManager(
            context = this,
            onLog = { msg -> appendLog(msg) },
            onSpeedChanged = { speedKmh ->
                runOnUiThread {
                    currentSpeedKmh = kotlin.math.abs(speedKmh)
                    updateSpeedDisplay()
                    updateAcousticEnvironment()
                    if (currentSpeedKmh > 5.0f) {
                        vehicleManager.cancelSafetyAutoShutdown()
                    }
                }
            },
            onRestrictionChanged = { restricted -> runOnUiThread { applyDrivingRestrictions(restricted) } }
        )
        vehicleManager.connect()
    }

    /**
     * @brief 기상청 초단기실황 API를 호출하여 실시간 차량 위치 기반 날씨를 갱신합니다.
     * @param lat 조회 위도
     * @param lon 조회 경도
     * @return Unit
     * @throws 없음
     */
    private fun loadRealtimeWeather(lat: Double, lon: Double) {
        appendLog("기상청 단기예보 실황 API 호출 중 (%.4f, %.4f)...".format(lat, lon))
        lifecycleScope.launch {
            val weather = KmaWeatherClient.fetchCurrentWeather(lat, lon)
            if (weather != null) {
                currentWeather = weather
                binding.tvWeatherDetails.text = "${weather.temperatureCelsius}°C · ${weather.description}"
                appendLog("기상청 날씨 수신 성공: ${weather.temperatureCelsius}℃, PTY=${weather.precipitationType}")
            } else if (currentWeather != null) {
                val cached = currentWeather!!
                binding.tvWeatherDetails.text = "${cached.temperatureCelsius}°C (이전)"
                appendLog("기상청 API 실패: 이전 관측값 유지")
            } else {
                // 네트워크 두절/오프라인 환경에서는 로컬 룰베이스 기본 기상값으로 폴백
                val offlineFallback = ai.pleos.commutecare.data.weather.RealtimeWeather(
                    temperatureCelsius = 14.5f,
                    precipitationType = 0,
                    precipitation1HourMm = 0f,
                    humidityPercent = 52,
                    windSpeedMps = 2.1f,
                    description = "맑음 (오프라인)",
                    conditionTag = ai.pleos.commutecare.data.weather.WeatherConditionTag.MODERATE
                )
                currentWeather = offlineFallback
                binding.tvWeatherDetails.text = "${offlineFallback.temperatureCelsius}°C · 오프라인"
                appendLog("기상청 API 미연결: 로컬 오프라인 기본 날씨(14.5°C) 폴백 적용")
            }
            updateAcousticEnvironment()
            if (isCareActive) {
                updateLightingEnvironment()
                updateNewsRecommendation()
                requestAiBriefing()
            }
        }
    }

    /**
     * @brief 시계열 학습 및 GPS 위치 기반으로 출퇴근 또는 능동 인사를 판별하고 음성 및 터치 패널에 제안합니다.
     * @return Unit
     * @throws 없음
     */
    private fun checkSmartCommuteIntent() {
        if (!::naviManager.isInitialized || !::vehicleManager.isInitialized) return
        val cal = Calendar.getInstance()
        val day = cal.get(Calendar.DAY_OF_WEEK)
        val time = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
        val inferredMorning = cal.get(Calendar.HOUR_OF_DAY) < 12
        val inferredPattern = patternEngine.predictPattern(day, time, inferredMorning)
        val prompt = commuteDetector.evaluateCommuteIntent(
            naviManager.getCurrentDestinationAnalysis(), currentLat, currentLon,
            dayOfWeek = day,
            previousLocation = previousLocation.takeIf { SystemClock.elapsedRealtime() - lastLocationFixMs <= 30_000L },
            learnedDepartureEligible = inferredPattern.isAutoGreetingUnlocked
        )
        if (prompt == null) {
            pendingCommutePrompt = null
            pendingQuickReplyYes = null
            lastPromptKey = null
            binding.tvCommutePrompt.visibility = View.GONE
            binding.btnConfirmCommute.visibility = View.GONE
            binding.layoutQuickReplyCard.visibility = View.GONE
            return
        }
        val key = promptKey(prompt)
        if (key in handledPrompts || key == lastPromptKey) return
        lastPromptKey = key
        pendingCommutePrompt = prompt
        val pattern = patternEngine.predictPattern(day, time, prompt.commuteType == CommuteType.MORNING)
        if (!hasManualTemperature) currentTargetTemp = pattern.personalizedTargetTemp.coerceIn(16f, 30f)
        binding.tvTargetTempDisplay.text = "${currentTargetTemp}°C"
        binding.tvCommutePrompt.text = prompt.message
        binding.tvCommutePrompt.visibility = View.VISIBLE
        binding.btnConfirmCommute.visibility = View.VISIBLE
        binding.btnConfirmCommute.setOnClickListener { executeCommuteRoutine(prompt) }
        showQuickReplyPanel(
            question = prompt.message, yesLabel = "맞춤 케어 시작", noLabel = "일반 주행",
            onYes = { executeCommuteRoutine(prompt) }, onNo = { dismissQuickReply() }
        )
        val greeting = if (pattern.isAutoGreetingUnlocked && greetedModes.add(prompt.commuteType)) "반갑습니다. 평소 출발 시간대입니다. " else ""
        if (announcedPrompts.add(key)) voiceManager.speak("$greeting${prompt.message}")
    }

    /**
     * @brief 동일 세션에서 목적지별 수락/거절을 기억할 키를 만든다.
     * @param prompt 통근/여행 제안
     * @return 모드와 목적지 좌표로 구성한 키
     * @throws 없음
     */
    private fun promptKey(prompt: CommutePrompt): String =
        "${prompt.commuteType}:${prompt.destination.latitude}:${prompt.destination.longitude}"

    /**
     * @brief 주행 제한 시 상세 대시보드·시연·외부 앱 진입을 숨기고 음성 조작만 유지한다.
     * @param restricted AAOS 주의 분산 제한 여부 (미확인도 true)
     * @return Unit
     * @throws 없음
     */
    private fun applyDrivingRestrictions(restricted: Boolean) {
        isUxRestricted = restricted
        val visibility = if (restricted) View.GONE else View.VISIBLE
        binding.layoutDetailedDashboard.visibility = visibility
        binding.layoutDiagnostics.visibility = View.GONE
        binding.btnJudgeDemoTour.visibility = visibility
        binding.btnNaviSimClear.visibility = View.GONE
        binding.tvDrivingStatus.visibility = if (restricted) View.VISIBLE else View.GONE
        binding.layoutQuickReplyCard.visibility = if (!restricted && pendingQuickReplyYes != null) View.VISIBLE else View.GONE
        if (restricted) demoJob?.cancel()
    }

    /**
     * @brief 실제 차속과 도로 제한속도를 독립 보관하여 함께 표시한다.
     * @return Unit
     * @throws 없음
     */
    private fun updateSpeedDisplay() {
        binding.tvSpeed.text = "%.0f km/h".format(currentSpeedKmh) +
            (currentSpeedLimit?.let { " / 제한 ${it}" } ?: "")
        binding.teslaVehicleView.updateTelemetry(currentTargetTemp, isCareActive, currentSpeedKmh)
    }

    /**
     * @brief 출퇴근 맞춤 프리컨디셔닝 및 루틴을 실행합니다.
     * @param prompt 감지된 출퇴근 정보
     * @return Unit
     * @throws 없음
     */
    private fun executeCommuteRoutine(prompt: CommutePrompt) {
        isCareActive = true
        val newlyHandled = handledPrompts.add(promptKey(prompt))
        pendingCommutePrompt = null
        pendingQuickReplyYes = null
        currentCommuteMode = prompt.commuteType
        isTravelModeActive = prompt.commuteType == CommuteType.TRAVEL
        if (!isTravelModeActive) {
            binding.tvTravelBanner.visibility = View.GONE
            binding.btnTriggerTravelMode.text = "여행 모드 시연"
            if (newlyHandled) {
                val cal = Calendar.getInstance()
                patternEngine.recordTripEvent(cal.get(Calendar.DAY_OF_WEEK),
                    cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE),
                    currentTargetTemp, currentCommuteMode == CommuteType.MORNING)
            }
        }
        val modeText = when (prompt.commuteType) {
            CommuteType.TRAVEL -> "주말 힐링 드라이브 모드"
            CommuteType.MORNING -> "출근 집중 모드 가동 중"
            CommuteType.EVENING -> "퇴근 릴랙스 모드 가동 중"
        }
        binding.tvStatusBadge.text = modeText
        val sourceBadge = if (prompt.source == CommuteDetectionSource.NAVI_EXPLICIT) "[내비 연동]" else "[지능형 자율 추론]"
        binding.tvCommutePrompt.text = "$sourceBadge ${prompt.destination.name} 맞춤 케어 활성화"
        binding.btnConfirmCommute.visibility = View.GONE
        binding.layoutQuickReplyCard.visibility = View.GONE

        if (prompt.commuteType == CommuteType.TRAVEL) {
            toggleTravelMode(true, prompt.destination.name)
        } else {
            vehicleManager.startPreconditioning(currentTargetTemp, 2) { success ->
                runOnUiThread {
                    appendLog("출퇴근 케어 공조 설정: ${currentTargetTemp}℃ (결과: $success)")
                }
            }
            updateAcousticEnvironment()
            updateLightingEnvironment()
            updateNewsRecommendation()
            requestAiBriefing()
        }
        Toast.makeText(this, "${prompt.destination.name} 맞춤 루틴 활성화", Toast.LENGTH_SHORT).show()
    }

    /**
     * @brief OpenAI GPT-4o-mini 기반 하이브리드 음성 브리핑을 요청하고 TTS로 자동 낭독합니다.
     * @return Unit
     * @throws 없음
     */
    private fun requestAiBriefing() {
        binding.tvAiBriefing.text = "탑승자 맞춤 AI 브리핑 생성 중..."
        binding.btnVoiceReplay.visibility = View.GONE

        briefingJob?.cancel()
        briefingJob = lifecycleScope.launch {
            val events = calendarMailManager.fetchTodayEvents()
            val emails = calendarMailManager.fetchUnreadImportantEmails()
            val briefing = AiBriefingEngine.generateBriefing(
                currentCommuteMode,
                currentWeather,
                events,
                emails
            )
            latestBriefingText = briefing
            binding.tvAiBriefing.text = briefing
            binding.btnVoiceReplay.visibility = View.VISIBLE
            appendLog("AI 브리핑 생성 완료 (일정·메일은 시연 데이터)")

            voiceManager.speak(briefing)
        }
    }

    /**
     * @brief 심리음향학 연구 근거 및 YouTube Music 기반 음악 큐레이션 적용
     * @return Unit
     * @param speedKmh EQ 추천에 사용할 차속 (기본값: 실제 차속)
     * @throws 없음
     */
    private fun updateAcousticEnvironment(speedKmh: Float = currentSpeedKmh) {
        if (isTravelModeActive) {
            val rec = travelEngine.buildTravelRecommendation("속초 설악 리조트")
            currentAcousticPreset = rec.travelAudioPreset
        } else {
            val condition = currentWeather?.conditionTag ?: WeatherConditionTag.CLEAR
            currentAcousticPreset = PsychoacousticEngine.determineAudioPreset(currentCommuteMode, condition, speedKmh = speedKmh)
        }

        val preset = currentAcousticPreset!!
        val bassSign = if (preset.bassGainDb >= 0) "+" else ""
        val trebleSign = if (preset.trebleGainDb >= 0) "+" else ""

        val track = musicManager.getPresetTrack(if (isTravelModeActive) "TRAVEL" else if (currentCommuteMode == CommuteType.MORNING) "MORNING" else "EVENING")

        binding.tvAudioInfo.text = "${track.title} · ${track.artist} (${track.contextTag})"
        binding.tvAcousticBasis.text = "제안 EQ: Bass ${bassSign}%.1fdB / Mid +%.1fdB / Treble ${trebleSign}%.1fdB".format(
            preset.bassGainDb,
            preset.midGainDb,
            preset.trebleGainDb
        )
    }

    /**
     * @brief 현재 심리음향학 큐레이션 트랙을 실제 YouTube Music 앱 또는 웹 플레이어로 연동하여 재생합니다.
     * @return Unit
     * @throws 없음
     */
    private fun playCurrentCuratedMusic() {
        if (isUxRestricted) {
            voiceManager.speak("음악 앱과 웹 화면은 정차 후 열 수 있습니다.")
            return
        }
        val track = musicManager.getPresetTrack(
            if (isTravelModeActive) "TRAVEL"
            else if (currentCommuteMode == CommuteType.MORNING) "MORNING"
            else "EVENING"
        )
        val result = musicManager.launchTrackPlayback(this, track, isUxRestricted)
        when (result) {
            is MusicLaunchResult.SuccessApp -> {
                appendLog("[YouTube Music 앱 연동 성공] ${track.artist} - ${track.title}")
                Toast.makeText(this, "YouTube Music 앱에서 재생합니다: ${track.title}", Toast.LENGTH_SHORT).show()
                voiceManager.speak("유튜브 뮤직으로 ${track.title} 페이지를 엽니다.")
            }
            is MusicLaunchResult.SuccessBrowser -> {
                appendLog("[YouTube Music 웹 플레이어 연동 성공] ${track.artist} - ${track.title}")
                Toast.makeText(this, "YouTube Music 웹 재생: ${track.title}", Toast.LENGTH_SHORT).show()
                voiceManager.speak("${track.title} 페이지를 엽니다.")
            }
            is MusicLaunchResult.Failure -> {
                appendLog("[음악 재생 실패] ${result.reason}")
                Toast.makeText(this, "음악 연동 실패: ${result.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * @brief 그랜저(GN7) 앰비언트 라이트 색상 동기화
     * @return Unit
     * @throws 없음
     */
    private fun updateLightingEnvironment() {
        val condition = currentWeather?.conditionTag ?: WeatherConditionTag.CLEAR
        val preset = AmbientLightingEngine.determineLightingPreset(currentCommuteMode, condition)
        currentLightingPreset = preset

        binding.tvLightingInfo.text = "${preset.colorNameKo} (${preset.hexString})"
        binding.tvLightingBasis.text = preset.presetName

        vehicleManager.setAmbientMoodLight(preset.colorHex) { success ->
            if (success) appendLog("GN7 무드램프 색상 자동 동기화 완료: ${preset.colorNameKo}")
        }
    }

    /**
     * @brief 뉴스 추천 및 피드백 학습 (소음 대비 터치 퀵 리플라이 연동)
     * @return Unit
     * @throws 없음
     */
    private fun updateNewsRecommendation() {
        val topNews = newsEngine.recommendTopNews()
        currentNewsItem = topNews

        binding.tvNewsTitle.text = "[${topNews.category}] ${topNews.title}"
        binding.tvNewsSummary.text = "30대 남성 관심 키워드 '${topNews.relevanceTag}' 기반 추천. 음성 요약으로 들으시겠습니까?"
        binding.btnNewsAccept.visibility = View.VISIBLE
        binding.btnNewsDecline.visibility = View.VISIBLE

        binding.btnNewsAccept.setOnClickListener {
            onAcceptNewsSummary(topNews)
        }

        binding.btnNewsDecline.setOnClickListener {
            onDeclineNewsSummary(topNews)
        }
    }

    /**
     * @brief 뉴스 요약 수락 시 음성 낭독 및 선호도 가중치 학습
     * @param newsItem 요약 대상 뉴스 아이템
     * @return Unit
     * @throws 없음
     */
    private fun onAcceptNewsSummary(newsItem: NewsItem) {
        newsEngine.recordUserFeedback(newsItem, true)
        binding.tvNewsSummary.text = "[3줄 요약]:\n${newsItem.summary}\n\n(선호도 학습 완료: '${newsItem.category}' 가중치 상승)"
        binding.btnNewsAccept.visibility = View.GONE
        binding.btnNewsDecline.visibility = View.GONE
        Toast.makeText(this, "선호 뉴스 카테고리로 학습되었습니다.", Toast.LENGTH_SHORT).show()
        appendLog("사용자 관심사 학습: [${newsItem.category}] 선호도 +3")

        voiceManager.speak("선택하신 ${newsItem.category} 뉴스 요약입니다. ${newsItem.summary}")
    }

    /**
     * @brief 뉴스 요약 거절 시 피드백 학습
     * @param newsItem 거절 대상 뉴스 아이템
     * @return Unit
     * @throws 없음
     */
    private fun onDeclineNewsSummary(newsItem: NewsItem) {
        newsEngine.recordUserFeedback(newsItem, false)
        binding.tvNewsSummary.text = "이해했습니다. 다른 카테고리 뉴스를 우선 추천해 드릴게요."
        binding.btnNewsAccept.visibility = View.GONE
        binding.btnNewsDecline.visibility = View.GONE
        appendLog("사용자 관심사 조정: [${newsItem.category}] 비선호 반영")
        voiceManager.speak("알겠습니다. 다른 관심 뉴스를 준비하겠습니다.")
    }

    /**
     * @brief 소음 환경 대비 대화면 퀵 리플라이 패널을 표시합니다.
     * @param question 질문 텍스트
     * @param yesLabel 긍정 버튼 문구
     * @param noLabel 부정 버튼 문구
     * @param altLabel 대안/전환 버튼 문구
     * @param onYes 수락 시 동작
     * @param onNo 거절 시 동작
     * @param onAlt 대안 선택 시 동작 또는 null
     * @return Unit
     * @throws 없음
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
        pendingQuickReplyYes = { pendingQuickReplyYes = null; binding.layoutQuickReplyCard.visibility = View.GONE; onYes() }
        binding.tvQuickReplyQuestion.text = question
        binding.btnQuickReplyYes.text = yesLabel
        binding.btnQuickReplyYes.setOnClickListener { pendingQuickReplyYes?.invoke() }

        binding.btnQuickReplyNo.text = noLabel
        binding.btnQuickReplyNo.setOnClickListener { onNo() }

        if (altLabel != null && onAlt != null) {
            binding.btnQuickReplyAlt.text = altLabel
            binding.btnQuickReplyAlt.visibility = View.VISIBLE
            binding.btnQuickReplyAlt.setOnClickListener { onAlt() }
        } else {
            binding.btnQuickReplyAlt.visibility = View.GONE
        }

        binding.layoutQuickReplyCard.visibility = if (isUxRestricted) View.GONE else View.VISIBLE
    }

    /**
     * @brief 퀵 리플라이 패널을 닫습니다.
     * @return Unit
     * @throws 없음
     */
    private fun dismissQuickReply() {
        pendingCommutePrompt?.let { handledPrompts.add(promptKey(it)) }
        pendingCommutePrompt = null
        pendingQuickReplyYes = null
        binding.tvCommutePrompt.visibility = View.GONE
        binding.btnConfirmCommute.visibility = View.GONE
        binding.layoutQuickReplyCard.visibility = View.GONE
        voiceManager.speak("일반 주행 모드를 유지합니다.")
    }

    /**
     * @brief 수동 온도를 실제 차량에 적용하고 성공한 값만 확정·학습한다.
     * @param delta 온도 변화량 (+0.5 또는 -0.5)
     * @return Unit
     * @throws 없음
     */
    private fun adjustTargetTemperature(delta: Float) {
        if (isTemperaturePending) return
        val requested = ((currentTargetTemp + delta).coerceIn(16f, 30f) * 2).toInt() / 2f
        val request = ++temperatureRequest
        isTemperaturePending = true
        hasManualTemperature = true
        binding.btnTempUp.isEnabled = false
        binding.btnTempDown.isEnabled = false
        binding.tvTargetTempDisplay.text = "${requested}°C ..."
        var completed = false
        fun finish(success: Boolean) {
            if (completed || request != temperatureRequest || isDestroyed) return
            completed = true
            isTemperaturePending = false
            binding.btnTempUp.isEnabled = true
            binding.btnTempDown.isEnabled = true
            if (success) {
                currentTargetTemp = requested
                patternEngine.updateTemperaturePreference(requested)
                binding.tvTargetTempDisplay.text = "${currentTargetTemp}°C"
                binding.teslaVehicleView.updateTelemetry(currentTargetTemp, isCareActive, currentSpeedKmh)
                appendLog("수동 공조 온도 적용: ${currentTargetTemp}℃")
                voiceManager.speak("목표 온도를 ${currentTargetTemp}도로 설정했습니다.")
            } else {
                binding.tvTargetTempDisplay.text = "${currentTargetTemp}°C"
                voiceManager.speak("목표 온도 변경을 확인하지 못했습니다. 차량 상태를 확인해 주세요.")
            }
        }
        val timeout = lifecycleScope.launch { delay(5_000); finish(false) }
        vehicleManager.setTargetTemperature(requested) { success ->
            timeout.cancel()
            finish(success)
        }
    }

    /**
     * @brief 주말/외곽 리조트 목적지 입력 시 여행 모드를 활성화함.
     * @param forceEnable 강제 활성화 여부 (null이면 토글)
     * @param destinationName 여행 목적지 명칭 (기본값: 시연 목적지)
     * @return Unit
     * @throws 없음
     */
    private fun toggleTravelMode(forceEnable: Boolean? = null, destinationName: String = "속초 설악 한화리조트 (시연)") {
        isTravelModeActive = forceEnable ?: !isTravelModeActive
        if (isTravelModeActive) {
            val rec = travelEngine.buildTravelRecommendation(destinationName)
            binding.tvTravelBanner.text = "[여행 모드 활성화 - ${rec.destinationName}]\n${rec.travelBriefingMessage}\n\n명소 및 맛집 추천:\n" +
                    rec.topPois.joinToString("\n") { "• [${it.category}] ${it.name}: ${it.description}" }
            binding.tvTravelBanner.visibility = View.VISIBLE
            binding.btnTriggerTravelMode.text = "출퇴근 일상 모드로 복귀"
            binding.tvStatusBadge.text = "주말 힐링 드라이브 모드"

            voiceManager.speak("${rec.destinationName} 여행 모드를 시작합니다. 음악 추천과 시연용 명소 목록을 표시합니다.")
        } else {
            binding.tvTravelBanner.visibility = View.GONE
            binding.btnTriggerTravelMode.text = "여행 모드 전환"
            binding.tvStatusBadge.text = "대기 중"
            voiceManager.speak("출퇴근 케어 일상 모드로 복귀합니다.")
        }
        updateAcousticEnvironment()
    }

    /**
     * @brief 심사위원 대상 5단계 원클릭 시연 투어를 순차적으로 자동 실행합니다.
     * @return Unit
     * @throws 없음
     */
    private fun runJudgeDemoTour() {
        if (isUxRestricted || demoJob?.isActive == true) {
            voiceManager.speak("시연은 정차 상태가 확인된 후 시작할 수 있습니다.")
            return
        }
        demoJob = lifecycleScope.launch {
            try {
                appendLog("[심사 시연 투어] 5단계 원클릭 자동 시연을 시작합니다.")
                binding.btnJudgeDemoTour.isEnabled = false
                binding.btnJudgeDemoTour.text = "시연 진행 중..."

                // 1단계: 탑승 & 정차(0 km/h) -> GPS 자택 출발 감지 및 공조 프리컨디셔닝 시작
                appendLog("[1단계: 탑승 & 자율 추론] 정차(0 km/h) · 자택 GPS(37.5172, 127.0473) -> 평일 오전 출근 추론")
                currentLat = 37.5172
                currentLon = 127.0473
                currentSpeedKmh = 0f
                naviManager.clearDestination()
                binding.tvNaviStatus.text = "자율 추론 · 현대차 본사"
                val prompt = commuteDetector.evaluateCommuteIntent(null, currentLat, currentLon, 8,
                    Calendar.MONDAY, LocationPoint("시연 이전 위치", 37.5176, 127.0473))
                executeCommuteRoutine(prompt ?: CommutePrompt(
                    CommuteType.MORNING, "현대차 본사 출근", LocationPoint("현대차 본사", 37.4640, 127.0434),
                    CommuteDetectionSource.IMPLICIT_INFERENCE
                ))
                // 목표 온도를 21.0°C로 낮춰 투명 차량 공조 기류 변화를 즉시 시각화
                currentTargetTemp = 21.0f
                binding.tvTargetTempDisplay.text = "${currentTargetTemp}°C"
                binding.teslaVehicleView.updateTelemetry(currentTargetTemp, true, 0f)
                delay(2800)

                // 2단계: 도심 주행 시작 (실제 GPS 이동 + 0 -> 55 km/h 실시간 가속)
                appendLog("[2단계: 도심 주행 가속] GPS 강남대로 이동 & 차속 0 -> 55 km/h 가속 + AI 브리핑")
                binding.tvNaviStatus.text = "강남대로 주행 중 (37.498, 127.028)"
                animateDemoDrive(37.5172, 127.0473, 37.4980, 127.0276, 0f, 55f, steps = 8, stepDelayMs = 350L)
                delay(1800)

                // 3단계: 고속도로 진입 (55 -> 88 km/h 가속) -> 심리음향학 EQ & 소음 안심 퀵리플라이
                appendLog("[3단계: 고속도로 소음 대응] 양재 IC 진입(55 -> 88 km/h) -> 로드노이즈 마스킹 EQ & 퀵리플라이")
                binding.tvNaviStatus.text = "경부고속도로 · 88 km/h"
                animateDemoDrive(37.4980, 127.0276, 37.4640, 127.0434, 55f, 88f, steps = 8, stepDelayMs = 350L)
                showQuickReplyPanel(
                    question = "\"주행 소음(88 km/h) 감지: 실내 온도를 조절할까요?\"",
                    yesLabel = "온도 1℃ 내림",
                    noLabel = "현재 유지",
                    altLabel = "뉴스 요약",
                    onYes = { adjustTargetTemperature(-1.0f) },
                    onNo = { dismissQuickReply() },
                    onAlt = { requestAiBriefing() }
                )
                voiceManager.speak("고속 주행 소음에 맞춰 오디오 이퀄라이저 추천값을 표시합니다.")
                delay(2500)

                // 4단계: 내비게이션 주말 여행 목적지 감지 & 감속 (88 -> 60 km/h)
                appendLog("[4단계: 여행 모드 전환] Pleos NaviHelper: '속초 델피노 리조트' 목적지 수신")
                dismissQuickReply()
                naviManager.simulateDestination("속초 델피노 리조트", "강원도 고성군 토성면 미시령옛길 1153", 38.2107, 128.5135)
                binding.tvNaviStatus.text = "목적지 · 속초 델피노 리조트"
                toggleTravelMode(true)
                animateDemoDrive(37.4640, 127.0434, 37.8500, 127.7500, 88f, 60f, steps = 6, stepDelayMs = 350L)
                delay(1800)

                // 5단계: 목적지 도착 감속 & 안전 정차 (60 -> 0 km/h)
                appendLog("[5단계: 안전 정차] 차속 60 -> 0 km/h 감속 정차 및 미탑승 10분 안전 타이머 안내")
                animateDemoDrive(37.8500, 127.7500, 38.2107, 128.5135, 60f, 0f, steps = 6, stepDelayMs = 300L)
                voiceManager.speak("시연 투어가 완료되었습니다. 정차 후 미탑승 시 10분 뒤 공조가 자동 종료됩니다.")
                appendLog("[심사 시연 투어] 5단계 시연 완료")

            } finally {
                currentSpeedKmh = 0f
                binding.btnJudgeDemoTour.isEnabled = true
                binding.btnJudgeDemoTour.text = "시연 투어"
                updateSpeedDisplay()
                updateAcousticEnvironment()
            }
        }
    }

    /**
     * @brief 시연 투어 중 실제 GPS 이동과 차량 가·감속을 단계별로 보간하여 실시간 대시보드에 반영한다.
     * @param fromLat 출발 위도
     * @param fromLon 출발 경도
     * @param toLat 도착 위도
     * @param toLon 도착 경도
     * @param startSpeed 시작 속도 (km/h)
     * @param endSpeed 목표 속도 (km/h)
     * @param steps 보간 단계 수
     * @param stepDelayMs 단계 간 지연 시간 (ms)
     * @return Unit
     */
    private suspend fun animateDemoDrive(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        startSpeed: Float,
        endSpeed: Float,
        steps: Int,
        stepDelayMs: Long
    ) {
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            currentLat = fromLat + (toLat - fromLat) * t
            currentLon = fromLon + (toLon - fromLon) * t
            currentSpeedKmh = startSpeed + (endSpeed - startSpeed) * t
            updateSpeedDisplay()
            updateAcousticEnvironment(currentSpeedKmh)
            binding.tvLocationGps.text = "GPS: %.4f, %.4f".format(currentLat, currentLon)
            delay(stepDelayMs)
        }
    }

    /**
     * @brief 진단 로그를 출력하고 화면 보관 길이를 제한한다.
     * @param msg 출력할 로그 문구
     * @return Unit
     * @throws 없음
     */
    private fun appendLog(msg: String) {
        if (isDestroyed) return
        Log.i(TAG, msg)
        runOnUiThread {
            val current = binding.tvLogConsole.text.toString()
            binding.tvLogConsole.text = "• $msg\n$current".take(12_000)
        }
    }

    /**
     * @brief 수동 온도와 세션별 제안 처리 상태를 화면 재생성에 대비해 저장한다.
     * @param outState 화면 재생성에 전달할 상태
     * @return Unit
     * @throws 없음
     */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat("targetTemp", currentTargetTemp)
        outState.putBoolean("manualTemp", hasManualTemperature)
        outState.putStringArrayList("handledPrompts", ArrayList(handledPrompts))
        outState.putStringArrayList("announcedPrompts", ArrayList(announcedPrompts))
        super.onSaveInstanceState(outState)
    }

    /**
     * @brief 애플 글래스모픽 버튼에 고주사율 스프링 터치 피드백(Scale 0.94 -> 1.0)을 적용한다.
     * @return Unit
     * @throws 없음
     */
    private fun setupGlassSpringFeedback() {
        val springViews = listOf(
            binding.btnJudgeDemoTour,
            binding.btnRefreshAiBriefing,
            binding.btnTempDown,
            binding.btnTempUp,
            binding.btnPrecondition,
            binding.btnVoiceMic,
            binding.btnVoiceReplay,
            binding.btnVoiceStop,
            binding.btnPlayMusic,
            binding.btnTriggerTravelMode,
            binding.btnConfirmCommute,
            binding.btnNaviSimWork,
            binding.btnNaviSimTravel,
            binding.btnNaviSimClear
        )
        val springInterpolator = android.view.animation.OvershootInterpolator(1.6f)
        springViews.forEach { target ->
            target.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(0.94f)
                            .scaleY(0.94f)
                            .alpha(0.86f)
                            .setDuration(90L)
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().cancel()
                        v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .alpha(1.0f)
                            .setDuration(220L)
                            .setInterpolator(springInterpolator)
                            .start()
                    }
                }
                false
            }
        }
    }

    /**
     * @brief 위치·내비·음성 연결을 해제하고 활성 공조 종료를 요청한다.
     * @return Unit
     * @throws 없음
     */
    override fun onDestroy() {
        super.onDestroy()
        naviManager.release()
        locationManager.stopLocationTracking()
        voiceManager.release()
        vehicleManager.release()
    }
}
