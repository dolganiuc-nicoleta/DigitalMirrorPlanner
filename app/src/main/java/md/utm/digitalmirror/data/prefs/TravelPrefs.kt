package md.utm.digitalmirror.data.prefs

import android.content.Context
import md.utm.digitalmirror.domain.TravelEstimator

/** Cum se deplasează utilizatorul de obicei (pe jos, transport public, mașină, bicicletă). */
object TravelPrefs {
    private const val FILE = "travel_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_HOME = "home_place"

    fun mode(context: Context): TravelEstimator.Mode =
        TravelEstimator.Mode.fromName(
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        )

    fun setMode(context: Context, mode: TravelEstimator.Mode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }

    /** Locul de unde pleci de obicei (acasă); null = nealeasă. */
    fun homePlaceId(context: Context): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_HOME, -1L).takeIf { it > 0 }

    fun setHomePlaceId(context: Context, id: Long) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(KEY_HOME, id).apply()
    }
}