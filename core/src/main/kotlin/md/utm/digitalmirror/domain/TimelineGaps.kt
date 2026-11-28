package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy

/**
 * Spațiile libere dintr-o zi, pentru linia de timp: ce rămâne între [from] și [to] după ce scoatem
 * ce e ocupat (activități, calendar, program fix, drumuri). Spațiile mai scurte de [minGap] nu se arată.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object TimelineGaps {

    data class Gap(val start: Int, val end: Int) {
        val minutes: Int get() = end - start
    }

    fun compute(busy: List<Busy>, from: Int, to: Int, minGap: Int = 30): List<Gap> {
        if (to <= from) return emptyList()
        val gaps = mutableListOf<Gap>()
        var cursor = from
        for (b in busy.filter { it.end > from && it.start < to && it.end > it.start }.sortedBy { it.start }) {
            if (b.start > cursor) gaps += Gap(cursor, b.start)
            cursor = maxOf(cursor, b.end)
        }
        if (cursor < to) gaps += Gap(cursor, to)
        return gaps.filter { it.minutes >= minGap }
    }
}
