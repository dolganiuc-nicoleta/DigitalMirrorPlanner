package md.utm.digitalmirror.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pariurile capsulei: la sigilare îți prezici luna (câte ore la ceva, cât dormi, cât din plan termini),
 * iar la deschidere vezi ce a fost. E aceeași întrebare ca în restul aplicației (cât crezi vs cât e de fapt),
 * la scară de o lună. Plus „ce s-a schimbat în tine”: cât de bine îți estimai timpul atunci și acum.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object CapsuleBets {

    /** HOURS: minute la o categorie, în toată perioada; SLEEP: minute de somn pe noapte, în medie; COMPLETION: procent. */
    enum class Kind { HOURS, SLEEP, COMPLETION }

    data class Bet(val kind: Kind, val predicted: Int, val categoryId: Long? = null)

    enum class Verdict { NEAR, BELOW, ABOVE }

    /** [actual] null = nu avem destule date ca să spunem ce a fost. */
    data class Result(val bet: Bet, val actual: Int?, val verdict: Verdict?, val difference: Int?)

    /** Cât de departe poate fi realitatea ca să spunem „aproape”: 10%, dar cel puțin atât cât contează. */
    private fun tolerance(bet: Bet): Int = when (bet.kind) {
        Kind.HOURS -> maxOf(60, bet.predicted / 10)
        Kind.SLEEP -> maxOf(15, bet.predicted / 10)
        Kind.COMPLETION -> 5
    }

    fun result(bet: Bet, actual: Int?): Result {
        if (actual == null) return Result(bet, null, null, null)
        val diff = actual - bet.predicted
        val verdict = when {
            abs(diff) <= tolerance(bet) -> Verdict.NEAR
            diff < 0 -> Verdict.BELOW
            else -> Verdict.ABOVE
        }
        return Result(bet, actual, verdict, diff)
    }

    // ---- cât de bine îți estimezi timpul ----

    /** O activitate terminată: cât ai planificat și cât a durat (măsurat, nu copiat din plan). */
    data class Sample(val planned: Int, val actual: Int)

    const val MIN_SAMPLES = 5

    /**
     * Cu cât subestimezi de obicei, în procente: mediana lui (real - planificat) / planificat.
     * Pozitiv = îți ia mai mult decât crezi; negativ = mai puțin. null = prea puține activități.
     */
    fun estimateError(samples: List<Sample>): Int? {
        val ratios = samples.filter { it.planned > 0 && it.actual > 0 }
            .map { (it.actual - it.planned).toDouble() / it.planned }
            .sorted()
        if (ratios.size < MIN_SAMPLES) return null
        val mid = ratios.size / 2
        val median = if (ratios.size % 2 == 1) ratios[mid] else (ratios[mid - 1] + ratios[mid]) / 2
        return (median * 100).roundToInt()
    }

    // ---- propunerile de la sigilare, din ritmul tău real ----

    /** Câte minute la o categorie, pentru [futureDays] zile, după cât ai lucrat în ultimele [pastDays]; la oră întreagă. */
    fun suggestHours(pastMinutes: Int, pastDays: Int, futureDays: Int): Int {
        if (pastDays <= 0 || pastMinutes <= 0) return 10 * 60
        val minutes = pastMinutes.toDouble() / pastDays * futureDays
        return (minutes / 60).roundToInt().coerceAtLeast(1) * 60
    }

    /** Somnul mediu din nopțile credibile, la sfert de oră; 7h 30 dacă nu știm. */
    fun suggestSleep(nights: List<SleepPlanner.Night>): Int {
        val sleeps = nights.filter(SleepPlanner::plausible).mapNotNull { it.sleepMinutes }
        if (sleeps.size < SleepPlanner.MIN_NIGHTS) return 7 * 60 + 30
        return (sleeps.average() / 15).roundToInt() * 15
    }

    /** Cât din ce a fost planificat s-a terminat: parțialele contează pe jumătate. null = nimic de comparat. */
    fun completionRate(due: Int, done: Int, partial: Int): Int? =
        if (due <= 0) null else ((done + 0.5 * partial) / due * 100).roundToInt().coerceIn(0, 100)

    /** Propunerea pentru procentul de terminat: rata ta, la 5 procente; 75% dacă nu știm. */
    fun suggestCompletion(rate: Int?): Int = rate?.let { (it / 5.0).roundToInt() * 5 } ?: 75
}
