package md.utm.digitalmirror.data.sync

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.backup.BackupManager
import md.utm.digitalmirror.data.backup.CloudBackup
import md.utm.digitalmirror.data.backup.CloudConfig
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.db.SyncSchema
import md.utm.digitalmirror.reminder.ActivityReminders
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sincronizarea cu serverul (POST /sync), ca telefonul și desktopul să vadă aceleași activități.
 *
 *  1. trimite rândurile „de trimis” (updatedAt ≠ syncedAt) și pietrele funerare, fiecare cu versiunea de la care a pornit;
 *  2. primește tot ce s-a schimbat de la ultimul cursor (inclusiv rândurile proprii, cu versiunea nouă);
 *  3. le aplică „în liniște” (fără să le noteze ca schimbări), traducând UUID-urile în id-urile locale;
 *  4. ultima modificare câștigă: un rând local mai nou decât cel venit rămâne și pleacă la următoarea sincronizare.
 */
object SyncEngine {

    private const val TAG = "SyncEngine"

    /** Legăturile dintre tabele: coloana locală → tabela din care e id-ul (se trimit ca UUID: „categoryUid”). */
    private val LINKS = mapOf(
        "activity" to listOf("categoryId" to "category", "weekTaskId" to "week_task"),
        "work_session" to listOf("activityId" to "activity"),
        "week_task" to listOf("categoryId" to "category")
    )

    /** Coloanele de evidență, care nu pleacă în date. */
    private val META = setOf("id", "uid", "updatedAt", "syncedAt", "syncVersion")

    private fun linkKey(column: String) = column.removeSuffix("Id") + "Uid"     // categoryId → categoryUid

    data class Result(val pushed: Int, val pulled: Int, val conflicts: Int)

    private val mutex = Mutex()

    /** Se poate sincroniza doar cu serverul configurat și cu un cont conectat. */
    val isPossible: Boolean get() = CloudConfig.isConfigured && AuthRepository.isSignedIn

    suspend fun sync(context: Context): Result = mutex.withLock {
        withContext(Dispatchers.IO) { run(context.applicationContext) }
    }

    private suspend fun run(context: Context): Result {
        val room = AppDatabase.get(context)
        val db = room.openHelper.writableDatabase
        var pushed = 0
        var pulled = 0
        var conflicts = 0
        var cursor = SyncPrefs.cursor(context)
        val touched = mutableSetOf<Long>()   // activitățile schimbate de pe alt dispozitiv, pentru notificări

        var first = true
        while (true) {
            // doar la prima cerere trimitem schimbările; dacă răspunsul are „more”, mai cerem doar ce vine
            val (changes, sentTombstones) = if (first) collect(db) else JSONArray() to emptyList()
            first = false

            val body = JSONObject().put("since", cursor).put("changes", changes)
            val response = try {
                CloudBackup.send("POST", "/sync", body.toString().toByteArray(Charsets.UTF_8))
            } catch (e: BackupManager.BackupException) {
                throw SyncException(e.message ?: "Nu pot contacta serverul.")
            }
            if (response.code !in 200..299) throw SyncException("Serverul a răspuns cu eroarea ${response.code}.")
            val json = JSONObject(String(response.body, Charsets.UTF_8))

            pushed += changes.length()
            conflicts += json.optInt("conflicts")
            val rows = json.optJSONArray("changes") ?: JSONArray()
            room.runInTransaction {
                SyncSchema.quietly(db) {
                    // pietrele trimise au ajuns: nu mai trebuie păstrate
                    sentTombstones.forEach { (kind, uid) ->
                        db.execSQL("DELETE FROM sync_tombstone WHERE kind = ? AND uid = ?", arrayOf(kind, uid))
                    }
                    pulled += apply(db, rows, touched)
                }
            }
            cursor = json.optLong("cursor", cursor)
            SyncPrefs.setCursor(context, cursor)
            if (!json.optBoolean("more")) break
        }

        if (touched.isNotEmpty()) refreshReminders(context, db, touched)
        SyncPrefs.setLastSync(context, System.currentTimeMillis())
        Log.i(TAG, "sincronizat: trimise $pushed, primite $pulled, conflicte $conflicts")
        return Result(pushed, pulled, conflicts)
    }

    // ---- ce trimitem ----

    private fun collect(db: SupportSQLiteDatabase): Pair<JSONArray, List<Pair<String, String>>> {
        val changes = JSONArray()
        for (table in SyncSchema.TABLES) {
            db.query("SELECT * FROM `$table` WHERE updatedAt != syncedAt AND uid IS NOT NULL").use { c ->
                while (c.moveToNext()) {
                    val data = JSONObject()
                    for (i in 0 until c.columnCount) {
                        val column = c.getColumnName(i)
                        if (column in META && !(table == "day_off" && column == "id")) continue
                        val link = LINKS[table]?.firstOrNull { it.first == column }
                        if (link != null) {
                            val localId = if (c.isNull(i)) null else c.getLong(i)
                            data.put(linkKey(column), localId?.let { uidOf(db, link.second, it) } ?: JSONObject.NULL)
                        } else data.put(column, value(c, i))
                    }
                    changes.put(
                        JSONObject()
                            .put("kind", table)
                            .put("uid", c.getString(c.getColumnIndexOrThrow("uid")))
                            .put("updatedAt", c.getLong(c.getColumnIndexOrThrow("updatedAt")))
                            .put("deleted", false)
                            .put("baseVersion", c.getLong(c.getColumnIndexOrThrow("syncVersion")))
                            .put("data", data)
                    )
                }
            }
        }
        // ștergerile; un rând care există din nou (reinserat) nu se trimite ca șters
        val tombstones = mutableListOf<Pair<String, String>>()
        db.query("SELECT kind, uid, deletedAt, baseVersion FROM sync_tombstone").use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0)
                val uid = c.getString(1)
                tombstones += kind to uid
                if (kind !in SyncSchema.TABLES || exists(db, kind, uid)) continue
                changes.put(
                    JSONObject().put("kind", kind).put("uid", uid).put("updatedAt", c.getLong(2))
                        .put("deleted", true).put("baseVersion", c.getLong(3)).put("data", JSONObject.NULL)
                )
            }
        }
        return changes to tombstones
    }

    private fun value(c: Cursor, i: Int): Any = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        else -> c.getString(i)
    }

    private fun uidOf(db: SupportSQLiteDatabase, table: String, id: Long): String? =
        db.query("SELECT uid FROM `$table` WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }

    private fun idOf(db: SupportSQLiteDatabase, table: String, uid: String): Long? =
        db.query("SELECT id FROM `$table` WHERE uid = ?", arrayOf(uid)).use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun exists(db: SupportSQLiteDatabase, table: String, uid: String): Boolean =
        db.query("SELECT 1 FROM `$table` WHERE uid = ?", arrayOf(uid)).use { it.moveToFirst() }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }

    // ---- ce primim ----

    /** Aplică rândurile venite, în ordinea tabelelor (categoriile înaintea activităților care le folosesc). */
    private fun apply(db: SupportSQLiteDatabase, rows: JSONArray, touched: MutableSet<Long>): Int {
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }
            .filter { it.optString("kind") in SyncSchema.TABLES }
            .sortedBy { SyncSchema.TABLES.indexOf(it.getString("kind")) }
        val columnsByTable = SyncSchema.TABLES.associateWith { columns(db, it) }
        var applied = 0

        for (row in list) {
            val table = row.getString("kind")
            val uid = row.getString("uid")
            val updatedAt = row.getLong("updatedAt")
            val version = row.getLong("version")
            val local = db.query("SELECT updatedAt, syncedAt FROM `$table` WHERE uid = ?", arrayOf(uid)).use {
                if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null
            }

            if (row.optBoolean("deleted")) {
                // șters pe alt dispozitiv; o modificare locală mai nouă câștigă și pleacă la următoarea sincronizare
                if (local != null && local.first <= updatedAt) {
                    if (table == "activity") idOf(db, table, uid)?.let(touched::add)
                    if (table == "work_session") activityOfSession(db, uid)?.let(touched::add)
                    db.delete(table, "uid = ?", arrayOf(uid))
                    applied++
                }
                continue
            }

            if (local != null && local.first > updatedAt) continue   // al nostru e mai nou: rămâne
            val values = toValues(db, table, row.optJSONObject("data") ?: continue, columnsByTable.getValue(table)) ?: continue
            values.put("uid", uid)
            values.put("updatedAt", updatedAt)
            values.put("syncedAt", updatedAt)
            values.put("syncVersion", version)

            if (local == null) {
                db.insert(table, SQLiteDatabase.CONFLICT_IGNORE, values)
            } else {
                db.update(table, SQLiteDatabase.CONFLICT_IGNORE, values, "uid = ?", arrayOf(uid))
            }
            // ecoul propriu (aceleași date, versiune nouă) nu e o schimbare de pe alt dispozitiv
            if (local == null || local.first != updatedAt || local.second != updatedAt) {
                when (table) {
                    "activity" -> idOf(db, table, uid)?.let(touched::add)
                    "work_session" -> activityOfSession(db, uid)?.let(touched::add)
                }
            }
            applied++
        }
        return applied
    }

    /** Datele unui rând, ca valori pentru tabela locală; UUID-urile legăturilor devin id-uri locale. null = nu se poate pune. */
    private fun toValues(db: SupportSQLiteDatabase, table: String, data: JSONObject, columns: Set<String>): ContentValues? {
        val values = ContentValues()
        val links = LINKS[table].orEmpty()
        for (key in data.keys()) {
            val link = links.firstOrNull { linkKey(it.first) == key }
            if (link != null) {
                val linkedUid = data.optString(key).takeIf { !data.isNull(key) && it.isNotBlank() }
                val localId = linkedUid?.let { idOf(db, link.second, it) }
                // o sesiune fără activitate nu poate exista (cheie străină)
                if (table == "work_session" && link.first == "activityId" && localId == null) return null
                if (localId == null) values.putNull(link.first) else values.put(link.first, localId)
                continue
            }
            if (key !in columns || key in META) continue      // câmpuri pe care versiunea asta nu le are
            when (val v = data.get(key)) {
                JSONObject.NULL -> values.putNull(key)
                is Int -> values.put(key, v.toLong())
                is Long -> values.put(key, v)
                is Double -> values.put(key, v)
                is Boolean -> values.put(key, if (v) 1 else 0)
                else -> values.put(key, v.toString())
            }
        }
        return values
    }

    private fun activityOfSession(db: SupportSQLiteDatabase, uid: String): Long? =
        db.query("SELECT activityId FROM work_session WHERE uid = ?", arrayOf(uid)).use { if (it.moveToFirst()) it.getLong(0) else null }

    /**
     * Cronometrul oprit pe desktop, o activitate terminată pe alt dispozitiv: notificările de pe telefon
     * trebuie să urmeze. Scoatem ce nu mai e valabil, apoi refacem ce rulează și ce e planificat.
     */
    private suspend fun refreshReminders(context: Context, db: SupportSQLiteDatabase, activities: Set<Long>) {
        val running = db.query("SELECT activityId FROM work_session WHERE endedAt IS NULL").use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }
        for (id in activities) {
            val state = db.query("SELECT state FROM activity WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
            when {
                state == null || state == "DONE" || state == "PARTIAL" || state == "ABANDONED" || state == "POSTPONED" ->
                    ActivityReminders.onActivityClosed(context, id)
                id !in running -> ActivityReminders.onTimerStopped(context, id)
            }
        }
        ActivityReminders.rescheduleAll(context)
    }

    class SyncException(message: String) : Exception(message)
}
