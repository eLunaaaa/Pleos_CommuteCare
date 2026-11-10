package ai.pleos.commutecare.presentation

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.pleos.commutecare.domain.AudioProfile
import ai.pleos.commutecare.domain.CalendarEvent
import ai.pleos.commutecare.domain.CommuteSchedule
import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.NewsBrief
import ai.pleos.commutecare.domain.WeatherInfo
import ai.pleos.commutecare.vehicle.CommuteVehicleManager

class MainActivity : AppCompatActivity() {

    private lateinit var vehicleManager: CommuteVehicleManager

    // UI elements
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvDrivingRestrictedNotice: TextView
    private lateinit var tvWeatherInfo: TextView
    private lateinit var tvScheduleEvents: TextView
    private lateinit var tvNewsSummary: TextView
    private lateinit var tvAudioTrack: TextView
    private lateinit var tvEqPreset: TextView
    private lateinit var tvLogConsole: TextView
    private lateinit var btnToggleCommute: Button
    private lateinit var btnTriggerPrecondition: Button

    private var currentMode = CommuteType.MORNING
    private val commuteSchedule = CommuteSchedule()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContentView())

        initVehicleManager()
        renderCommuteContext(currentMode)
    }

    private fun initVehicleManager() {
        vehicleManager = CommuteVehicleManager(
            context = this,
            onLog = { msg -> appendLog(msg) },
            onSpeedChanged = { speedKmh ->
                tvSpeed.text = "현재 차속: %.1f km/h".format(speedKmh)
            },
            onRestrictionChanged = { isRestricted ->
                tvDrivingRestrictedNotice.visibility = if (isRestricted) View.VISIBLE else View.GONE
            }
        )
        vehicleManager.connect()
    }

    private fun renderCommuteContext(type: CommuteType) {
        val weather = WeatherInfo()
        tvWeatherInfo.text = "📍 ${weather.location}\n온도: ${weather.currentTemp}℃ · ${weather.condition}\n미세먼지: ${weather.airQuality}"

        if (type == CommuteType.MORNING) {
            tvStatusBadge.text = "출근 모드 (오전 08:00 기준)"
            val morningEvents = listOf(
                CalendarEvent("09:30", "모빌리티 SW 아키텍처 스탠드업", "사옥 11층 회의실 A"),
                CalendarEvent("14:00", "Pleos 커넥티드 카 신규 기능 리뷰", "온라인")
            )
            tvScheduleEvents.text = morningEvents.joinToString("\n") { "• [${it.time}] ${it.title} (${it.location})" }

            val morningNews = NewsBrief(
                category = "테크 & 모빌리티",
                title = "현대차그룹 SDV 전환 가속화 및 OS 통합",
                summary = "인포테인먼트와 차량 제어를 유기적으로 결합한 커넥티드 서비스 확대..."
            )
            tvNewsSummary.text = "📰 [${morningNews.category}] ${morningNews.title}\n→ ${morningNews.summary}"

            val audio = AudioProfile(
                commuteType = CommuteType.MORNING,
                playlistName = "에너지 업 출근길 믹스",
                trackTitle = "Viva La Vida",
                artist = "Coldplay",
                eqPresetName = "모닝 에너지 (보컬 & 베이스 강조)",
                bassGain = 4.5f,
                midGain = 2.0f,
                trebleGain = 3.0f
            )
            tvAudioTrack.text = "🎵 ${audio.trackTitle} - ${audio.artist} (${audio.playlistName})"
            tvEqPreset.text = "EQ: ${audio.eqPresetName} [Bass +${audio.bassGain}dB, Treble +${audio.trebleGain}dB]"
            btnToggleCommute.text = "퇴근 모드로 전환"
        } else {
            tvStatusBadge.text = "퇴근 모드 (오후 18:30 기준)"
            val eveningEvents = listOf(
                CalendarEvent("19:30", "동기들과 저녁 약속", "강남역")
            )
            tvScheduleEvents.text = eveningEvents.joinToString("\n") { "• [${it.time}] ${it.title} (${it.location})" }

            val eveningNews = NewsBrief(
                category = "퇴근길 시사 브리핑",
                title = "오늘의 주요 경제 및 라이프스타일 트렌드",
                summary = "퇴근 시간 수도권 주요 도로 원활, 저녁 기온 쾌적 수준 유지..."
            )
            tvNewsSummary.text = "📰 [${eveningNews.category}] ${eveningNews.title}\n→ ${eveningNews.summary}"

            val audio = AudioProfile(
                commuteType = CommuteType.EVENING,
                playlistName = "퇴근길 칠 아웃 재즈",
                trackTitle = "Fly Me to the Moon",
                artist = "Frank Sinatra",
                eqPresetName = "퇴근 릴랙스 (어쿠스틱 & 웜 톤)",
                bassGain = 2.0f,
                midGain = 3.5f,
                trebleGain = 1.0f
            )
            tvAudioTrack.text = "🎵 ${audio.trackTitle} - ${audio.artist} (${audio.playlistName})"
            tvEqPreset.text = "EQ: ${audio.eqPresetName} [Warm Acoustic, Mid +${audio.midGain}dB]"
            btnToggleCommute.text = "출근 모드로 전환"
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
            text = "출근 준비 상태"
            textSize = 14f
            setTextColor(0xFF00E5FF.toInt())
            setPadding(0, 8, 0, 16)
        }
        layout.addView(tvStatusBadge)

        tvDrivingRestrictedNotice = TextView(this).apply {
            text = "⚠️ 주행 중(Distraction Optimization Active) - 화면 간소화 모드"
            setTextColor(0xFFFF5252.toInt())
            textSize = 13f
            visibility = View.GONE
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvDrivingRestrictedNotice)

        tvSpeed = TextView(this).apply {
            text = "현재 차속: 0.0 km/h"
            setTextColor(0xFFBBDEFB.toInt())
            textSize = 15f
            setPadding(0, 0, 0, 24)
        }
        layout.addView(tvSpeed)

        // 섹션 1: 스마트 프리컨디셔닝
        layout.addView(createSectionHeader("🌡️ 스마트 프리컨디셔닝 (차량 공조 & 시트)"))
        val tvTempTarget = TextView(this).apply {
            text = "목표 공조 온도: ${commuteSchedule.targetTemperatureCelsius}℃ | 시트 열선: ${commuteSchedule.seatHeatingLevel}단"
            setTextColor(0xFFECEFF1.toInt())
            setPadding(0, 8, 0, 16)
        }
        layout.addView(tvTempTarget)

        btnTriggerPrecondition = Button(this).apply {
            text = "스마트 프리컨디셔닝 원격 실행"
            setBackgroundColor(0xFF0D47A1.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                vehicleManager.startPreconditioning(
                    commuteSchedule.targetTemperatureCelsius,
                    commuteSchedule.seatHeatingLevel
                ) { success ->
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            if (success) "프리컨디셔닝 시작 완료" else "프리컨디셔닝 요청 실패(에뮬레이터/차량 응답 확인)",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        layout.addView(btnTriggerPrecondition)

        // 섹션 2: 날씨 및 일정 브리핑
        layout.addView(createSectionHeader("⛅ 출퇴근 날씨 및 일정 브리핑"))
        tvWeatherInfo = TextView(this).apply {
            setTextColor(0xFFCFD8DC.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tvWeatherInfo)

        tvScheduleEvents = TextView(this).apply {
            setTextColor(0xFFE0E0E0.toInt())
            textSize = 14f
            setPadding(0, 4, 0, 16)
        }
        layout.addView(tvScheduleEvents)

        // 섹션 3: 시사 & 관심사 뉴스 브리핑
        layout.addView(createSectionHeader("📰 맞춤형 뉴스 브리핑"))
        tvNewsSummary = TextView(this).apply {
            setTextColor(0xFFCFD8DC.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 16)
        }
        layout.addView(tvNewsSummary)

        // 섹션 4: 출퇴근 맞춤 사운드 & EQ
        layout.addView(createSectionHeader("🎧 출퇴근 사운드 & 이퀄라이징"))
        tvAudioTrack = TextView(this).apply {
            setTextColor(0xFF80D8FF.toInt())
            textSize = 14f
            setPadding(0, 8, 0, 4)
        }
        layout.addView(tvAudioTrack)

        tvEqPreset = TextView(this).apply {
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(tvEqPreset)

        btnToggleCommute = Button(this).apply {
            text = "퇴근 모드로 전환"
            setBackgroundColor(0xFF263238.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                currentMode = if (currentMode == CommuteType.MORNING) CommuteType.EVENING else CommuteType.MORNING
                renderCommuteContext(currentMode)
            }
        }
        layout.addView(btnToggleCommute)

        // 시스템 로그 콘솔
        layout.addView(createSectionHeader("💻 시스템 및 SDK 이벤트 로그"))
        tvLogConsole = TextView(this).apply {
            text = "• 시스템 대기 중..."
            setTextColor(0xFF90A4AE.toInt())
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
            textSize = 16f
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
