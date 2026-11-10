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
 * 하이브리드 인공지능 브리핑 엔진
 * - OpenAI GPT-4o-mini 기반 지능형 요약 브리핑 생성
 * - 통신 두절 또는 응답 지연 시 안전한 차량 내 로컬 룰베이스 폴백
 */
object AiBriefingEngine {
    private const val TAG = "AiBriefingEngine"
    private const val OPENAI_ENDPOINT = "https://api.openai.com/v1/chat/completions"

    // 주입된 OpenAI API Key (BuildConfig 또는 외부 주입)
    var openAiKey: String = ai.pleos.commutecare.BuildConfig.OPENAI_API_KEY

    suspend fun generateBriefing(
        commuteType: CommuteType,
        weather: RealtimeWeather?,
        events: List<WorkEvent>,
        emails: List<WorkEmail>
    ): String = withContext(Dispatchers.IO) {
        if (openAiKey.isNotBlank()) {
            try {
                val aiResponse = callOpenAiGpt(commuteType, weather, events, emails, openAiKey)
                if (aiResponse.isNotBlank()) return@withContext aiResponse
            } catch (e: Exception) {
                Log.w(TAG, "OpenAI API 호출 실패, 로컬 룰베이스로 안전 전환: ${e.message}")
            }
        }
        return@withContext generateFallbackBriefing(commuteType, weather, events, emails)
    }

    private fun callOpenAiGpt(
        commuteType: CommuteType,
        weather: RealtimeWeather?,
        events: List<WorkEvent>,
        emails: List<WorkEmail>,
        key: String
    ): String {
        val url = URL(OPENAI_ENDPOINT)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
            doOutput = true
            connectTimeout = 6000
            readTimeout = 6000
        }

        val systemPrompt = "너는 현대자동차 디 올 뉴 그랜저(GN7) 및 제네시스 Pleos IVI 시스템의 탑승자 전용 AI 브리핑 에이전트야. 운전자가 주행 전 편안하게 들을 수 있도록 존댓말과 정중하고 부드러운 톤(TTS용 구어체)으로 3문장 이내의 브리핑 멘트를 작성해."

        val userPrompt = buildString {
            append("상황: ${if (commuteType == CommuteType.MORNING) "출근길" else "퇴근길"}\n")
            append("기상청 날씨: ${weather?.description ?: "기온 14.5도, 맑음, 강수 없음"}\n")
            append("오늘 주요 캘린더 일정: ${events.joinToString("; ") { "[${it.startTime}] ${it.title} (${it.location})" }}\n")
            val importantMails = emails.filter { it.isImportant }
            if (importantMails.isNotEmpty()) {
                append("미확인 중요 메일: ${importantMails.joinToString("; ") { "${it.sender}: ${it.subject} (${it.snippet})" }}\n")
            }
            append("요청: 위 기상과 일정, 중요 메일을 유기적으로 연결하여 탑승자에게 맞춤 브리핑을 해줘.")
        }

        val payload = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.7)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
        }

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
            os.flush()
        }

        if (conn.responseCode == 200) {
            val responseText = conn.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
            val root = JSONObject(responseText)
            val choices = root.getJSONArray("choices")
            if (choices.length() > 0) {
                val messageObj = choices.getJSONObject(0).getJSONObject("message")
                return messageObj.getString("content").trim()
            }
        } else {
            val errorText = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
            Log.e(TAG, "OpenAI API 에러 (${conn.responseCode}): $errorText")
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
                append("좋은 아침입니다. 오늘 날씨는 ${weather?.description ?: "쾌적한"} 상태입니다. ")
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
