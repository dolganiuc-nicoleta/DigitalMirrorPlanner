package md.utm.digitalmirror.reminder

import android.content.Context

// setările reminder-ului, salvate local în SharedPreferences
object ReminderPrefs {

    private const val FILE = "reminder_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MINUTE = "minute_of_day"

    const val DEFAULT_MINUTE = 21 * 60   // 21:00

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun minuteOfDay(context: Context): Int =
        prefs(context).getInt(KEY_MINUTE, DEFAULT_MINUTE)

    fun save(context: Context, enabled: Boolean, minuteOfDay: Int) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putInt(KEY_MINUTE, minuteOfDay)
            .apply()
    }
}