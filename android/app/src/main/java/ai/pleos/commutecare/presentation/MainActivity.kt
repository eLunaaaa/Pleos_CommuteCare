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

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.pleos.commutecare.data.location.PleosLocationManager
import ai.pleos.commutecare.data.weather.KmaWeatherClient
import ai.pleos.commutecare.data.weather.RealtimeWeather
import ai.pleos.commutecare.data.weather.WeatherConditionTag
import ai.pleos.commutecare.databinding.ActivityMainBinding
import ai.pleos.commutecare.domain.CommuteSchedule
import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.ai.AiBriefingEngine
import ai.pleos.commutecare.domain.audio.AcousticPreset
import ai.pleos.commutecare.domain.audio.PsychoacousticEngine
import ai.pleos.commutecare.domain.calendar.CalendarMailManager
import ai.pleos.commutecare.domain.commute.CommutePrompt
import ai.pleos.commutecare.domain.commute.LocationPoint
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

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var vehicleManager: CommuteVehicleManager
    private lateinit var voiceManager: PleosVoiceManager
    private lateinit var locationManager: PleosLocationManager

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

    // 실시간 GPS 좌표 (FusedLocation 연동)
    private var currentLat = 37.4640
    private var currentLon = 127.0434
    private var currentHeading = 0.0f
    private var currentTargetTemp = 22.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initVoiceManager()
        setupClickListeners()
        patternEngine.seedDemonstrationHistory()
        initVehicleManager()
        initLocationManager()
    }

    /**
     * @brief Pleos Voice Manager (TTS & STT) 초기화 및 음성 대화 리스너를 등록합니다.
     * @return Unit
     */
    private fun initVoiceManager() {
        voiceManager = PleosVoiceManager(this)

        // 1. TTS 음성 출력 상태 콜백
        voiceManager.onSpeechStateChangeListener = { isSpeaking, speakingText ->
            runOnUiThread {
                if (isSpeaking) {
                    val preview = if (speakingText != null) " - \"${speakingText.take(24)}...\"" else ""
                    binding.tvVoiceStatus.text = "🔊 [음성 안내 중] Pleos TTS 출력$preview"
                    binding.btnVoiceStop.visibility = View.VISIBLE
                } else {
                    if (!voiceManager.isListening) {
                        binding.tvVoiceStatus.text = "🔈 [음성 대기] Pleos On-Device TTS/STT 준비 완료"
                        binding.btnVoiceStop.visibility = View.GONE
                    }
                }
            }
        }

        // 2. STT 음성 인식 청취 상태 및 부분 인식 콜백
        voiceManager.onListeningStateChangeListener = { isListening, partialText ->
            runOnUiThread {
                if (isListening) {
                    binding.tvVoiceStatus.text = "🎙️ [음성 청취 중] ${partialText ?: "말씀해 주세요..."}"
                    binding.btnVoiceMic.text = "🔴 듣는 중... (터치 시 중단)"
                    binding.btnVoiceMic.setBackgroundResource(ai.pleos.commutecare.R.drawable.bg_button_orange)
                } else {
                    binding.btnVoiceMic.text = "🎤 음성 명령 말하기"
                    binding.btnVoiceMic.setBackgroundResource(ai.pleos.commutecare.R.drawable.bg_button_blue)
                    if (!voiceManager.isSpeaking) {
                        binding.tvVoiceStatus.text = "🔈 [음성 대기] Pleos On-Device TTS/STT 준비 완료"
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
     */
    private fun handleVoiceCommand(command: String) {
        val trimmed = command.trim()
        appendLog("🎙️ 음성 명령 인식: \"$trimmed\"")
        Toast.makeText(this, "음성 명령: \"$trimmed\"", Toast.LENGTH_SHORT).show()

        when {
            // 1. 출퇴근 시작 명령
            trimmed.contains("출근") || trimmed.contains("퇴근") || trimmed.contains("네") || trimmed.contains("시작") -> {
                val prompt = commuteDetector.evaluateCommuteIntent(currentLat, currentLon)
                    ?: CommutePrompt(
                        commuteType = currentCommuteMode,
                        destination = LocationPoint("현대자동차 양재 본사", currentLat, currentLon),
                        message = "맞춤 출근 루틴을 가동합니다."
                    )
                executeCommuteRoutine(prompt)
                voiceManager.speak("맞춤 루틴과 프리컨디셔닝을 시작합니다.")
            }

            // 2. 뉴스 요약 명령
            trimmed.contains("뉴스") || trimmed.contains("요약") -> {
                currentNewsItem?.let { onAcceptNewsSummary(it) } ?: run {
                    voiceManager.speak("현재 추천된 뉴스를 불러오는 중입니다.")
                }
            }

            // 3. 공조 온도 올리기
            trimmed.contains("더워") || trimmed.contains("온도 올려") || trimmed.contains("올려줘") || trimmed.contains("따뜻") -> {
                adjustTargetTemperature(+0.5f)
                voiceManager.speak("목표 공조 온도를 ${currentTargetTemp}도로 올렸습니다.")
            }

            // 4. 공조 온도 내리기
            trimmed.contains("추워") || trimmed.contains("온도 내려") || trimmed.contains("내려줘") || trimmed.contains("시원") -> {
                adjustTargetTemperature(-0.5f)
                voiceManager.speak("목표 공조 온도를 ${currentTargetTemp}도로 내렸습니다.")
            }

            // 5. 여행 모드 전환
            trimmed.contains("여행") || trimmed.contains("콘도") || trimmed.contains("리조트") || trimmed.contains("주말") -> {
                toggleTravelMode()
            }

            // 6. 시동 및 공조 가동
            trimmed.contains("시동") || trimmed.contains("에어컨") || trimmed.contains("히터") || trimmed.contains("프리컨디셔닝") -> {
                vehicleManager.startPreconditioning(currentTargetTemp, 2) { success ->
                    runOnUiThread {
                        voiceManager.speak(if (success) "원격 시동과 프리컨디셔닝이 실행되었습니다." else "차량 공조 상태를 확인해주세요.")
                    }
                }
            }

            // 7. 일반 주행 / 취소
            trimmed.contains("아니") || trimmed.contains("취소") || trimmed.contains("그만") -> {
                dismissQuickReply()
            }

            else -> {
                voiceManager.speak("\"$trimmed\" 명령을 확인했습니다. 화면 퀵 버튼으로도 조작하실 수 있습니다.")
            }
        }
    }

    /**
     * @brief Pleos FusedLocation SDK 1.1.3을 초기화하고 실시간 위치 및 주기적 날씨 동기화를 시작합니다.
     */
    private fun initLocationManager() {
        locationManager = PleosLocationManager(this)

        // 실시간 위치 수신 콜백
        locationManager.onLocationChangedListener = { lat, lon, heading ->
            currentLat = lat
            currentLon = lon
            currentHeading = heading

            binding.tvLocationGps.text = "📍 실시간 GPS: %.4f°N, %.4f°E (FusedLocation)".format(lat, lon)
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
                    Toast.makeText(
                        this@MainActivity,
                        if (success) "원격 시동 및 공조 가동 완료 (10분 미탑승 시 자동 시동 OFF)" else "차량 SDK 응답 확인 필요",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 여행 모드 토글
        binding.btnTriggerTravelMode.setOnClickListener {
            toggleTravelMode()
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
                runOnUiThread {
                    binding.tvSpeed.text = "현재 차속: %.1f km/h".format(speedKmh)
                    if (speedKmh > 5.0f) {
                        vehicleManager.cancelSafetyAutoShutdown()
                    }
                }
            },
            onRestrictionChanged = { _ -> }
        )
        vehicleManager.connect()
    }

    /**
     * @brief 기상청 초단기실황 API를 호출하여 실시간 차량 위치 기반 날씨를 갱신합니다.
     * @param lat 조회 위도
     * @param lon 조회 경도
     * @return Unit
     */
    private fun loadRealtimeWeather(lat: Double, lon: Double) {
        appendLog("기상청 단기예보 실황 API 호출 중 (%.4f, %.4f)...".format(lat, lon))
        lifecycleScope.launch {
            val weather = KmaWeatherClient.fetchCurrentWeather(lat, lon)
            currentWeather = weather
            if (weather != null) {
                binding.tvWeatherDetails.text = "⛅ [기상청 초단기실황] ${weather.description} (풍속 ${weather.windSpeedMps}m/s)"
                appendLog("기상청 날씨 수신 성공: ${weather.temperatureCelsius}℃, PTY=${weather.precipitationType}")
            } else {
                binding.tvWeatherDetails.text = "⛅ [기상청 API 수신 대기] 14.5℃, 맑음 (로컬 캐시)"
                appendLog("기상청 API 응답 대기/캐시 전환")
            }
            updateAcousticEnvironment()
            updateLightingEnvironment()
            updateNewsRecommendation()
            requestAiBriefing()
        }
    }

    /**
     * @brief 시계열 학습 및 GPS 위치 기반으로 출퇴근 또는 능동 인사를 판별하고 음성 및 터치 패널에 제안합니다.
     * @return Unit
     */
    private fun checkSmartCommuteIntent() {
        val cal = Calendar.getInstance()
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val hourMinute = cal.get(Calendar.HOUR_OF_DAY) * 100 + cal.get(Calendar.MINUTE)
        val pattern = patternEngine.predictPattern(dayOfWeek, hourMinute, currentCommuteMode == CommuteType.MORNING)

        currentTargetTemp = pattern.personalizedTargetTemp
        binding.tvTargetTempDisplay.text = "목표 공조: ${currentTargetTemp}℃ (학습된 최적 온도)"

        if (pattern.isAutoGreetingUnlocked) {
            val greeting = if (currentCommuteMode == CommuteType.MORNING)
                "오늘도 힘찬 하루 보내세요! 출발 준비가 완료되었습니다."
            else
                "오늘 하루도 정말 수고 많으셨습니다. 안전하게 귀가하세요."
            binding.tvCommutePrompt.text = "🌟 패턴 학습 완료: $greeting"
            binding.tvCommutePrompt.visibility = View.VISIBLE
            binding.btnConfirmCommute.visibility = View.GONE
            binding.tvStatusBadge.text = "스마트 자동 케어 활성"

            binding.layoutQuickReplyCard.visibility = View.GONE
            voiceManager.speak("반갑습니다. $greeting")
        } else {
            val prompt = commuteDetector.evaluateCommuteIntent(currentLat, currentLon)
            if (prompt != null) {
                binding.tvCommutePrompt.text = "💡 스마트 주행 감지: ${prompt.message}"
                binding.tvCommutePrompt.visibility = View.VISIBLE
                binding.btnConfirmCommute.visibility = View.VISIBLE

                showQuickReplyPanel(
                    question = "📢 \"${prompt.message}\"",
                    yesLabel = "네, 맞춤 출근 시작",
                    noLabel = "아닙니다 (일반 주행)",
                    altLabel = "목적지 변경",
                    onYes = { executeCommuteRoutine(prompt) },
                    onNo = { dismissQuickReply() },
                    onAlt = { toggleTravelMode() }
                )

                val spokenQuestion = if (prompt.commuteType == CommuteType.MORNING) {
                    "현대자동차 양재 본사로 출근 중이십니까? 말씀하시거나 패널을 눌러주세요."
                } else {
                    "자택으로 퇴근 중이십니까? 맞춤 프리컨디셔닝을 시작할까요?"
                }
                voiceManager.speak(spokenQuestion)

                binding.btnConfirmCommute.setOnClickListener {
                    executeCommuteRoutine(prompt)
                }
            } else {
                binding.tvCommutePrompt.visibility = View.GONE
                binding.btnConfirmCommute.visibility = View.GONE
                binding.layoutQuickReplyCard.visibility = View.GONE
            }
        }
    }

    /**
     * @brief 출퇴근 맞춤 프리컨디셔닝 및 루틴을 실행합니다.
     * @param prompt 감지된 출퇴근 정보
     */
    private fun executeCommuteRoutine(prompt: CommutePrompt) {
        currentCommuteMode = prompt.commuteType
        binding.tvStatusBadge.text = if (prompt.commuteType == CommuteType.MORNING) "출근 집중 모드 가동 중" else "퇴근 릴랙스 모드 가동 중"
        binding.tvCommutePrompt.text = "✅ ${prompt.destination.name} 경로 설정 및 맞춤 케어 루틴이 활성화되었습니다."
        binding.btnConfirmCommute.visibility = View.GONE
        binding.layoutQuickReplyCard.visibility = View.GONE

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
        binding.tvAiBriefing.text = "🤖 탑승자 맞춤 AI 브리핑 생성 중 (날씨, 캘린더, 메일 분석 중)..."
        binding.btnVoiceReplay.visibility = View.GONE

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
            binding.tvAiBriefing.text = briefing
            binding.btnVoiceReplay.visibility = View.VISIBLE
            appendLog("AI 브리핑 생성 완료 (GPT-4o-mini)")

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

        binding.tvAudioInfo.text = "🎵 ${track.title} - ${track.artist} (${track.contextTag})\n[EQ: Bass ${bassSign}%.1fdB, Mid +%.1fdB, Treble ${trebleSign}%.1fdB]".format(
            preset.bassGainDb,
            preset.midGainDb,
            preset.trebleGainDb
        )
        binding.tvAcousticBasis.text = "🔬 음향학적 근거: ${preset.scientificBasis}"
    }

    /**
     * @brief 그랜저(GN7) 앰비언트 라이트 색상 동기화
     * @return Unit
     */
    private fun updateLightingEnvironment() {
        val condition = currentWeather?.conditionTag ?: WeatherConditionTag.CLEAR
        val preset = AmbientLightingEngine.determineLightingPreset(currentCommuteMode, condition)
        currentLightingPreset = preset

        binding.tvLightingInfo.text = "💡 무드램프: ${preset.colorNameKo} (${preset.hexString}) [${preset.presetName}]"
        binding.tvLightingBasis.text = "🔬 조명공학 근거: ${preset.scientificBasis}\n[논문: ${preset.referencePaper}]"

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
     */
    private fun onAcceptNewsSummary(newsItem: NewsItem) {
        newsEngine.recordUserFeedback(newsItem, true)
        binding.tvNewsSummary.text = "🎙️ [3줄 요약]:\n${newsItem.summary}\n\n(✨ 선호도 학습 완료: '${newsItem.category}' 가중치 상승)"
        binding.btnNewsAccept.visibility = View.GONE
        binding.btnNewsDecline.visibility = View.GONE
        Toast.makeText(this, "선호 뉴스 카테고리로 학습되었습니다.", Toast.LENGTH_SHORT).show()
        appendLog("사용자 관심사 학습: [${newsItem.category}] 선호도 +3")

        voiceManager.speak("선택하신 ${newsItem.category} 뉴스 요약입니다. ${newsItem.summary}")
    }

    /**
     * @brief 뉴스 요약 거절 시 피드백 학습
     * @param newsItem 거절 대상 뉴스 아이템
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
        binding.tvQuickReplyQuestion.text = question
        binding.btnQuickReplyYes.text = yesLabel
        binding.btnQuickReplyYes.setOnClickListener { onYes() }

        binding.btnQuickReplyNo.text = noLabel
        binding.btnQuickReplyNo.setOnClickListener { onNo() }

        if (altLabel != null && onAlt != null) {
            binding.btnQuickReplyAlt.text = altLabel
            binding.btnQuickReplyAlt.visibility = View.VISIBLE
            binding.btnQuickReplyAlt.setOnClickListener { onAlt() }
        } else {
            binding.btnQuickReplyAlt.visibility = View.GONE
        }

        binding.layoutQuickReplyCard.visibility = View.VISIBLE
    }

    /**
     * @brief 퀵 리플라이 패널을 닫습니다.
     */
    private fun dismissQuickReply() {
        binding.layoutQuickReplyCard.visibility = View.GONE
        voiceManager.speak("일반 주행 모드를 유지합니다.")
    }

    /**
     * @brief 사용자의 수동 공조 온도 조절을 학습 엔진에 피드백함.
     * @param delta 온도 변화량 (+0.5 또는 -0.5)
     */
    private fun adjustTargetTemperature(delta: Float) {
        currentTargetTemp += delta
        currentTargetTemp = (currentTargetTemp * 10).toInt() / 10.0f
        binding.tvTargetTempDisplay.text = "목표 공조: ${currentTargetTemp}℃ (실시간 수동 조절)"

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
            binding.tvTravelBanner.text = "🏖️ [여행 모드 활성화 - ${rec.destinationName}]\n${rec.travelBriefingMessage}\n\n📍 꼭 가봐야 할 명소 & 맛집:\n" +
                    rec.topPois.joinToString("\n") { "• [${it.category}] ${it.name}: ${it.description}" }
            binding.tvTravelBanner.visibility = View.VISIBLE
            binding.btnTriggerTravelMode.text = "출퇴근 일상 모드로 복귀"
            binding.tvStatusBadge.text = "주말 힐링 드라이브 모드"

            voiceManager.speak("속초 설악 리조트로 여행을 떠나시나요? 힐링 음악과 목적지 주변 꼭 봐야 할 명소를 준비했습니다.")
        } else {
            binding.tvTravelBanner.visibility = View.GONE
            binding.btnTriggerTravelMode.text = "모의 목적지: 주말 콘도/리조트 여행 모드 시연"
            binding.tvStatusBadge.text = "출퇴근 케어 에이전트 대기 중"
            voiceManager.speak("출퇴근 케어 일상 모드로 복귀합니다.")
        }
        updateAcousticEnvironment()
    }

    private fun appendLog(msg: String) {
        Log.i(TAG, msg)
        runOnUiThread {
            val current = binding.tvLogConsole.text.toString()
            binding.tvLogConsole.text = "• $msg\n$current"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.stopLocationTracking()
        voiceManager.release()
        vehicleManager.release()
    }
}
