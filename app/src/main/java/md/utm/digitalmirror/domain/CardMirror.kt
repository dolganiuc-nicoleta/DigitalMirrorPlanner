package md.utm.digitalmirror.domain

import kotlin.math.abs

/**
 * Oglinda de pe cardul unei activități: o propoziție scurtă care spune ce știe aplicația din istoricul tău.
 *  - înainte (planificată): doar când istoricul spune clar altceva decât planul („de obicei ~1h 50, nu 1h 30”);
 *  - după (terminată): cât a durat față de plan.
 * Când datele nu sunt sigure, nu spune nimic. Nu depinde de Android, deci se poate testa simplu.
 */
object CardMirror {

    /** Istoricul arată altceva decât planul. [sameActivity] = din activități la fel, nu doar din categorie. */
    data class Usually(val minutes: Int, val planned: Int, val samples: Int, val sameActivity: Boolean)

    enum class Versus { OVER, UNDER, ON_PLAN }

    /** Cât a durat față de plan. */
    data class Took(val actual: Int, val planned: Int, val versus: Versus) {
        val difference: Int get() = abs(actual - planned)
    }

    /** Din câte activități la fel credem istoricul. */
    const val MIN_SAME = 3
    /** Din câte activități din categorie credem istoricul, când nu sunt destule la fel. */
    const val MIN_CATEGORY = 5

    /** Cât trebuie să difere ca să merite spus: 20% din plan, dar cel puțin 15 minute. */
    private fun worthSaying(a: Int, b: Int): Boolean = abs(a - b) >= maxOf(15, b / 5)

    fun usually(planned: Int?, estimate: HistoryEstimator.Estimate?): Usually? {
        if (planned == null || planned <= 0 || estimate == null) return null
        val same = estimate.level == HistoryEstimator.Level.ACTIVITY && estimate.activityCount >= MIN_SAME
        val category = estimate.level == HistoryEstimator.Level.CATEGORY && estimate.categoryCount >= MIN_CATEGORY
        if (!same && !category) return null
        if (!worthSaying(estimate.minutes, planned)) return null
        return Usually(estimate.minutes, planned, if (same) estimate.activityCount else estimate.categoryCount, same)
    }

    fun took(planned: Int?, actual: Int?): Took? {
        if (planned == null || planned <= 0 || actual == null || actual <= 0) return null
        // în 10% (cel puțin 5 minute) e „cât ai plănuit”
        val versus = when {
            abs(actual - planned) <= maxOf(5, planned / 10) -> Versus.ON_PLAN
            actual > planned -> Versus.OVER
            else -> Versus.UNDER
        }
        return Took(actual, planned, versus)
    }
}
