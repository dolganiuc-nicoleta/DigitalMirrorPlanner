package md.utm.digitalmirror.domain

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Evaluează cât de bune sunt estimările, pe activitățile finalizate.
 *
 * Compară, pe aceleași activități:
 *  - M0: ce a crezut utilizatorul ÎNAINTE să vadă propunerea (userGuessMinutes)
 *  - M3: ce i-a propus aplicația în acel moment (estimatedMinutes)
 * cu durata reală (actualMinutes).
 *
 * Evaluarea e prospectivă: propunerea e cea salvată la crearea activității,
 * calculată doar din istoricul de până atunci, nu una recalculată după aceea.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object EstimateEvaluation {

    const val MIN_RECORDS = 5

    /** O activitate finalizată, cu durata reală din surse sigure (cronometru sau introdusă manual). */
    data class Record(
        val actualMinutes: Int,
        val guessMinutes: Int?,       // M0
        val estimateMinutes: Int?,    // M3
        val lowMinutes: Int?,
        val highMinutes: Int?,
        val plannedMinutes: Int?      // ce a ales utilizatorul până la urmă
    )

    data class Result(
        val comparedCount: Int,           // activități cu ambele estimări
        val userMae: Int?,                // eroarea medie a utilizatorului, în minute
        val appMae: Int?,                 // eroarea medie a aplicației, în minute
        val appCloserPercent: Int?,       // în câte % din cazuri aplicația a fost mai aproape
        val intervalCount: Int,           // activități cu interval propus
        val coveragePercent: Int?,        // în câte % din cazuri durata reală a fost în interval
        val offeredCount: Int,            // de câte ori propunerea a diferit de ghicit
        val acceptedPercent: Int?         // în câte % din acestea utilizatorul a ales propunerea
    ) {
        val enoughData: Boolean get() = comparedCount >= MIN_RECORDS

        /** cu câte procente e mai mică eroarea aplicației decât a utilizatorului */
        val improvementPercent: Int?
            get() = if (userMae == null || appMae == null || userMae == 0) null
            else ((userMae - appMae) * 100.0 / userMae).roundToInt()
    }

    fun evaluate(records: List<Record>): Result {
        val valid = records.filter { it.actualMinutes > 0 }

        // M0 vs M3 pe aceleași activități
        val compared = valid.filter { (it.guessMinutes ?: 0) > 0 && (it.estimateMinutes ?: 0) > 0 }
        val userErrors = compared.map { abs(it.actualMinutes - it.guessMinutes!!) }
        val appErrors = compared.map { abs(it.actualMinutes - it.estimateMinutes!!) }
        val appCloser = compared.indices.count { appErrors[it] < userErrors[it] }
        val ties = compared.indices.count { appErrors[it] == userErrors[it] }

        // acoperirea intervalului
        val withInterval = valid.filter { it.lowMinutes != null && it.highMinutes != null }
        val inside = withInterval.count { it.actualMinutes in it.lowMinutes!!..it.highMinutes!! }

        // acceptarea propunerii, doar unde propunerea a fost diferită de ghicit
        val offered = compared.filter { it.guessMinutes != it.estimateMinutes && it.plannedMinutes != null }
        val accepted = offered.count { it.plannedMinutes == it.estimateMinutes }

        return Result(
            comparedCount = compared.size,
            userMae = userErrors.averageOrNull(),
            appMae = appErrors.averageOrNull(),
            // egalitățile se împart la jumătate, ca procentul să nu favorizeze pe nimeni
            appCloserPercent = percent(appCloser + ties / 2.0, compared.size),
            intervalCount = withInterval.size,
            coveragePercent = percent(inside.toDouble(), withInterval.size),
            offeredCount = offered.size,
            acceptedPercent = percent(accepted.toDouble(), offered.size)
        )
    }

    private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else average().roundToInt()

    private fun percent(part: Double, total: Int): Int? =
        if (total == 0) null else (part * 100 / total).roundToInt()
}