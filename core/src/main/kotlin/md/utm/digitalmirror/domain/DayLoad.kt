package md.utm.digitalmirror.domain

import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Compară cât a planificat utilizatorul pentru o zi cu cât reușește de obicei într-o zi.
 *
 * „Cât reușește de obicei” = mediana minutelor lucrate efectiv (activități finalizate sau parțiale)
 * în ultimele zile active de același tip: zi lucrătoare sau zi de weekend.
 * Mediana, nu media, ca o zi excepțională (12 h înainte de un termen) să nu strice valoarea.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object DayLoad {

    const val MIN_DAYS = 5          // câte zile cu date trebuie ca să comparăm
    const val RECENT_DAYS = 28      // câte zile recente luăm în calcul
    private const val OVER_FACTOR = 1.15
    private const val OVER_MIN_MINUTES = 30
    private const val LIGHT_FACTOR = 0.6

    data class DayTotal(val date: LocalDate, val minutes: Int)

    enum class Status { NO_HISTORY, LIGHT, OK, OVERLOADED }

    data class Result(
        val plannedMinutes: Int,
        val typicalMinutes: Int?,   // null dacă nu sunt destule zile
        val daysUsed: Int,          // pe câte zile s-a calculat valoarea obișnuită
        val status: Status
    ) {
        /** cu cât depășește planul ce reușește de obicei (negativ = mai puțin) */
        val difference: Int get() = plannedMinutes - (typicalMinutes ?: plannedMinutes)
    }

    fun compute(plannedMinutes: Int, history: List<DayTotal>, day: LocalDate): Result {
        val past = history.filter { it.date.isBefore(day) && it.minutes > 0 }
        val weekend = isWeekend(day)

        // preferăm zilele de același tip; dacă sunt prea puține, le luăm pe toate
        val sameKind = past.filter { isWeekend(it.date) == weekend }
        val chosen = (if (sameKind.size >= MIN_DAYS) sameKind else past)
            .sortedByDescending { it.date }
            .take(RECENT_DAYS)

        if (chosen.size < MIN_DAYS) {
            return Result(plannedMinutes, null, chosen.size, Status.NO_HISTORY)
        }

        val typical = median(chosen.map { it.minutes })
        val status = when {
            plannedMinutes > typical * OVER_FACTOR && plannedMinutes - typical >= OVER_MIN_MINUTES -> Status.OVERLOADED
            plannedMinutes < typical * LIGHT_FACTOR -> Status.LIGHT
            else -> Status.OK
        }
        return Result(plannedMinutes, typical, chosen.size, status)
    }

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    private fun median(values: List<Int>): Int {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else ((sorted[mid - 1] + sorted[mid]) / 2.0).roundToInt()
    }
}