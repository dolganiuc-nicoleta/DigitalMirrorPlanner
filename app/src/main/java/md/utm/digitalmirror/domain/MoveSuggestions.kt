package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy
import java.time.LocalDate

/**
 * „Mută”: unde mai încape o activitate, gata calculat. Din locurile libere ale câtorva zile alegem
 * câteva propuneri variate: cea mai devreme, apoi câte un loc în alt moment al zilei, ca să ai de ales.
 *
 * Zilele vin deja pregătite: ce e ocupat (lărgit cu drumuri, cu mesele), intervalul dintre trezire și
 * perna de seară. Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object MoveSuggestions {

    data class Day(val date: LocalDate, val busy: List<Busy>, val from: Int, val to: Int)

    enum class Note { EARLIEST, MORNING, AFTERNOON, EVENING }

    data class Suggestion(val date: LocalDate, val start: Int, val end: Int, val note: Note)

    private const val STEP = 15
    /** Două propuneri în aceeași zi trebuie să fie departe una de alta, altfel nu e o alegere reală. */
    private const val SAME_DAY_GAP = 3 * 60

    /**
     * @param current unde e acum activitatea (ca să nu o propunem chiar acolo)
     */
    fun suggest(minutes: Int, days: List<Day>, current: Pair<LocalDate, Int?>? = null, max: Int = 4): List<Suggestion> {
        val result = mutableListOf<Suggestion>()
        for (day in days) {
            val starts = freeStarts(minutes, day).filterNot { current != null && day.date == current.first && it == current.second }
            if (starts.isEmpty()) continue

            // prima din zi; apoi încă una, mai târziu cu cel puțin 3h, dacă există
            val picked = mutableListOf(starts.first())
            starts.firstOrNull { it - picked.first() >= SAME_DAY_GAP }?.let { picked += it }

            for (start in picked) {
                val note = if (result.isEmpty()) Note.EARLIEST else partOf(start)
                result += Suggestion(day.date, start, start + minutes, note)
                if (result.size >= max) return result
            }
        }
        return result
    }

    /** Orele de start libere dintr-o zi, din sfert în sfert de oră. */
    fun freeStarts(minutes: Int, day: Day): List<Int> {
        val first = (day.from + STEP - 1) / STEP * STEP
        return (first..(day.to - minutes) step STEP).filter { SlotRecommender.isFree(it, it + minutes, day.busy) }
    }

    fun partOf(start: Int): Note = when {
        start < 12 * 60 -> Note.MORNING
        start < 17 * 60 -> Note.AFTERNOON
        else -> Note.EVENING
    }
}
