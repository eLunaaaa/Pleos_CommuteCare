/**
 * @file AiBriefingEngine.kt
 * @brief 네트워크에서 브리핑을 생성하고 실패 시 로컬 문장으로 대체한다. 취소는 호출자에게 전파한다.
 */
package ai.pleos.commutecare.domain.ai

import ai.pleos.commutecare.data.weather.RealtimeWeather
import ai.pleos.commutecare.domain.CommuteType
import ai.pleos.commutecare.domain.calendar.WorkEmail
import ai.pleos.commutecare.domain.calendar.WorkEvent
import android.util.Log
import kotlinx.coroutines.CancellationException
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

    /**
     * @brief AI 브리핑을 요청하고 오류 시 로컬 폴백을 반환하되 코루틴 취소는 전파한다.
     * @param commuteType 현재 출퇴근/여행 모드
     * @param weather 최근 확인된 기상 또는 null
     * @param events 브리핑 대상 일정 (현재 시연 데이터)
     * @param emails 브리핑 대상 메일 (현재 시연 데이터)
     * @return AI 응답 또는 로컬 브리핑 문장
     * @throws CancellationException 호출 코루틴이 취소된 경우
     */
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OpenAI API 호출 실패, 로컬 룰베이스로 안전 전환: ${e.message}")
            }
        }
        return@withContext generateFallbackBriefing(commuteType, weather, events, emails)
    }

    /**
     * @brief 기상·일정·메일을 전송하고 응답 문장을 파싱한 뒤 연결을 해제한다.
     * @param commuteType 현재 출퇴근/여행 모드
     * @param weather 최근 확인된 기상 또는 null
     * @param events 브리핑 대상 일정 (현재 시연 데이터)
     * @param emails 브리핑 대상 메일 (현재 시연 데이터)
     * @param key 로컬에서 주입한 API 키
     * @return AI 응답 문장 또는 빈 문자열
     * @throws 네트워크/응답 파싱 예외 (호출자가 로컬 폴백 처리)
     */
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

        try {
            val systemPrompt = "너는 현대자동차 디 올 뉴 그랜저(GN7) 및 제네시스 Pleos IVI 시스템의 탑승자 전용 AI 브리핑 에이전트야. 운전자가 주행 전 편안하게 들을 수 있도록 존댓말과 정중하고 부드러운 톤(TTS용 구어체)으로 3문장 이내의 브리핑 멘트를 작성해."

            val userPrompt = buildString {
                append("상황: ${if (commuteType == CommuteType.MORNING) "출근길" else "퇴근길"}\n")
                append("기상청 날씨: ${weather?.description ?: "날씨 정보 미확인"}\n")
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
        } finally {
            conn.disconnect()
        }
    }

    /**
     * @brief 확인된 입력만 사용해 오프라인 브리핑 문장을 구성한다.
     * @param commuteType 현재 출퇴근/여행 모드
     * @param weather 최근 확인된 기상 또는 null
     * @param events 브리핑 대상 일정 (현재 시연 데이터)
     * @param emails 브리핑 대상 메일 (현재 시연 데이터)
     * @return 로컬 브리핑 문장
     * @throws 없음
     */
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
                append("좋은 아침입니다. 오늘 날씨는 ${weather?.description ?: "정보를 확인할 수 없습니다"}. ")
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
                    append(if (weather == null) "날씨 정보를 확인할 수 없습니다. 안전한 귀가길 되세요." else "안전한 귀가길 되세요.")
                }
            }
        }
    }
}
