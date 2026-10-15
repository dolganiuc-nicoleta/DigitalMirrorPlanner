package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Împarte pe zilele unei săptămâni munca de făcut („Lucru la licență, 6 ore”) în sesiuni
 * și le așază pe ore, fără suprapuneri cu ce e deja ocupat.
 *
 * Reguli:
 *  - o sarcină „fixată” pe o zi rămâne pe acea zi;
 *  - sarcinile cu termen apropiat se așază primele; nicio sesiune nu trece de termen;
 *  - sesiunile unei sarcini se răspândesc pe zile diferite, în zilele mai puțin încărcate;
 *  - o zi primește cel mult cât reușește utilizatorul de obicei (bugetul zilei); peste buget se
 *    intră doar dacă altfel sesiunea nu încape nicăieri, și ziua devine „riscantă”;
 *  - ce nu încape deloc rămâne „neașezat”.
 *
 * „Șansele” sunt o estimare simplă, nu o probabilitate demonstrată: fiecare sesiune reușește cu
 * rata cu care a reușit utilizatorul până acum, scăzută în zilele peste buget; ce nu încape
 * se socotește 0. Rezultatul e partea așteptată din muncă ce se termină.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object WeekPlanner {

    const val WEEKDAY_BUDGET = 300      // minute de lucru pe o zi lucrătoare, când nu avem istoric
    const val WEEKEND_BUDGET = 180
    const val DEFAULT_SUCCESS_RATE = 0.75
    private const val MIN_RATE = 0.3
    private const val MAX_RATE = 0.95
    private const val DAY_START = 8 * 60
    private const val DAY_END = 22 * 60
    private const val MIN_SESSION = 20          // o rămășiță mai mică se lipește de sesiunea dinainte
    private const val OVERLOADED = 1.15
    private const val TIGHT = 0.85

    data class Task(
        val id: Long,
        val title: String,
        val totalMinutes: Int,
        val sessionMinutes: Int,
        val deadline: LocalDate? = null,        // ultima zi în care sesiunile mai au voie
        val pinnedDate: LocalDate? = null,      // toate sesiunile pe această zi
        val history: List<SlotRecommender.Outcome> = emptyList()
    )

    data class DayInput(
        val date: LocalDate,
        val busy: List<Busy>,                   // blocuri fixe, calendar, scenariu, activități deja puse
        val plannedMinutes: Int = 0,            // cât e deja planificat în acea zi (din busy sau fără oră)
        val typicalMinutes: Int? = null         // cât reușește de obicei (DayLoad); null = nu știm
    ) {
        val budget: Int
            get() = typicalMinutes ?: if (DayLoad.isWeekend(date)) WEEKEND_BUDGET else WEEKDAY_BUDGET
    }

    enum class Risk { LOW, MEDIUM, HIGH }

    data class Session(
        val taskId: Long,
        val title: String,
        val date: LocalDate,
        val startMinute: Int,
        val endMinute: Int,
        val pinned: Boolean
    ) {
        val minutes: Int get() = endMinute - startMinute
    }

    data class Unplaced(val taskId: Long, val minutes: Int)

    data class TaskResult(
        val taskId: Long,
        val neededMinutes: Int,
        val plannedMinutes: Int,
        val dates: List<LocalDate>,
        val risk: Risk
    ) {
        val unplacedMinutes: Int get() = neededMinutes - plannedMinutes
    }

    data class DayResult(
        val date: LocalDate,
        val addedMinutes: Int,
        val totalMinutes: Int,                  // ce era deja + ce am adăugat
        val budget: Int
    ) {
        val ratio: Double get() = if (budget <= 0) 1.0 else totalMinutes.toDouble() / budget
        val overloaded: Boolean get() = ratio > OVERLOADED
        val tight: Boolean get() = ratio > TIGHT
    }

    data class Plan(
        val sessions: List<Session>,
        val unplaced: List<Unplaced>,
        val tasks: List<TaskResult>,
        val days: List<DayResult>,
        val chancePercent: Int?,                // null = nu e nimic de făcut
        val riskyDay: LocalDate?
    )

    /**
     * @param days zilele din care se poate alege (de obicei de azi până duminică), în ordine
     * @param successRate rata cu care reușește de obicei (0–1); null = valoarea implicită
     * @param earliestToday cel mai devreme minut de start în prima zi (ora curentă), dacă prima zi e azi
     */
    fun plan(
        tasks: List<Task>,
        days: List<DayInput>,
        successRate: Double? = null,
        earliestToday: Int = DAY_START,
        today: LocalDate? = null
    ): Plan {
        val rate = (successRate ?: DEFAULT_SUCCESS_RATE).coerceIn(MIN_RATE, MAX_RATE)
        val occupied = days.associate { it.date to it.busy.toMutableList() }
        val added = days.associate { it.date to 0 }.toMutableMap()
        val perTaskDay = mutableMapOf<Pair<Long, LocalDate>, Int>()

        val sessions = mutableListOf<Session>()
        val unplaced = mutableListOf<Unplaced>()

        // sarcinile fixate primele, apoi după termen, apoi cele mai mari
        val ordered = tasks.sortedWith(
            compareBy<Task>(
                { if (it.pinnedDate != null) 0 else 1 },
                { it.deadline ?: LocalDate.MAX },
                { -it.totalMinutes }
            )
        )

        for (task in ordered) {
            for (size in split(task.totalMinutes, task.sessionMinutes)) {
                val candidates = days.filter { day ->
                    when {
                        task.pinnedDate != null -> day.date == task.pinnedDate
                        task.deadline != null && day.date.isAfter(task.deadline) -> false
                        else -> true
                    }
                }.mapNotNull { day ->
                    val start = if (day.date == today) maxOf(DAY_START, earliestToday) else DAY_START
                    val slot = slotFor(task, size, occupied.getValue(day.date), start) ?: return@mapNotNull null
                    Candidate(day, slot)
                }

                val best = if (task.pinnedDate != null) candidates.firstOrNull()
                else candidates.minWithOrNull(
                    compareBy<Candidate>(
                        { if (it.over(added, size)) 1 else 0 },                         // întâi zile cu loc în buget
                        { if (perTaskDay.containsKey(task.id to it.day.date)) 1 else 0 }, // apoi zile fără sarcina asta
                        { it.after(added, size) },                                       // apoi cea mai ușoară zi
                        { it.day.date }
                    )
                )

                if (best == null) {
                    unplaced += Unplaced(task.id, size)
                } else {
                    val (start, end) = best.slot
                    sessions += Session(task.id, task.title, best.day.date, start, end, task.pinnedDate != null)
                    occupied.getValue(best.day.date) += Busy(start, end)
                    added[best.day.date] = added.getValue(best.day.date) + size
                    perTaskDay.merge(task.id to best.day.date, 1, Int::plus)
                }
            }
        }

        val dayResults = days.map {
            val extra = added.getValue(it.date)
            DayResult(it.date, extra, it.plannedMinutes + extra, it.budget)
        }
        val byDate = dayResults.associateBy { it.date }

        val taskResults = tasks.map { task ->
            val mine = sessions.filter { it.taskId == task.id }
            val planned = mine.sumOf { it.minutes }
            val lost = unplaced.any { it.taskId == task.id }
            val dates = mine.map { it.date }.distinct().sorted()
            val worstRatio = dates.maxOfOrNull { byDate.getValue(it).ratio } ?: 0.0
            val noSlack = task.deadline != null && dates.lastOrNull() == task.deadline
            val risk = when {
                lost || worstRatio > OVERLOADED -> Risk.HIGH
                worstRatio > TIGHT || noSlack -> Risk.MEDIUM
                else -> Risk.LOW
            }
            TaskResult(task.id, task.totalMinutes, planned, dates, risk)
        }

        val needed = tasks.sumOf { it.totalMinutes }
        val expected = sessions.sumOf { s ->
            val ratio = byDate.getValue(s.date).ratio
            val factor = if (ratio > 1.0) 1.0 / ratio else 1.0
            s.minutes * rate * factor
        }
        val chance = if (needed == 0) null else (100 * expected / needed).roundToInt().coerceIn(0, 100)

        val risky = dayResults.filter { it.addedMinutes > 0 && it.tight }.maxByOrNull { it.ratio }?.date

        return Plan(sessions.sortedWith(compareBy({ it.date }, { it.startMinute })), unplaced, taskResults, dayResults, chance, risky)
    }

    /** 270 min în sesiuni de 90 → 90, 90, 90. 100 în sesiuni de 90 → 100 (rămășița de 10 se lipește). */
    fun split(total: Int, session: Int): List<Int> {
        if (total <= 0) return emptyList()
        val size = session.coerceAtLeast(MIN_SESSION)
        val count = ceil(total.toDouble() / size).toInt()
        val parts = MutableList(count) { size }
        parts[count - 1] = total - size * (count - 1)
        if (count > 1 && parts.last() < MIN_SESSION) {
            val last = parts.removeAt(count - 1)
            parts[parts.size - 1] += last
        }
        return parts
    }

    private class Candidate(val day: DayInput, val slot: Pair<Int, Int>) {
        fun after(added: Map<LocalDate, Int>, size: Int): Double {
            val budget = day.budget
            val total = day.plannedMinutes + added.getValue(day.date) + size
            return if (budget <= 0) 9.0 else total.toDouble() / budget
        }
        fun over(added: Map<LocalDate, Int>, size: Int): Boolean = after(added, size) > 1.0
    }

    private fun slotFor(task: Task, minutes: Int, busy: List<Busy>, earliest: Int): Pair<Int, Int>? {
        val fromHistory = SlotRecommender.suggest(task.history, minutes, busy, earliest, DAY_END)
        if (fromHistory != null) return fromHistory.startMinute to fromHistory.endMinute
        val free = SlotRecommender.firstFree(minutes, busy, earliest, DAY_END) ?: return null
        return free.startMinute to free.endMinute
    }
}