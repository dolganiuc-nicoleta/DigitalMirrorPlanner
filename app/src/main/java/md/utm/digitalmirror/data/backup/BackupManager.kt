package md.utm.digitalmirror.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.reminder.ReminderPrefs
import md.utm.digitalmirror.reminder.ReminderScheduler
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import md.utm.digitalmirror.ui.theme.Accent
import md.utm.digitalmirror.ui.theme.ThemeSettings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/**
 * Copie de rezervă completă: toate tabelele din baza de date, plus câteva setări.
 *
 * Diferă de exportul pentru cercetare (DataExporter): acela e anonimizat și nu se poate
 * reîncărca; copia aceasta conține tot și se poate restaura pe același telefon sau pe altul.
 *
 * Tabelele se citesc și se scriu generic, după numele coloanelor. Așa, când adăugăm o coloană
 * sau un tabel nou, copia le include fără să modificăm acest fișier. Id-urile se păstrează,
 * deci legăturile dintre tabele (activitate ↔ categorie ↔ sesiuni) rămân intacte.
 */
object BackupManager {

    private const val FORMAT = "DigitalMirrorPlanner-backup"
    private const val FORMAT_VERSION = 1

    class BackupException(message: String) : Exception(message)

    /** Ce conține un fișier de copie, arătat utilizatorului înainte să confirme restaurarea. */
    class Preview internal constructor(
        val createdAt: String,
        val counts: Map<String, Int>,
        internal val root: JSONObject
    ) {
        /** De exemplu „124 activități · 5 categorii · 30 reflecții”. */
        fun describe(): String {
            val names = listOf(
                "activity" to "activități",
                "category" to "categorii",
                "work_session" to "sesiuni de cronometru",
                "reflection" to "reflecții",
                "daily_activity" to "zile cu pași și somn",
                "session_usage" to "sesiuni cu timp pe telefon",
                "goal" to "obiective",
                "time_capsule" to "capsule"
            )
            val lower = counts.mapKeys { it.key.lowercase() }
            return names
                .mapNotNull { (table, label) -> lower[table]?.takeIf { it > 0 }?.let { "$it $label" } }
                .joinToString(" · ")
                .ifEmpty { "nicio dată" }
        }
    }

    fun suggestedFileName(): String = "DigitalMirror-copie-${LocalDate.now()}.json"

    /** Salvează copia în fișierul ales de utilizator. Returnează numărul total de rânduri. */
    suspend fun create(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val (text, rows) = createJson(context)
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw BackupException("Nu am putut scrie fișierul.")
        out.bufferedWriter().use { it.write(text) }
        rows
    }

    /** Copia ca text JSON (pentru fișier sau pentru cloud) și numărul de rânduri. */
    suspend fun createJson(context: Context): Pair<String, Int> = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val sdb = db.openHelper.readableDatabase

        val tables = JSONObject()
        var rows = 0
        for (table in tableNames(sdb)) {
            val array = dump(sdb, table)
            tables.put(table, array)
            rows += array.length()
        }

        val root = JSONObject()
            .put("format", FORMAT)
            .put("formatVersion", FORMAT_VERSION)
            .put("dbVersion", sdb.version)
            .put("createdAt", Instant.now().toString())
            .put("prefs", JSONObject()
                .put("onboardingDone", OnboardingPrefs.isDone(context))
                .put("pattern", OnboardingPrefs.pattern(context).name)
                .put("reminderEnabled", ReminderPrefs.isEnabled(context))
                .put("reminderMinute", ReminderPrefs.minuteOfDay(context))
                .put("accent", ThemeSettings.accent.name))
            .put("tables", tables)

        root.toString() to rows
    }

    /** Citește și verifică un fișier de copie, fără să schimbe nimic în aplicație. */
    suspend fun read(context: Context, uri: Uri): Preview = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw BackupException("Nu am putut citi fișierul.")
        parse(context, text)
    }

    /** Verifică un text de copie (din fișier sau din cloud), fără să schimbe nimic în aplicație. */
    suspend fun parse(context: Context, text: String): Preview = withContext(Dispatchers.IO) {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw BackupException("Fișierul nu este o copie de rezervă validă.")
        }
        if (root.optString("format") != FORMAT) {
            throw BackupException("Fișierul nu este o copie făcută de această aplicație.")
        }

        val current = AppDatabase.get(context).openHelper.readableDatabase.version
        val backupVersion = root.optInt("dbVersion", -1)
        // o copie mai veche se poate restaura (tabelele sau coloanele noi rămân goale sau implicite);
        // una mai nouă nu, pentru că aplicația nu știe ce conține
        if (backupVersion < 1 || backupVersion > current) {
            throw BackupException(
                "Copia e dintr-o versiune mai nouă a aplicației (baza de date $backupVersion, acum $current). Actualizează aplicația."
            )
        }

        val tables = root.optJSONObject("tables")
            ?: throw BackupException("Fișierul nu conține date.")
        val counts = tables.keys().asSequence().associateWith { tables.getJSONArray(it).length() }
        Preview(root.optString("createdAt"), counts, root)
    }

    /** Înlocuiește toate datele de acum cu cele din copie. Totul sau nimic: la eroare nu se schimbă nimic. */
    suspend fun restore(context: Context, preview: Preview) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val sdb = db.openHelper.writableDatabase
        val tables = preview.root.getJSONObject("tables")

        db.runInTransaction(Runnable {
            // legăturile dintre tabele se verifică abia la final, deci ordinea nu contează
            sdb.execSQL("PRAGMA defer_foreign_keys = ON")
            val names = tableNames(sdb)
            names.forEach { sdb.execSQL("DELETE FROM `$it`") }
            for (table in names) {
                val rows = tables.optJSONArray(table) ?: continue
                val columns = columnNames(sdb, table)
                for (i in 0 until rows.length()) {
                    val row = rows.getJSONObject(i)
                    val values = ContentValues()
                    for (column in columns) {
                        if (!row.has(column)) continue
                        put(values, column, row.get(column))
                    }
                    sdb.insert(table, SQLiteDatabase.CONFLICT_REPLACE, values)
                }
            }
        })

        // setările
        preview.root.optJSONObject("prefs")?.let { p ->
            if (p.optBoolean("onboardingDone", false)) {
                runCatching { OnboardingPrefs.Pattern.valueOf(p.getString("pattern")) }
                    .getOrNull()?.let { OnboardingPrefs.save(context, it) }
            }
            ReminderPrefs.save(
                context,
                p.optBoolean("reminderEnabled", true),
                p.optInt("reminderMinute", ReminderPrefs.DEFAULT_MINUTE)
            )
            // culoarea aplicației urmează contul (copiile mai vechi nu o au, atunci rămâne cea de acum)
            runCatching { Accent.valueOf(p.getString("accent")) }
                .getOrNull()?.let { ThemeSettings.setAccent(context, it) }
        }

        // alarmele și reminderul de seară trebuie programate din nou pentru datele restaurate
        ReminderScheduler.schedule(context, replaceExisting = true)
        ActivityReminders.rescheduleAll(context)
    }

    // ---- ajutătoare ----

    private fun tableNames(sdb: SupportSQLiteDatabase): List<String> {
        val names = mutableListOf<String>()
        sdb.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%' AND name <> 'room_master_table'"
        ).use { while (it.moveToNext()) names += it.getString(0) }
        return names
    }

    private fun columnNames(sdb: SupportSQLiteDatabase, table: String): List<String> {
        val names = mutableListOf<String>()
        sdb.query("PRAGMA table_info(`$table`)").use {
            val nameIndex = it.getColumnIndexOrThrow("name")
            while (it.moveToNext()) names += it.getString(nameIndex)
        }
        return names
    }

    private fun dump(sdb: SupportSQLiteDatabase, table: String): JSONArray {
        val array = JSONArray()
        sdb.query("SELECT * FROM `$table`").use { c ->
            val names = c.columnNames
            while (c.moveToNext()) {
                val row = JSONObject()
                for (i in names.indices) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> row.put(names[i], JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> row.put(names[i], c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> row.put(names[i], c.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> row.put(names[i], c.getString(i))
                        else -> Unit   // nu avem coloane binare
                    }
                }
                array.put(row)
            }
        }
        return array
    }

    private fun put(values: ContentValues, column: String, value: Any) {
        when (value) {
            JSONObject.NULL -> values.putNull(column)
            is Int -> values.put(column, value.toLong())
            is Long -> values.put(column, value)
            is Double -> values.put(column, value)
            is Boolean -> values.put(column, if (value) 1L else 0L)
            else -> values.put(column, value.toString())
        }
    }
}