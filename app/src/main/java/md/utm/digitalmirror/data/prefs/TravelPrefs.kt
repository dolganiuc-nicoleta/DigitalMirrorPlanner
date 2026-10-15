package md.utm.digitalmirror.data.prefs

import android.content.Context
import md.utm.digitalmirror.domain.TravelEstimator

/** Cum se deplasează utilizatorul de obicei (pe jos, transport public, mașină, bicicletă). */
object TravelPrefs {
    private const val FILE = "travel_prefs"
    private const val KEY_MODE = "mode"

    fun mode(context: Context): TravelEstimator.Mode =
        TravelEstimator.Mode.fromName(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        )

    fun setMode(context: Context, mode: TravelEstimator.Mode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }
}