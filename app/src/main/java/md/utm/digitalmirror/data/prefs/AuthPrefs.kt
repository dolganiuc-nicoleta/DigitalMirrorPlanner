package md.utm.digitalmirror.data.prefs

import android.content.Context

/**
 * Alegerea de la prima pornire: utilizatorul a ales „Continuă fără cont” (CF-04).
 * Dacă s-a autentificat, starea o ține Firebase; aici reținem doar alegerea de a nu avea cont,
 * ca ecranul de autentificare să nu apară din nou la fiecare pornire.
 */
object AuthPrefs {

    private const val FILE = "auth_prefs"
    private const val KEY_SKIPPED = "skipped"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun skipped(context: Context): Boolean = prefs(context).getBoolean(KEY_SKIPPED, false)

    fun setSkipped(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SKIPPED, value).apply()
    }
}