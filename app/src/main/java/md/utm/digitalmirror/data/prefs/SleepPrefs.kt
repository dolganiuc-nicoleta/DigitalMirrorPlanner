package md.utm.digitalmirror.data.prefs

import android.content.Context
import md.utm.digitalmirror.domain.SleepPlanner

/**
 * Ritmul tău obișnuit (trezire, culcare), spus la configurarea inițială sau în Setări.
 * E doar punctul de pornire: după câteva nopți confirmate, aplicația folosește nopțile tale reale.
 */
object SleepPrefs {
    private const val FILE = "sleep_prefs"
    private const val KEY_WAKE = "wake"
    private const val KEY_BED = "bed"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Ora de trezire aleasă; null = nealeasă (pornim de la 8:00). */
    fun wake(context: Context): Int? = prefs(context).getInt(KEY_WAKE, -1).takeIf { it >= 0 }

    /** Ora de culcare aleasă (minute de la miezul nopții); null = nealeasă (pornim de la 23:00). */
    fun bed(context: Context): Int? = prefs(context).getInt(KEY_BED, -1).takeIf { it >= 0 }

    fun save(context: Context, wake: Int, bed: Int) {
        prefs(context).edit().putInt(KEY_WAKE, wake).putInt(KEY_BED, bed % (24 * 60)).apply()
    }

    fun habit(context: Context): SleepPlanner.Habit {
        val w = wake(context)
        val b = bed(context)
        return if (w != null && b != null) SleepPlanner.Habit.of(w, b) else SleepPlanner.Habit()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
