package md.utm.digitalmirror.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Progresul unui obiectiv, calculat din activitățile închise din categoriile legate de el.
 *
 * Un obiectiv poate avea o țintă de timp (minute lucrate), o țintă de activități terminate sau ambele.
 * Când are ambele, progresul e media celor două. Fiecare activitate adaugă la progres o fracțiune
 * din ținta ei, așa că aceeași formulă dă și procentul total, și ritmul din ultimele zile.
 *
 * Pe lângă procent, spune dacă utilizatorul e înaintea sau în urma unui ritm liniar până la termen,
 * când ar termina la ritmul recent și dacă ritmul a scăzut sau s-a oprit (stagnare).
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object GoalProgress {

    const val STAGNANT_DAYS = 7                 // atâtea zile fără nicio activitate = stagnare
    private const val RECENT_DAYS = 14          // fereastra pentru ritmul recent
    private const val PACE_MARGIN = 0.10        // ±10 puncte procentuale față de ritmul liniar
    private const val SLOWING_FACTOR = 0.5      // ultima săptămână sub jumătate din cea obișnuită

    data class Goal(
        val startDate: LocalDate,
        val deadline: LocalDate?,
        val targetMinutes: Int?,
        val targetActivities: Int?
    )

    /** O activitate închisă din categoriile obiectivului. */
    data class Contribution(val date: LocalDate, val minutes: Int)

    enum class Pace { DONE, OVERDUE, AHEAD, ON_TRACK, BEHIND, NO_DEADLINE }

    enum class Momentum { OK, SLOWING, STAGNANT, TOO_EARLY }

    data class Result(
        val minutesDone: Int,
        val activitiesDone: Int,
        val percent: Int,                  // 0–100
        val expectedPercent: Int?,         // cât ar fi azi la un ritm liniar; null fără termen
        val pace: Pace,
        val daysLeft: Int?,                // negativ dacă termenul a trecut
        val forecast: LocalDate?,          // când termină la ritmul recent
        val momentum: Momentum,
        val daysSinceLast: Int?            // zile de la ultima activitate
    )

    fun compute(goal: Goal, contributions: List<Contribution>, today: LocalDate): Result {
        val valid = contributions.filter {
            it.minutes > 0 && !it.date.isBefore(goal.startDate) && !it.date.isAfter(today)
        }
        val targetMinutes = goal.targetMinutes?.takeIf { it > 0 }
        val targetActivities = goal.targetActivities?.takeIf { it > 0 }

        // fracțiunea din obiectiv adusă de o activitate
        fun gain(c: Contribution): Double {
            val parts = buildList {
                targetMinutes?.let { add(c.minutes.toDouble() / it) }
                targetActivities?.let { add(1.0 / it) }
            }
            return if (parts.isEmpty()) 0.0 else parts.average()
        }

        val rawDone = valid.sumOf { gain(it) }
        val done = rawDone.coerceAtMost(1.0)

        // câte zile au trecut între două date
        fun daysBetween(from: LocalDate, to: LocalDate) = ChronoUnit.DAYS.between(from, to).toInt()

        val daysSinceStart = daysBetween(goal.startDate, today).coerceAtLeast(0)

        // ritmul liniar până la termen
        val expected: Double? = goal.deadline?.let { deadline ->
            val total = daysBetween(goal.startDate, deadline).coerceAtLeast(1)
            daysSinceStart.coerceAtMost(total).toDouble() / total
        }

        // diferența față de ritmul liniar, în fracțiuni din obiectiv
        val gap = if (expected != null) done - expected else 0.0

        val pace = when {
            rawDone >= 1.0 - 1e-9 -> Pace.DONE
            goal.deadline == null -> Pace.NO_DEADLINE
            today.isAfter(goal.deadline) -> Pace.OVERDUE
            gap >= PACE_MARGIN -> Pace.AHEAD
            gap <= -PACE_MARGIN -> Pace.BEHIND
            else -> Pace.ON_TRACK
        }

        // când termină, dacă ține ritmul din ultimele zile
        val forecast: LocalDate? = if (pace == Pace.DONE) null else {
            val window = minOf(RECENT_DAYS, daysSinceStart + 1)
            val rate = valid.filter { daysBetween(it.date, today) < window }.sumOf { gain(it) } / window
            if (rate > 0) today.plusDays(ceil((1.0 - done) / rate - 1e-9).toLong()) else null
        }

        val last = valid.maxOfOrNull { it.date }
        val daysSinceLast = last?.let { daysBetween(it, today) }

        val momentum = when {
            pace == Pace.DONE -> Momentum.OK
            last == null ->
                if (daysSinceStart >= STAGNANT_DAYS) Momentum.STAGNANT else Momentum.TOO_EARLY
            daysSinceLast!! >= STAGNANT_DAYS -> Momentum.STAGNANT
            isSlowing(valid, { gain(it) }, daysSinceStart, today) -> Momentum.SLOWING
            else -> Momentum.OK
        }

        return Result(
            minutesDone = valid.sumOf { it.minutes },
            activitiesDone = valid.size,
            percent = (done * 100).roundToInt(),
            expectedPercent = expected?.let { (it * 100).roundToInt() },
            pace = pace,
            daysLeft = goal.deadline?.let { daysBetween(today, it) },
            forecast = forecast,
            momentum = momentum,
            daysSinceLast = daysSinceLast
        )
    }

    /** Ultima săptămână aduce mai puțin de jumătate din cât aducea o săptămână obișnuită înainte. */
    private fun isSlowing(
        valid: List<Contribution>,
        gain: (Contribution) -> Double,
        daysSinceStart: Int,
        today: LocalDate
    ): Boolean {
        // câte zile avem înaintea ultimei săptămâni, maximum 21
        val previousDays = minOf(21, daysSinceStart - 6)
        if (previousDays < 7) return false

        fun age(c: Contribution) = ChronoUnit.DAYS.between(c.date, today).toInt()
        val lastWeek = valid.filter { age(it) < 7 }.sumOf { gain(it) }
        val previous = valid.filter { age(it) in 7 until 7 + previousDays }.sumOf { gain(it) }
        if (previous <= 0) return false

        val usualWeek = previous / previousDays * 7
        return lastWeek < SLOWING_FACTOR * usualWeek
    }
}