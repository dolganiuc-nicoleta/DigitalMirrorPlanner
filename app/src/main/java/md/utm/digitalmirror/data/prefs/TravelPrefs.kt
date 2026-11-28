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

    // același mod în ambele sensuri: Acasă → Facultate și Facultate → Acasă
    private fun routeKey(a: Long, b: Long) = "route_${minOf(a, b)}_${maxOf(a, b)}"

    /** Modul ales pentru un anumit drum (de ex. Acasă ↔ Facultate cu autobuzul); null = modul obișnuit. */
    fun routeMode(context: Context, from: Long, to: Long): TravelEstimator.Mode? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(routeKey(from, to), null)
            ?.let { name -> TravelEstimator.Mode.entries.firstOrNull { it.name == name } }

    fun setRouteMode(context: Context, from: Long, to: Long, mode: TravelEstimator.Mode?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (mode == null) remove(routeKey(from, to)) else putString(routeKey(from, to), mode.name)
        }.apply()
    }

    /** Toate modurile alese pe drumuri, pentru copia de rezervă: „route_3_7” → „TRANSIT”. */
    fun routeModes(context: Context): Map<String, String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all
            .filterKeys { it.startsWith("route_") }
            .mapValues { it.value.toString() }

    /** Înlocuiește toate modurile pe drumuri (la restaurare; o hartă goală le șterge, la ieșirea din cont). */
    fun setRouteModes(context: Context, modes: Map<String, String>) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.edit().apply {
            prefs.all.keys.filter { it.startsWith("route_") }.forEach(::remove)
            modes.filterKeys { it.startsWith("route_") }.forEach { (k, v) -> putString(k, v) }
        }.apply()
    }

    /** Modul cu care se planifică drumul: cel ales pentru el, altfel cel obișnuit. */
    fun modeFor(context: Context, from: Long, to: Long): TravelEstimator.Mode =
        routeMode(context, from, to) ?: mode(context)

    /** Locul de unde pleci de obicei (acasă); null = nealeasă. */
    fun homePlaceId(context: Context): Long? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(KEY_HOME, -1L).takeIf { it > 0 }

    fun setHomePlaceId(context: Context, id: Long?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(KEY_HOME, id ?: -1L).apply()
    }

    /** Variantele propuse pentru „Cât îți ia să ieși din casă?”. */
    val PREP_CHOICES = listOf(5, 10, 15, 20, 30)
    const val DEFAULT_PREP_MINUTES = 15

    /** Cât îți ia să te pregătești de plecare (îmbrăcat, geantă), înainte de drumul de acasă. */
    fun prepMinutes(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(KEY_PREP, DEFAULT_PREP_MINUTES)

    fun setPrepMinutes(context: Context, minutes: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt(KEY_PREP, minutes.coerceIn(0, 120)).apply()
    }

    private const val KEY_PREP = "prep_minutes"
}