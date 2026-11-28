package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy

/**
 * „⚡ A apărut ceva”: ceva neprevăzut îți ia o bucată din zi, iar restul zilei se reface singur.
 *
 *  - cursurile, calendarul și blocurile fixe nu se mută;
 *  - activitățile tale neîncepute care încă încap rămân pe loc;
 *  - celelalte se mută la primul loc liber după întrerupere (cu drumuri, mese, până la perna de seară),
 *    în ordinea în care erau planificate;
 *  - ce nu mai încape azi trece pe mâine.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object Replan {

    /** O activitate de azi care încă nu a început. */
    data class Item(val id: Long, val title: String, val start: Int, val minutes: Int, val placeId: Long? = null) {
        val end: Int get() = start + minutes
    }

    enum class Outcome { STAYS, MOVED, TOMORROW }

    data class Move(val item: Item, val outcome: Outcome, val newStart: Int?)

    /**
     * @param items activitățile de azi neîncepute (cu oră)
     * @param fixed ce nu se mută: calendar, blocuri, mese, activitățile deja începute sau terminate
     * @param from începutul întreruperii (acum), [until] sfârșitul ei
     * @param dayEnd până când se pot pune lucruri (perna de seară)
     */
    fun plan(
        items: List<Item>,
        fixed: List<Busy>,
        from: Int,
        until: Int,
        dayEnd: Int,
        travel: TravelGaps.Travel = TravelGaps.NONE,
        margin: Int = TravelGaps.DEFAULT_MARGIN
    ): List<Move> {
        val blocked = Busy(from, until)
        val ordered = items.sortedBy { it.start }

        // întâi, ce rămâne pe loc: nu atinge întreruperea și nu începe înainte de ea
        val stays = ordered.filter { it.start >= until || it.end <= from }
            .filter { it.start >= from }
        val occupied = (fixed + blocked + stays.map { Busy(it.start, it.end, it.placeId) }).toMutableList()

        val moves = mutableListOf<Move>()
        for (item in ordered) {
            if (item in stays) {
                moves += Move(item, Outcome.STAYS, item.start)
                continue
            }
            val view = TravelGaps.expand(occupied, item.placeId, travel, margin)
            val slot = SlotRecommender.firstFree(item.minutes, view, until, dayEnd)
            if (slot == null) {
                moves += Move(item, Outcome.TOMORROW, null)
            } else {
                moves += Move(item, Outcome.MOVED, slot.startMinute)
                occupied += Busy(slot.startMinute, slot.endMinute, item.placeId)
            }
        }
        return moves
    }
}
