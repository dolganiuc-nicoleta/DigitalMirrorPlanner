package md.utm.digitalmirror.data.prefs

import android.content.Context

/** Comutatorul „Detectare automată a locului” din Locurile mele. */
object PresencePrefs {
    private const val FILE = "presence_prefs"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}