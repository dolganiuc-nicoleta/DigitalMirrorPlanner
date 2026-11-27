package md.utm.digitalmirror.data.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.backup.BackupManager
import md.utm.digitalmirror.data.backup.CloudBackup
import md.utm.digitalmirror.data.backup.CloudConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * „✨ Echilibrează”: trimite săptămâna la serverul tău (POST /ai/balance), care o dă mai departe la Claude.
 * Cheia AI stă pe server, nu în aplicație. Pleacă doar titlurile sarcinilor și minutele, nimic despre locuri sau cont.
 */
object AiBalancer {

    data class Item(val id: String, val title: String, val minutes: Int, val movable: Boolean, val deadline: String? = null)

    data class Day(
        val date: String,
        val capacityMinutes: Int,
        val fixedMinutes: Int,
        val dayOff: Boolean,
        val items: List<Item>
    )

    data class Move(val itemId: String, val toDate: String, val reason: String)

    data class Result(val summary: String, val moves: List<Move>)

    class AiException(message: String) : Exception(message)

    /** Butonul apare doar când serverul e configurat. */
    val isAvailable: Boolean get() = CloudConfig.isConfigured

    suspend fun balance(days: List<Day>, wish: String?): Result = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("days", JSONArray().apply {
                days.forEach { d ->
                    put(
                        JSONObject()
                            .put("date", d.date)
                            .put("capacityMinutes", d.capacityMinutes)
                            .put("fixedMinutes", d.fixedMinutes)
                            .put("dayOff", d.dayOff)
                            .put("items", JSONArray().apply {
                                d.items.forEach { i ->
                                    put(
                                        JSONObject()
                                            .put("id", i.id)
                                            .put("title", i.title)
                                            .put("minutes", i.minutes)
                                            .put("movable", i.movable)
                                            .put("deadline", i.deadline ?: JSONObject.NULL)
                                    )
                                }
                            })
                    )
                }
            })
            .put("wish", wish?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)

        val response = try {
            CloudBackup.send("POST", "/ai/balance", body.toString().toByteArray(Charsets.UTF_8))
        } catch (e: BackupManager.BackupException) {
            throw AiException(e.message ?: "Nu pot contacta serverul.")
        }
        val text = String(response.body, Charsets.UTF_8)
        if (response.code !in 200..299) throw AiException(problem(text) ?: "Serverul a răspuns cu eroarea ${response.code}.")

        val json = JSONObject(text)
        val moves = json.optJSONArray("moves") ?: JSONArray()
        Result(
            summary = json.optString("summary"),
            moves = (0 until moves.length()).map { i ->
                val m = moves.getJSONObject(i)
                Move(m.getString("itemId"), m.getString("toDate"), m.optString("reason"))
            }
        )
    }

    // serverul trimite erorile ca ProblemDetails: { "detail": "..." }
    private fun problem(text: String): String? =
        runCatching { JSONObject(text).let { j -> j.optString("detail").ifBlank { j.optString("error") } }.takeIf { it.isNotBlank() } }.getOrNull()
}

/** Acordul pentru AI: o singură dată, cu explicația a ce pleacă din telefon. */
object AiPrefs {
    private const val FILE = "ai_prefs"
    private const val KEY_CONSENT = "consent"

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_CONSENT, false)

    fun setConsent(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_CONSENT, value).apply()
    }
}
