package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy

/**
 * Așază activitățile unei zile pe ore, fără suprapuneri cu ce e deja ocupat
 * (activități, calendar, blocuri fixe cu drum).
 *
 * Ordinea: întâi activitățile cu oră spusă de utilizator, apoi cele cu moment al zilei
 * („seara”, „dimineața”), apoi restul, în ordinea în care au fost scrise.
 * Pentru restul se folosește ora recomandată din istoric, iar fără istoric primul interval liber.
 * Cele care nu mai încap nu primesc oră și sunt propuse pentru amânare.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object DayPlanner {

    private const val GRACE = 15      // într-un moment al zilei, ora de start poate fi cel mai târziu cu 15 min înainte de sfârșitul lui

    data class Item(
        val title: String,
        val minutes: Int,
        val startMinute: Int?,
        val dayPart: PlanParser.DayPart?,
        val history: List<SlotRecommender.Outcome> = emptyList()
    )

    enum class Reason { CHOSEN_BY_USER, FROM_HISTORY, FIRST_FREE, NO_ROOM }

    data class Placement(
        val startMinute: Int?,
        val endMinute: Int?,
        val reason: Reason,
        val conflict: Boolean = false      // ora spusă de utilizator se suprapune cu altceva
    ) {
        val placed: Boolean get() = startMinute != null
    }

    /** Rezultatul are aceeași ordine ca [items]. */
    fun place(
        items: List<Item>,
        busy: List<Busy>,
        earliestStart: Int,
        latestEnd: Int = 23 * 60
    ): List<Placement> {
        val result = arrayOfNulls<Placement>(items.size)
        val occupied = busy.toMutableList()

        // 1. ora spusă de utilizator rămâne cum a spus-o, chiar dacă se suprapune; semnalăm conflictul
        items.forEachIndexed { index, item ->
            val start = item.startMinute ?: return@forEachIndexed
            val end = start + item.minutes
            result[index] = Placement(
                start, end, Reason.CHOSEN_BY_USER,
                conflict = !SlotRecommender.isFree(start, end, occupied)
            )
            occupied += Busy(start, end)
        }

        // 2. cu moment al zilei, în ordinea momentelor; 3. fără indicii, în ordinea scrisă
        val withPart = items.indices
            .filter { items[it].startMinute == null && items[it].dayPart != null }
            .sortedWith(compareBy({ items[it].dayPart!!.from }, { it }))
        val rest = items.indices.filter { items[it].startMinute == null && items[it].dayPart == null }

        for (index in withPart + rest) {
            val item = items[index]
            val part = item.dayPart
            val from = if (part != null) maxOf(earliestStart, part.from) else earliestStart
            val lastEnd = if (part != null) minOf(latestEnd, part.to - GRACE + item.minutes) else latestEnd

            val fromHistory = SlotRecommender.suggest(item.history, item.minutes, occupied, from, lastEnd)
            val slot = fromHistory ?: SlotRecommender.firstFree(item.minutes, occupied, from, lastEnd)

            result[index] = if (slot == null) {
                Placement(null, null, Reason.NO_ROOM)
            } else {
                occupied += Busy(slot.startMinute, slot.endMinute)
                Placement(
                    slot.startMinute, slot.endMinute,
                    if (fromHistory != null) Reason.FROM_HISTORY else Reason.FIRST_FREE
                )
            }
        }
        return result.map { it!! }
    }
}