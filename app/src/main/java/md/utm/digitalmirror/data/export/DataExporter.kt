package md.utm.digitalmirror.data.export

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/**
 * Exportă toate datele aplicației într-un fișier JSON, pentru analiza din teză.
 *
 * Varianta anonimizată (implicită) nu conține nimic scris liber de utilizator:
 *  - titlurile activităților sunt înlocuite cu un cod („t-3f9a2c1b07”), același pentru aceeași activitate,
 *    ca analiza să poată grupa „Laborator broker” fără să știe cum se numește;
 *  - din reflecții rămâne doar nota 1–5, fără text.
 * Varianta completă (backup personal) include și titlurile, notițele și textul reflecțiilor.
 */
object DataExporter {

    const val SCHEMA_VERSION = 1

    private const val PREFS = "study_prefs"
    private const val KEY_PARTICIPANT = "participant_id"
    private const val KEY_SALT = "title_salt"

    /** ID-ul anonim al acestui telefon, de forma P-7K2Q; se generează o singură dată. */
    fun participantId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_PARTICIPANT, null)?.let { return it }
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"   // fără 0/O și 1/I, ca să nu fie confundate
        val random = SecureRandom()
        val id = "P-" + (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        prefs.edit().putString(KEY_PARTICIPANT, id).apply()
        return id
    }

    fun suggestedFileName(context: Context, full: Boolean): String =
        "digitalmirror-${participantId(context)}-${LocalDate.now()}" + (if (full) "-complet" else "") + ".json"

    /** Scrie exportul în fișierul ales de utilizator. Întoarce numărul de activități exportate. */
    suspend fun export(context: Context, uri: Uri, full: Boolean): Int = withContext(Dispatchers.IO) {
        val (json, count) = build(context, full)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(json.toString(2).toByteArray(Charsets.UTF_8))
        } ?: error("Fișierul nu poate fi deschis")
        count
    }

    private suspend fun build(context: Context, full: Boolean): Pair<JSONObject, Int> {
        val db = AppDatabase.get(context)
        val activities = db.activityDao().getAll()
        val sessions = db.workSessionDao().getAll().groupBy { it.activityId }
        val categories = db.categoryDao().observeAll().first()
        val daily = db.dailyActivityDao().observeAll().first().sortedBy { it.date }
        val reflections = db.reflectionDao().observeAll().first().sortedBy { it.date }
        val salt = salt(context)

        val root = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("exportedAt", Instant.now().toString())
            .put("anonymized", !full)
            .put("participantId", participantId(context))
            .put("profile", JSONObject()
                .put("pattern", OnboardingPrefs.pattern(context).name)
                .put("priorRatio", OnboardingPrefs.priorRatio(context)))
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("androidSdk", Build.VERSION.SDK_INT))

        root.put("categories", JSONArray().apply {
            categories.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name)) }
        })

        root.put("activities", JSONArray().apply {
            activities.forEach { a ->
                val o = JSONObject()
                    .put("id", a.id)
                    .put("date", a.date)
                    .put("weekday", LocalDate.parse(a.date).dayOfWeek.value)   // 1 = luni … 7 = duminică
                    .put("categoryId", a.categoryId ?: JSONObject.NULL)
                    .put("titleId", titleId(a.titleKey ?: a.title, salt))
                    .put("state", a.state)
                    .put("plannedStartMinute", a.plannedStartMinute ?: JSONObject.NULL)
                    .put("userGuessMinutes", a.userGuessMinutes ?: JSONObject.NULL)          // M0
                    .put("estimatedMinutes", a.estimatedMinutes ?: JSONObject.NULL)          // M3
                    .put("estimatedLowMinutes", a.estimatedLowMinutes ?: JSONObject.NULL)
                    .put("estimatedHighMinutes", a.estimatedHighMinutes ?: JSONObject.NULL)
                    .put("plannedMinutes", a.userEstimatedMinutes ?: JSONObject.NULL)
                    .put("actualMinutes", a.actualMinutes ?: JSONObject.NULL)
                    .put("actualSource", a.actualSource ?: JSONObject.NULL)
                    .put("postponedCount", a.postponedCount)
                    .put("createdAt", a.createdAt)
                    .put("sessions", JSONArray().apply {
                        sessions[a.id].orEmpty().forEach { s ->
                            put(JSONObject()
                                .put("startedAt", s.startedAt)
                                .put("endedAt", s.endedAt ?: JSONObject.NULL))
                        }
                    })
                if (full) {
                    o.put("title", a.title)
                    o.put("note", a.note)
                }
                put(o)
            }
        })

        root.put("daily", JSONArray().apply {
            daily.forEach { d ->
                put(JSONObject()
                    .put("date", d.date)
                    .put("steps", d.steps ?: JSONObject.NULL)
                    .put("sleepMinutes", d.sleepMinutes ?: JSONObject.NULL)
                    .put("bedtimeMinute", d.bedtimeMinute ?: JSONObject.NULL)
                    .put("wakeMinute", d.wakeMinute ?: JSONObject.NULL)
                    .put("sleepSource", d.sleepSource ?: JSONObject.NULL))
            }
        })

        root.put("reflections", JSONArray().apply {
            reflections.forEach { r ->
                val o = JSONObject()
                    .put("date", r.date)
                    .put("moodScore", r.moodScore)
                if (full) {
                    o.put("workedOn", r.workedOn)
                    o.put("blockers", r.blockers)
                    o.put("note", r.note)
                }
                put(o)
            }
        })

        return root to activities.size
    }

    // un cod scurt, mereu același pentru același titlu pe acest telefon, dar din care titlul nu se poate afla
    private fun titleId(titleKey: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((salt + titleKey).toByteArray(Charsets.UTF_8))
        return "t-" + digest.take(5).joinToString("") { "%02x".format(it) }
    }

    // sarea e aleatorie și diferită pe fiecare telefon, ca nimeni să nu poată ghici titlurile încercând variante
    private fun salt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_SALT, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val value = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_SALT, value).apply()
        return value
    }
}