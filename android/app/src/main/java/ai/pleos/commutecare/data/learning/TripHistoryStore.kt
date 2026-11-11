/**
 * @file TripHistoryStore.kt
 * @brief 로컬 환경설정에서 최근 통근 이력을 읽고 저장한다. 손상된 이력은 안전하게 비운다.
 */
package ai.pleos.commutecare.data.learning

import android.content.Context
import ai.pleos.commutecare.domain.learning.TripLog
import org.json.JSONArray
import org.json.JSONObject

class TripHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences("commute_learning", Context.MODE_PRIVATE)

    /**
     * @brief 마지막으로 저장한 EMA 온도 상태를 복원한다.
     * @return 유효한 선호 온도, 미저장/손상 시 null
     * @throws 없음
     */
    fun loadPreferredTemperature(): Float? = runCatching {
        if (!preferences.contains("preferredTemp")) null
        else preferences.getFloat("preferredTemp", 22f).takeIf { it.isFinite() && it in 16f..30f }
    }.getOrNull()

    /**
     * @brief 통근 이력과 별도로 수동 온도 학습 상태를 보존한다.
     * @param temperature EMA 온도 상태
     * @return Unit
     * @throws 없음
     */
    fun savePreferredTemperature(temperature: Float) {
        preferences.edit().putFloat("preferredTemp", temperature).apply()
    }

    /**
     * @brief 유효한 요일·시각·온도를 갖는 최근 최대 200개 이력을 복원한다.
     * @return 복원 이력 (손상 시 빈 목록)
     * @throws 없음
     */
    fun load(): List<TripLog> = runCatching {
        val rows = JSONArray(preferences.getString("trips", "[]"))
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val day = row.getInt("day")
            val time = row.getInt("time")
            val temp = row.getDouble("temp").toFloat()
            if (day !in 1..7 || time / 100 !in 0..23 || time % 100 !in 0..59 || !temp.isFinite() || temp !in 16f..30f) null
            else TripLog(day, time, temp, row.getBoolean("morning"))
        }.takeLast(200)
    }.getOrDefault(emptyList())

    /**
     * @brief 최근 이력을 JSON으로 직렬화해 비동기 로컬 저장한다.
     * @param history 저장할 통근 이력
     * @return Unit
     * @throws 없음
     */
    fun save(history: List<TripLog>) {
        val rows = JSONArray()
        history.takeLast(200).forEach { trip ->
            rows.put(JSONObject().apply {
                put("day", trip.dayOfWeek)
                put("time", trip.hourMinute)
                put("temp", trip.manualSetTempCelsius)
                put("morning", trip.isMorningCommute)
            })
        }
        preferences.edit().putString("trips", rows.toString()).apply()
    }
}
