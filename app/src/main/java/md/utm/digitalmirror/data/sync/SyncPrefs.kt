package md.utm.digitalmirror.data.sync

import android.content.Context

/** Unde a rămas sincronizarea: ultimul cursor primit de la server și când a mers ultima oară. */
object SyncPrefs {
    private const val FILE = "sync_prefs"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_LAST = "last_sync"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun cursor(context: Context): Long = prefs(context).getLong(KEY_CURSOR, 0)
    fun setCursor(context: Context, value: Long) = prefs(context).edit().putLong(KEY_CURSOR, value).apply()

    /** Ultima sincronizare reușită (milisecunde); 0 = niciodată. */
    fun lastSync(context: Context): Long = prefs(context).getLong(KEY_LAST, 0)
    fun setLastSync(context: Context, value: Long) = prefs(context).edit().putLong(KEY_LAST, value).apply()

    /** La ieșirea din cont: următorul cont pornește de la zero. */
    fun reset(context: Context) = prefs(context).edit().clear().apply()
}
