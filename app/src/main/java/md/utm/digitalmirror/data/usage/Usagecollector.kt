package md.utm.digitalmirror.data.usage

import android.content.Context
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.SessionUsage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Salvează local timpul pe telefon pentru fiecare sesiune de cronometru încheiată.
 *
 * Se apelează când utilizatorul deschide aplicația. Android păstrează evenimentele doar
 * câteva zile, deci căutăm doar sesiunile din ultimele 6 zile care nu au încă datele salvate.
 * Așa nu trebuie să atingem codul care oprește cronometrul din mai multe locuri
 * (ecran, notificare).
 */
object UsageCollector {

    private const val WINDOW_MILLIS = 6 * 24 * 60 * 60 * 1000L
    private const val TOP_APPS = 5

    /** Returnează câte sesiuni noi au fost salvate. */
    suspend fun collectMissing(context: Context): Int {
        if (!UsageReader.hasPermission(context)) return 0

        val db = AppDatabase.get(context)
        val now = System.currentTimeMillis()
        val already = db.sessionUsageDao().collectedStarts().toSet()

        val pending = db.workSessionDao().getAll().filter {
            val end = it.endedAt
            end != null && end > now - WINDOW_MILLIS && it.startedAt !in already
        }

        var saved = 0
        for (s in pending) {
            val end = s.endedAt ?: continue
            val usage = UsageReader.foreground(context, s.startedAt, end) ?: continue
            val top = JSONArray().apply {
                usage.apps.take(TOP_APPS).forEach { put(JSONObject().put("p", it.pkg).put("ms", it.millis)) }
            }
            db.sessionUsageDao().insert(
                SessionUsage(
                    startedAt = s.startedAt,
                    activityId = s.activityId,
                    phoneMillis = usage.totalMillis,
                    topApps = top.toString(),
                    collectedAt = now
                )
            )
            saved++
        }
        return saved
    }

    /** Citește lista salvată în SessionUsage.topApps. */
    fun parseTopApps(json: String): List<UsageReader.AppTime> =
        try {
            val arr = JSONArray(json)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                UsageReader.AppTime(o.getString("p"), o.getLong("ms"))
            }
        } catch (e: Exception) {
            emptyList()
        }
}