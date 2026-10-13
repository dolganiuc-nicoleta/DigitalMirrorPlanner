package md.utm.digitalmirror.data.prefs

import android.content.Context

/**
 * Comutatorul „Analiză și recomandări”. Cât e oprit, aplicația nu mai propune durate și ore, nu mai
 * avertizează la supraîncărcare și nu mai arată observații sau statistici de precizie.
 * Activitățile se salvează în continuare, ca la repornire analiza să aibă istoric.
 */
object AnalysisPrefs {

    private const val FILE = "analysis_prefs"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}