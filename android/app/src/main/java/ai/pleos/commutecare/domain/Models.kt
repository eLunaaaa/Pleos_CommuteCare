package ai.pleos.commutecare.domain

data class CommuteSchedule(
    val morningTime: String = "08:00",
    val eveningTime: String = "18:30",
    val preConditioningMinutesBefore: Int = 15,
    val targetTemperatureCelsius: Float = 22.0f,
    val seatHeatingLevel: Int = 2, // 0: Off, 1: Low, 2: Med, 3: High
    val isAutoPreconditioningEnabled: Boolean = true
)

enum class CommuteType {
    MORNING,
    EVENING,
    TRAVEL
}

data class WeatherInfo(
    val location: String = "서울시 서초구 양재동 (현대차 사옥)",
    val currentTemp: Float = 14.5f,
    val condition: String = "맑음 (강수확률 10%)",
    val airQuality: String = "좋음 (PM2.5: 12㎍/㎥)"
)

data class CalendarEvent(
    val time: String,
    val title: String,
    val location: String
)

data class NewsBrief(
    val category: String,
    val title: String,
    val summary: String
)

data class AudioProfile(
    val commuteType: CommuteType,
    val playlistName: String,
    val trackTitle: String,
    val artist: String,
    val eqPresetName: String, // "Vocal & Energy" or "Warm & Acoustic"
    val bassGain: Float,
    val midGain: Float,
    val trebleGain: Float
)
