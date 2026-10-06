package md.utm.digitalmirror.domain

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Propune durata unei activități pe baza istoricului utilizatorului din aceeași categorie.
 *
 * Ideea: dacă în trecut activitățile dintr-o categorie au durat în total de 1.25 ori
 * mai mult decât estimările, o estimare nouă de 40 min devine o propunere de 50 min.
 * Dacă utilizatorul nu a introdus nicio estimare, se propune durata reală medie.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object DurationEstimator {

    /** Numărul minim de activități finalizate ca propunerea să fie credibilă. */
    const val MIN_SAMPLES = 3

    /** O activitate finalizată din istoric: cât a estimat utilizatorul și cât a durat de fapt. */
    data class Sample(val estimatedMinutes: Int, val actualMinutes: Int)

    data class Suggestion(
        val minutes: Int,          // durata propusă, rotunjită
        val ratio: Double,         // real / estimat pe tot istoricul folosit
        val averageActual: Int,    // durata reală medie
        val sampleSize: Int        // câte activități au stat la baza calculului
    ) {
        /** +24 = durează cu 24% mai mult decât estimările; -10 = cu 10% mai puțin. */
        val deviationPercent: Int
            get() = ((ratio - 1.0) * 100).roundToInt()
    }

    fun suggest(history: List<Sample>, userEstimate: Int?): Suggestion? {
        val valid = history.filter { it.estimatedMinutes > 0 && it.actualMinutes >= 0 }
        if (valid.size < MIN_SAMPLES) return null

        val totalEstimated = valid.sumOf { it.estimatedMinutes }
        val totalActual = valid.sumOf { it.actualMinutes }

        val ratio = totalActual.toDouble() / totalEstimated
        val average = (totalActual.toDouble() / valid.size).roundToInt()

        val raw = if (userEstimate != null && userEstimate > 0) userEstimate * ratio
        else average.toDouble()

        return Suggestion(
            minutes = roundNicely(raw),
            ratio = ratio,
            averageActual = average,
            sampleSize = valid.size
        )
    }

    /** Sub 15 min rotunjim la minut, peste la multiplu de 5 (37 → 35, 38 → 40). */
    fun roundNicely(minutes: Double): Int =
        if (minutes < 15) max(1, minutes.roundToInt())
        else (minutes / 5).roundToInt() * 5
}