package ai.pleos.commutecare.domain.ai

import ai.pleos.commutecare.data.weather.RealtimeWeather
import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.calendar.WorkEmail
import ai.pleos.commutecare.domain.calendar.WorkEvent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 하이브리드 AI 브리핑 엔진
 * - 온라인 시: Gemini API를 호출하여 날씨, 일정, 중요 메일을 자연스러운 구어체 비서 브리핑으로 생성
 * - 오프라인/키 미입력 시: 안전한 로컬 룰베이스 폴백 브리핑 제공
 */
object AiBriefingEngine {
    private const val TAG = "AiBriefingEngine"

    // 사용자가 API 키를 설정하거나 환경변수/설정창에서 주입 가능 (미설정 시 Rule-based 자동 폴백)
    var apiKey: String = ""

    suspend fun generateBriefing(
        commuteType: CommuteType,
        weather: RealtimeWeather?,
        events: List<WorkEvent>,
        emails: List<WorkEmail>
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isNotBlank()) {
            try {
                val aiResponse = callGeminiApi(commuteType, weather, events, emails, apiKey)
                if (aiResponse.isNotBlank()) return@withContext aiResponse
            } catch (e: Exception) {
                Log.w(TAG, "Gemini API 호출 실패, 로컬 룰베이스로 폴백: ${e.message}")
            }
        }
        return@withContext generateFallbackBriefing(commuteType, weather, events, emails)
    }

    private fun callGeminiApi(
        commuteType: CommuteType,
        weather: RealtimeWeather?,
        events: List<WorkEvent>,
        emails: List<WorkEmail>,
        key: String
    ): String {
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$key"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 5000
            readTimeout = 5000
        }

        val promptText = buildString {
            append("너는 현대자동차 프리미엄 세단(그랜저 GN7/제네시스)의 탑승자 전용 AI 브리핑 에이전트야. ")
            append("차량 탑승자가 운전에 집중할 수 있도록 아래 정보를 바탕으로 편안하고 신뢰감 있는 3문장 분량의 구어체 음성 브리핑 멘트를 작성해줘.\n\n")
            append("[상황]: ${if (commuteType == CommuteType.MORNING) "출근길" else "퇴근길"}\n")
            append("[날씨]: ${weather?.description ?: "기온 14도, 맑음"}\n")
            append("[주요 일정]: ${events.joinToString { "${it.startTime} ${it.title} (${it.location})" }}\n")
            append("[중요 미확인 메일]: ${emails.filter { it.isImportant }.joinToString { "${it.sender}: ${it.subject}" }}\n")
        }

        val jsonBody = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", promptText)
                }))
            }))
        }

        conn.outputStream.use { os ->
            os.write(jsonBody.toString().toByteArray())
            os.flush()
        }

        if (conn.responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val root = JSONObject(responseText)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() > 0) {
                val content = candidates.getJSONObject(0).getJSONObject("content")
                val parts = content.getJSONArray("parts")
                if (parts.length() > 0) {
                    return parts.getJSONObject(0).getString("text").trim()
                }
            }
        }
        return ""
    }

    private fun generateFallbackBriefing(
        commuteType: CommuteType,
        weather: RealtimeWeather?,
        events: List<WorkEvent>,
        emails: List<WorkEmail>
    ): String {
        val nextEvent = events.firstOrNull()
        val importantEmail = emails.firstOrNull { it.isImportant }

        return buildString {
            if (commuteType == CommuteType.MORNING) {
                append("좋은 아침입니다. 오늘 날씨는 ${weather?.description ?: "쾌적함"} 상태입니다. ")
                if (nextEvent != null) {
                    append("첫 번째 일정은 ${nextEvent.startTime}에 시작되는 '${nextEvent.title}'입니다. ")
                }
                if (importantEmail != null) {
                    append("출근 전 '${importantEmail.sender}'님으로부터 '${importantEmail.subject}' 관련 중요 메일이 도착했습니다.")
                }
            } else {
                append("오늘 하루도 고생 많으셨습니다. ")
                if (weather != null && weather.precipitationType > 0) {
                    append("현재 비가 내리고 있으니 안전 거리를 유지하시고 편안한 귀가길 되세요.")
                } else {
                    append("저녁 날씨는 온화하며, 집까지 안전하게 모시겠습니다.")
                }
            }
        }
    }
}
