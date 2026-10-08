package md.utm.digitalmirror.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Estimatorul de durată pe niveluri (M3).
 *
 * Folosește istoricul utilizatorului pe trei niveluri, de la cel mai specific la cel mai general:
 *  1. aceeași activitate (același titleKey)  → „Laborator broker îți ia de obicei ~2 h 40”
 *  2. aceeași categorie                      → „la Laboratoare depășești estimarea cu ~30%”
 *  3. valoarea de pornire (prior)            → fără date, se presupune că estimarea ta e corectă
 *
 * Nivelurile se combină cu ponderea w = n / (n + K): cu puține date contează mai mult nivelul general,
 * cu multe date contează aproape doar nivelul specific.
 *
 * Detalii:
 *  - calculele se fac pe scară logaritmică, pentru că duratele și erorile sunt multiplicative
 *    (a depăși cu 20 min contează mult la o activitate de 15 min și puțin la una de 3 h);
 *  - activitățile recente cântăresc mai mult: ponderea se înjumătățește la fiecare 30 de zile;
 *  - raportul real / estimat se taie la [0.25, 4], ca o valoare absurdă să nu strice totul;
 *  - pe lângă estimare se întoarce un interval în care durata reală ar trebui să cadă în ~80% din cazuri.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object HistoryEstimator {

    const val K = 5.0                      // câte activități „valorează” cât nivelul general
    const val HALF_LIFE_DAYS = 30.0        // după 30 de zile o activitate contează pe jumătate
    const val MIN_RATIO = 0.25
    const val MAX_RATIO = 4.0
    const val DEFAULT_MINUTES = 30         // dacă nu știm nimic, nici măcar estimarea utilizatorului
    private const val PRIOR_SIGMA = 0.35   // incertitudinea de pornire (≈ ±40%)
    private const val Z_80 = 1.2816        // pentru un interval de 80% al distribuției normale

    /** O activitate finalizată din istoric. */
    data class Sample(
        val titleKey: String?,
        val categoryId: Long?,
        val baseMinutes: Int?,       // cât a crezut utilizatorul (ghicitul, sau durata planificată)
        val actualMinutes: Int,      // cât a durat de fapt
        val ageDays: Double          // acum câte zile a fost
    )

    enum class Level { ACTIVITY, CATEGORY, PRIOR }

    data class Estimate(
        val minutes: Int,
        val lowMinutes: Int,
        val highMinutes: Int,
        val level: Level,              // nivelul care a contat cel mai mult
        val activityCount: Int,        // câte activități la fel au fost găsite
        val categoryCount: Int,        // câte activități din aceeași categorie (cu estimare) au fost găsite
        val activityTypicalMinutes: Int?,   // durata tipică a acestei activități, dacă există
        val categoryRatio: Double?          // de câte ori durează mai mult decât estimezi în categorie
    )

    /**
     * @param history activitățile finalizate (doar din surse sigure: cronometru sau introduse manual)
     * @param userGuess cât crede utilizatorul că va dura, sau null dacă n-a spus
     * @param priorRatio raportul de pornire (1.0 = estimările utilizatorului sunt corecte); poate veni din onboarding
     */
    fun estimate(
        history: List<Sample>,
        titleKey: String?,
        categoryId: Long?,
        userGuess: Int?,
        priorRatio: Double = 1.0
    ): Estimate {
        // 1. nivelul activității: durata tipică a acestei activități
        val sameActivity = if (titleKey.isNullOrBlank()) emptyList()
        else history.filter { it.titleKey == titleKey && it.actualMinutes > 0 }
        val activity = weightedLogStats(sameActivity.map { it.ageDays to ln(it.actualMinutes.toDouble()) })

        // 2. nivelul categoriei: de câte ori durează mai mult decât crede utilizatorul
        val sameCategory = if (categoryId == null) emptyList()
        else history.filter { it.categoryId == categoryId && (it.baseMinutes ?: 0) > 0 && it.actualMinutes > 0 }
        val categoryRatio = weightedLogStats(sameCategory.map {
            val ratio = (it.actualMinutes.toDouble() / it.baseMinutes!!).coerceIn(MIN_RATIO, MAX_RATIO)
            it.ageDays to ln(ratio)
        })
        // durata tipică în categorie, folosită când utilizatorul n-a dat o estimare
        val categoryActual = weightedLogStats(
            history.filter { categoryId != null && it.categoryId == categoryId && it.actualMinutes > 0 }
                .map { it.ageDays to ln(it.actualMinutes.toDouble()) }
        )

        // 3. combinarea nivelurilor general → specific
        val priorLogRatio = ln(priorRatio.coerceIn(MIN_RATIO, MAX_RATIO))
        val wCat = categoryRatio.n / (categoryRatio.n + K)
        val shrunkLogRatio = wCat * categoryRatio.mean + (1 - wCat) * priorLogRatio
        val catSigma = shrinkSigma(categoryRatio)

        // estimarea fără nivelul activității
        val (generalLog, generalSigma) = when {
            userGuess != null && userGuess > 0 ->
                (ln(userGuess.toDouble()) + shrunkLogRatio) to catSigma
            categoryActual.n > 0 -> {
                val w = categoryActual.n / (categoryActual.n + K)
                (w * categoryActual.mean + (1 - w) * ln(DEFAULT_MINUTES.toDouble())) to shrinkSigma(categoryActual)
            }
            else -> ln(DEFAULT_MINUTES.toDouble()) to PRIOR_SIGMA * 2
        }

        // nivelul activității trage estimarea spre durata ei tipică
        val wAct = activity.n / (activity.n + K)
        val finalLog = wAct * activity.mean + (1 - wAct) * generalLog
        val sigma = if (activity.n > 0) wAct * shrinkSigma(activity) + (1 - wAct) * generalSigma else generalSigma

        val center = exp(finalLog)
        val level = when {
            wAct >= 0.5 -> Level.ACTIVITY
            sameActivity.isNotEmpty() || sameCategory.isNotEmpty() -> Level.CATEGORY
            else -> Level.PRIOR
        }

        return Estimate(
            minutes = DurationEstimator.roundNicely(center),
            lowMinutes = DurationEstimator.roundNicely(center * exp(-Z_80 * sigma)),
            highMinutes = DurationEstimator.roundNicely(center * exp(Z_80 * sigma)),
            level = level,
            activityCount = sameActivity.size,
            categoryCount = sameCategory.size,
            activityTypicalMinutes = if (sameActivity.isEmpty()) null
            else DurationEstimator.roundNicely(exp(activity.mean)),
            categoryRatio = if (sameCategory.isEmpty()) null else exp(categoryRatio.mean)
        )
    }

    /** Media și dispersia ponderate după vechime, pe valori logaritmice. n = suma ponderilor. */
    private data class Stats(val n: Double, val mean: Double, val variance: Double)

    private fun weightedLogStats(values: List<Pair<Double, Double>>): Stats {
        if (values.isEmpty()) return Stats(0.0, 0.0, 0.0)
        val weights = values.map { (age, _) -> recencyWeight(age) }
        val n = weights.sum()
        val mean = values.indices.sumOf { weights[it] * values[it].second } / n
        val variance = values.indices.sumOf { weights[it] * (values[it].second - mean).pow(2) } / n
        return Stats(n, mean, variance)
    }

    fun recencyWeight(ageDays: Double): Double = 0.5.pow(ageDays.coerceAtLeast(0.0) / HALF_LIFE_DAYS)

    // cu puține date nu ne încredem în dispersia observată, o tragem spre valoarea de pornire
    private fun shrinkSigma(s: Stats): Double =
        sqrt((s.n * s.variance + K * PRIOR_SIGMA * PRIOR_SIGMA) / (s.n + K))
}