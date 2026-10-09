package md.utm.digitalmirror.domain

import kotlin.math.roundToInt

/**
 * Harta orelor productive: pentru fiecare zi a săptămânii și fiecare interval de 3 ore,
 * cât de des utilizatorul finalizează activitățile începute acolo.
 *
 * Rata se calculează ca media unei distribuții Beta(1 + finalizate, 1 + nefinalizate),
 * la fel ca în SlotRecommender: o celulă cu o singură activitate reușită nu apare drept 100%.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object ProductivityMap {

    /** Începutul fiecărui interval: 06–09, 09–12, 12–15, 15–18, 18–21, 21–24. */
    val SLOT_STARTS = listOf(6, 9, 12, 15, 18, 21)
    const val MIN_FOR_CONCLUSION = 3

    /** O activitate închisă: ziua (1 = luni … 7 = duminică), ora de start și dacă a fost finalizată. */
    data class Outcome(val weekday: Int, val startMinute: Int, val success: Boolean)

    data class Cell(val weekday: Int, val slot: Int, val total: Int, val successes: Int) {
        /** rata netezită, 0..1; null dacă nu există nicio activitate */
        val rate: Double? get() = if (total == 0) null else (successes + 1.0) / (total + 2.0)
        val percent: Int? get() = if (total == 0) null else (successes * 100.0 / total).roundToInt()
    }

    data class Best(val label: String, val percent: Int, val total: Int)

    data class Result(
        val cells: List<Cell>,       // 7 zile × 6 intervale
        val total: Int,
        val bestSlot: Best?,         // de ex. „15–18”
        val bestDay: Best?           // de ex. „marți”
    ) {
        fun cell(weekday: Int, slot: Int): Cell = cells.first { it.weekday == weekday && it.slot == slot }
    }

    /** Indexul intervalului pentru o oră de start; null pentru orele dintre 0 și 6 dimineața. */
    fun slotOf(startMinute: Int): Int? {
        val hour = startMinute / 60
        return if (hour < 6 || hour > 23) null else (hour - 6) / 3
    }

    fun slotLabel(slot: Int): String {
        val start = SLOT_STARTS[slot]
        return "%02d–%02d".format(start, start + 3)
    }

    private val DAY_NAMES = listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică")

    fun compute(outcomes: List<Outcome>): Result {
        val placed = outcomes.mapNotNull { o -> slotOf(o.startMinute)?.let { slot -> o to slot } }

        val cells = (1..7).flatMap { day ->
            SLOT_STARTS.indices.map { slot ->
                val inCell = placed.filter { (o, s) -> o.weekday == day && s == slot }
                Cell(day, slot, inCell.size, inCell.count { it.first.success })
            }
        }

        // cel mai bun interval, pe toate zilele la un loc
        val bestSlot = SLOT_STARTS.indices
            .map { slot -> slot to cells.filter { it.slot == slot } }
            .map { (slot, list) -> Triple(slot, list.sumOf { it.total }, list.sumOf { it.successes }) }
            .filter { it.second >= MIN_FOR_CONCLUSION }
            .maxByOrNull { (_, total, ok) -> (ok + 1.0) / (total + 2.0) }
            ?.let { (slot, total, ok) -> Best(slotLabel(slot), (ok * 100.0 / total).roundToInt(), total) }

        // cea mai bună zi, pe toate intervalele la un loc
        val bestDay = (1..7)
            .map { day -> day to cells.filter { it.weekday == day } }
            .map { (day, list) -> Triple(day, list.sumOf { it.total }, list.sumOf { it.successes }) }
            .filter { it.second >= MIN_FOR_CONCLUSION }
            .maxByOrNull { (_, total, ok) -> (ok + 1.0) / (total + 2.0) }
            ?.let { (day, total, ok) -> Best(DAY_NAMES[day - 1], (ok * 100.0 / total).roundToInt(), total) }

        return Result(cells, placed.size, bestSlot, bestDay)
    }
}