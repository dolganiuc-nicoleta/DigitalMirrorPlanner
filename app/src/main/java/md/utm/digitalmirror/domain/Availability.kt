package md.utm.digitalmirror.domain

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Blocurile fixe ale utilizatorului (cursuri, masă, somn) și drumul dinainte și de după ele.
 *
 * Un bloc se repetă în anumite zile ale săptămânii. Drumul se adaugă la blocul însuși:
 * pentru cursuri 08:00–14:00 cu 40 min drum la dus și la întors, intervalul ocupat e 07:20–14:40.
 * În zilele marcate ca libere, blocurile nu se aplică.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object Availability {

    const val ALL_DAYS = 0b1111111        // luni … duminică
    const val WEEKDAYS = 0b0011111        // luni … vineri
    const val WEEKEND = 0b1100000         // sâmbătă, duminică

    private const val MINUTES_IN_DAY = 24 * 60
    private val SHORT_DAYS = listOf("Lu", "Ma", "Mi", "Jo", "Vi", "Sâ", "Du")

    data class Rule(
        val id: Long,
        val title: String,
        val daysMask: Int,
        val startMinute: Int,
        val endMinute: Int,
        val travelBefore: Int,
        val travelAfter: Int
    )

    /** Un bloc într-o zi anume, cu drumul inclus. */
    data class Block(
        val ruleId: Long,
        val title: String,
        val start: Int,
        val end: Int,
        val travelBefore: Int,
        val travelAfter: Int
    ) {
        /** Începutul intervalului ocupat, cu drumul dus. */
        val busyStart: Int get() = (start - travelBefore).coerceAtLeast(0)

        /** Sfârșitul intervalului ocupat, cu drumul întors. */
        val busyEnd: Int get() = (end + travelAfter).coerceAtMost(MINUTES_IN_DAY)
    }

    /** Bitul zilei: luni = 1, marți = 2, … duminică = 64. */
    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun appliesOn(daysMask: Int, day: DayOfWeek): Boolean = daysMask and bit(day) != 0

    /** Blocurile unei zile, în ordinea în care încep. Gol dacă ziua e liberă. */
    fun blocksFor(date: LocalDate, rules: List<Rule>, daysOff: Set<String>): List<Block> {
        if (date.toString() in daysOff) return emptyList()
        return rules
            .filter { it.endMinute > it.startMinute && appliesOn(it.daysMask, date.dayOfWeek) }
            .map { Block(it.id, it.title, it.startMinute, it.endMinute, it.travelBefore, it.travelAfter) }
            .sortedBy { it.busyStart }
    }

    /** „Zilnic”, „Lu–Vi”, „Sâ, Du” sau lista zilelor. */
    fun daysLabel(mask: Int): String = when (mask and ALL_DAYS) {
        ALL_DAYS -> "Zilnic"
        WEEKDAYS -> "Lu–Vi"
        WEEKEND -> "Sâ, Du"
        else -> SHORT_DAYS.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(", ")
    }

    fun shortDayName(index: Int): String = SHORT_DAYS[index]
}