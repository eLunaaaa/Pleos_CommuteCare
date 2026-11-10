package ai.pleos.commutecare.data.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class RealtimeWeather(
    val temperatureCelsius: Float,
    val precipitationType: Int, // 0: 없음, 1: 비, 2: 비/눈, 3: 눈, 5: 빗방울, 6: 빗방울눈날림, 7: 눈날림
    val precipitation1HourMm: Float,
    val humidityPercent: Int,
    val windSpeedMps: Float,
    val description: String,
    val conditionTag: WeatherConditionTag
)

enum class WeatherConditionTag {
    CLEAR,
    RAINY,
    SNOWY,
    COLD_WINTER,
    HOT_SUMMER,
    MODERATE
}

/**
 * 기상청 초단기실황 API 연동 및 위경도->기상청 격자(nx, ny) Lambert 투영 변환기
 */
object KmaWeatherClient {
    private const val TAG = "KmaWeatherClient"
    private const val API_ENDPOINT = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst"
    private const val SERVICE_KEY = "b51c7376c6fecd0f8af62dd1d77661ebca07c213d782eeb864f34de1cc73edaa"

    /**
     * 위경도 기반 기상청 초단기실황 조회
     */
    suspend fun fetchCurrentWeather(latitude: Double, longitude: Double): RealtimeWeather? = withContext(Dispatchers.IO) {
        try {
            val (nx, ny) = convertToGrid(latitude, longitude)
            val cal = Calendar.getInstance()

            // 기상청 초단기실황은 매시 40분 이후 발표되므로, 40분 이전이면 1시간 전 발표 데이터를 조회
            if (cal.get(Calendar.MINUTE) < 45) {
                cal.add(Calendar.HOUR_OF_DAY, -1)
            }
            val baseDate = SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(cal.time)
            val baseTime = SimpleDateFormat("HH00", Locale.KOREA).format(cal.time)

            val urlString = "$API_ENDPOINT?serviceKey=$SERVICE_KEY&numOfRows=15&pageNo=1&dataType=JSON&base_date=$baseDate&base_time=$baseTime&nx=$nx&ny=$ny"
            val url = URL(urlString)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
            }

            if (conn.responseCode != 200) {
                Log.w(TAG, "기상청 API 응답 오류: code=${conn.responseCode}")
                return@withContext null
            }

            val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val root = JSONObject(responseText)
            val header = root.getJSONObject("response").getJSONObject("header")
            if (header.getString("resultCode") != "00") {
                Log.w(TAG, "기상청 결과 실패: ${header.getString("resultMsg")}")
                return@withContext null
            }

            val items = root.getJSONObject("response")
                .getJSONObject("body")
                .getJSONObject("items")
                .getJSONArray("item")

            var temp = 15.0f
            var pty = 0
            var rn1 = 0.0f
            var reh = 50
            var wsd = 1.0f

            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val cat = item.getString("category")
                val obsrValue = item.getString("obsrValue")
                when (cat) {
                    "T1H" -> temp = obsrValue.toFloatOrNull() ?: temp
                    "PTY" -> pty = obsrValue.toIntOrNull() ?: pty
                    "RN1" -> rn1 = obsrValue.toFloatOrNull() ?: rn1
                    "REH" -> reh = obsrValue.toIntOrNull() ?: reh
                    "WSD" -> wsd = obsrValue.toFloatOrNull() ?: wsd
                }
            }

            val conditionTag = when {
                pty in listOf(1, 2, 5, 6) -> WeatherConditionTag.RAINY
                pty in listOf(3, 7) -> WeatherConditionTag.SNOWY
                temp <= 5.0f -> WeatherConditionTag.COLD_WINTER
                temp >= 28.0f -> WeatherConditionTag.HOT_SUMMER
                else -> WeatherConditionTag.CLEAR
            }

            val desc = buildString {
                append("기온 ${temp}℃, 습도 ${reh}%")
                if (pty == 0) append(", 맑음")
                else if (pty in listOf(1, 5)) append(", 강우(${rn1}mm/h)")
                else if (pty in listOf(3, 7)) append(", 눈(${rn1}mm/h)")
                else append(", 눈/비 섞임")
                append(", 풍속 ${wsd}m/s")
            }

            return@withContext RealtimeWeather(
                temperatureCelsius = temp,
                precipitationType = pty,
                precipitation1HourMm = rn1,
                humidityPercent = reh,
                windSpeedMps = wsd,
                description = desc,
                conditionTag = conditionTag
            )
        } catch (e: Exception) {
            Log.e(TAG, "날씨 조회 중 예외 발생: ${e.message}")
            return@withContext null
        }
    }

    /**
     * 기상청 공식 LCC 격자 변환 수식 (기상청 단기예보 활용가이드 공식 C/C++ 알고리즘 이식)
     */
    fun convertToGrid(lat: Double, lon: Double): Pair<Int, Int> {
        val re = 6371.00877 / 5.0
        val slat1 = 30.0 * Math.PI / 180.0
        val slat2 = 60.0 * Math.PI / 180.0
        val olon = 126.0 * Math.PI / 180.0
        val olat = 38.0 * Math.PI / 180.0

        var sn = tan(Math.PI * 0.25 + slat2 * 0.5) / tan(Math.PI * 0.25 + slat1 * 0.5)
        sn = ln(cos(slat1) / cos(slat2)) / ln(sn)
        var sf = tan(Math.PI * 0.25 + slat1 * 0.5)
        sf = sf.pow(sn) * cos(slat1) / sn
        var ro = tan(Math.PI * 0.25 + olat * 0.5)
        ro = re * sf / ro.pow(sn)

        var ra = tan(Math.PI * 0.25 + (lat) * Math.PI / 180.0 * 0.5)
        ra = re * sf / ra.pow(sn)
        var theta = lon * Math.PI / 180.0 - olon
        if (theta > Math.PI) theta -= 2.0 * Math.PI
        if (theta < -Math.PI) theta += 2.0 * Math.PI
        theta *= sn

        val x = floor(ra * sin(theta) + 210.0 / 5.0 + 1.5).toInt()
        val y = floor(ro - ra * cos(theta) + 675.0 / 5.0 + 1.5).toInt()
        return Pair(x, y)
    }
}
