package md.utm.digitalmirror.domain

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Propune ora de start pentru o activitate, pe baza orelor la care utilizatorul
 * a început în trecut activități din aceeași categorie și dacă le-a finalizat.
 *
 * Pentru fiecare oră candidată se calculează o „rată de reușită” estimată:
 *  1. activitățile din istoric apropiate în timp de ora candidată contează mai mult
 *     (pondere gaussiană, o activitate la ±1h contează ~60%, la ±2h ~14%);
 *  2. rata se calculează ca medie a unei distribuții Beta(1 + reușite, 1 + eșecuri),
 *     adică pornim de la 50% și ne mutăm spre datele reale pe măsură ce se adună.
 *     Astfel un interval cu o singură reușită nu bate un interval cu 8 reușite din 10.
 *
 * Se iau în calcul doar intervalele libere din ziua respectivă.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object SlotRecommender {

    const val MIN_SAMPLES = 3
    private const val STEP_MINUTES = 15            // orele candidate: 9:00, 9:15, 9:30...
    private const val SIGMA_MINUTES = 60.0         // cât de „larg” influențează o activitate din istoric
    private const val MIN_EVIDENCE = 0.5           // sub atât, nu avem destule date în jurul acelei ore

    /** O activitate închisă din istoric: când a început și dacă a fost finalizată complet. */
    data class Outcome(val startMinute: Int, val success: Boolean)

    /** Un interval deja ocupat în ziua respectivă, în minute de la miezul nopții. */
    data class Busy(val start: Int, val end: Int)

    data class Suggestion(
        val startMinute: Int,
        val endMinute: Int,
        val successPercent: Int,     // rata de reușită estimată pentru această oră
        val nearbyCount: Int,        // câte activități din istoric au fost la ±1h de această oră
        val sampleSize: Int          // câte activități are istoricul în total
    )

    fun suggest(
        history: List<Outcome>,
        durationMinutes: Int,
        busy: List<Busy>,
        earliestStart: Int = 7 * 60,     // nu propunem înainte de 7:00
        latestEnd: Int = 23 * 60         // și nici după 23:00
    ): Suggestion? {
        if (history.size < MIN_SAMPLES) return null
        val duration = durationMinutes.coerceAtLeast(5)

        var best: Suggestion? = null
        var bestScore = -1.0

        var start = roundUpToStep(earliestStart)
        while (start + duration <= latestEnd) {
            val end = start + duration
            if (isFree(start, end, busy)) {
                var successes = 0.0
                var failures = 0.0
                for (o in history) {
                    val d = (o.startMinute - start) / SIGMA_MINUTES
                    val w = exp(-d * d / 2)
                    if (o.success) successes += w else failures += w
                }
                val evidence = successes + failures
                if (evidence >= MIN_EVIDENCE) {
                    // media distribuției Beta(1 + reușite, 1 + eșecuri)
                    val score = (successes + 1) / (evidence + 2)
                    if (score > bestScore) {
                        bestScore = score
                        best = Suggestion(
                            startMinute = start,
                            endMinute = end,
                            successPercent = (score * 100).roundToInt(),
                            nearbyCount = history.count { kotlin.math.abs(it.startMinute - start) <= 60 },
                            sampleSize = history.size
                        )
                    }
                }
            }
            start += STEP_MINUTES
        }
        return best
    }

    fun isFree(start: Int, end: Int, busy: List<Busy>): Boolean =
        busy.none { start < it.end && it.start < end }

    private fun roundUpToStep(minute: Int): Int =
        ((minute + STEP_MINUTES - 1) / STEP_MINUTES) * STEP_MINUTES
}