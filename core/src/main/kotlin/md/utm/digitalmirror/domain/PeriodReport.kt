package md.utm.digitalmirror.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * Rapoartele pe perioade: rata de finalizare, timpul lucrat, evoluția pe săptămâni,
 * comparația dintre două perioade și activitățile amânate repetat.
 *
 * O activitate care încă poate fi făcută (planificată pentru azi sau mai târziu) nu se numără
 * ca nefinalizată; se numără abia după ce ziua a trecut. Nu depinde de Android, deci se testează simplu.
 */
object PeriodReport {

    const val MIN_REPEATED = 2          // amânată de cel puțin atâtea ori
    private val OPEN_STATES = setOf("PLANNED", "IN_PROGRESS")
    private val WORKED_STATES = setOf("DONE", "PARTIAL")

    data class Item(
        val date: LocalDate,
        val state: String,
        val actualMinutes: Int?,
        val categoryId: Long?,
        val title: String,
        val titleKey: String?,
        val postponedCount: Int
    )

    data class Summary(
        val from: LocalDate,
        val to: LocalDate,
        val due: Int,               // activitățile scadente: cele la care se poate spune dacă s-au făcut
        val done: Int,
        val partial: Int,
        val abandoned: Int,
        val notClosed: Int,         // rămase neînchise sau amânate
        val minutes: Int,           // timp lucrat (activități finalizate sau parțiale)
        val activeDays: Int,
        val minutesByCategory: Map<Long?, Int>
    ) {
        /** Câte din activitățile scadente au fost finalizate complet; null dacă nu era nimic scadent. */
        val completionPercent: Int? get() = if (due == 0) null else (done * 100.0 / due).roundToInt()
    }

    data class Comparison(val current: Summary, val previous: Summary) {
        val doneDelta: Int get() = current.done - previous.done

        /** Diferența în puncte procentuale; null dacă una din perioade n-avea nimic scadent. */
        val completionDelta: Int?
            get() {
                val now = current.completionPercent ?: return null
                val before = previous.completionPercent ?: return null
                return now - before
            }

        /** Schimbarea timpului lucrat, în procente; null dacă perioada anterioară n-are timp lucrat. */
        val minutesChangePercent: Int?
            get() = if (previous.minutes <= 0) null
            else ((current.minutes - previous.minutes) * 100.0 / previous.minutes).roundToInt()
    }

    data class Postponed(val title: String, val times: Int, val instances: Int)

    /** Luni a săptămânii în care cade data. */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun summarize(items: List<Item>, from: LocalDate, to: LocalDate, today: LocalDate): Summary {
        val inRange = items.filter { !it.date.isBefore(from) && !it.date.isAfter(to) && !it.date.isAfter(today) }
        // azi nu s-a terminat ziua: ce e încă planificat nu e „ratat”
        val counted = inRange.filterNot { it.date == today && it.state in OPEN_STATES }

        val done = counted.count { it.state == "DONE" }
        val partial = counted.count { it.state == "PARTIAL" }
        val abandoned = counted.count { it.state == "ABANDONED" }

        val worked = inRange.filter { it.state in WORKED_STATES && (it.actualMinutes ?: 0) > 0 }

        return Summary(
            from = from,
            to = to,
            due = counted.size,
            done = done,
            partial = partial,
            abandoned = abandoned,
            notClosed = counted.size - done - partial - abandoned,
            minutes = worked.sumOf { it.actualMinutes ?: 0 },
            activeDays = worked.map { it.date }.distinct().size,
            minutesByCategory = worked.groupBy { it.categoryId }
                .mapValues { entry -> entry.value.sumOf { it.actualMinutes ?: 0 } }
        )
    }

    /** Ultimele [count] săptămâni, până la săptămâna care începe la [lastWeekStart]; cea mai veche prima. */
    fun weeks(items: List<Item>, lastWeekStart: LocalDate, count: Int, today: LocalDate): List<Summary> =
        (count - 1 downTo 0).map { back ->
            val start = lastWeekStart.minusWeeks(back.toLong())
            summarize(items, start, start.plusDays(6), today)
        }

    /** Ultimele [days] zile față de cele [days] dinaintea lor. */
    fun compare(items: List<Item>, days: Int, today: LocalDate): Comparison {
        val currentFrom = today.minusDays(days - 1L)
        val previousTo = currentFrom.minusDays(1)
        return Comparison(
            current = summarize(items, currentFrom, today, today),
            previous = summarize(items, previousTo.minusDays(days - 1L), previousTo, today)
        )
    }

    /**
     * Activitățile amânate de mai multe ori și încă nefăcute. Activitățile „la fel” se grupează după titlul normalizat.
     * Cele care au ajuns până la urmă finalizate nu apar.
     */
    fun repeatedlyPostponed(items: List<Item>, minTimes: Int = MIN_REPEATED, limit: Int = 5): List<Postponed> =
        items
            .groupBy { it.titleKey?.takeIf { key -> key.isNotBlank() } ?: it.title.trim().lowercase() }
            .map { (_, list) ->
                val latest = list.maxByOrNull { it.date }!!
                Triple(latest, list.sumOf { it.postponedCount }, list.size)
            }
            .filter { (latest, times, _) -> times >= minTimes && latest.state != "DONE" }
            .sortedByDescending { it.second }
            .take(limit)
            .map { (latest, times, instances) -> Postponed(latest.title, times, instances) }

    /**
     * Observațiile din raport, în cuvinte, cu ton neutru.
     * [previousLabel] e felul în care se numește perioada de comparație, de exemplu „săptămâna trecută”.
     */
    fun observations(
        current: Summary,
        previous: Summary?,
        previousLabel: String,
        topCategory: String?,
        repeated: List<Postponed>
    ): List<String> {
        val lines = mutableListOf<String>()

        val percent = current.completionPercent
        if (percent == null) {
            lines += "Nu ai activități scadente în această perioadă."
        } else {
            val delta = previous?.completionPercent?.let { percent - it }
            val comparison = when {
                delta == null -> ""
                delta >= 5 -> ", cu $delta puncte mai mult decât $previousLabel"
                delta <= -5 -> ", cu ${-delta} puncte mai puțin decât $previousLabel"
                else -> ", aproape ca $previousLabel"
            }
            lines += "Ai finalizat ${current.done} din ${current.due} " +
                    "${plural(current.due, "activitate", "activități")} ($percent%)$comparison."
        }

        if (current.minutes > 0) {
            val change = previous?.takeIf { it.minutes > 0 }
                ?.let { ((current.minutes - it.minutes) * 100.0 / it.minutes).roundToInt() }
            val comparison = when {
                change == null -> ""
                change >= 20 -> ", cu $change% mai mult decât $previousLabel"
                change <= -20 -> ", cu ${-change}% mai puțin decât $previousLabel"
                else -> ""
            }
            lines += "Ai lucrat ${duration(current.minutes)} în ${current.activeDays} " +
                    "${plural(current.activeDays, "zi", "zile")}$comparison."
        }

        topCategory?.let { lines += "Cel mai mult timp a mers la $it." }

        if (current.due >= 3 && current.done == current.due) {
            lines += "Toate activitățile scadente au fost finalizate."
        } else if (current.notClosed > 0) {
            lines += "${current.notClosed} ${plural(current.notClosed, "activitate a", "activități au")} " +
                    "rămas neînchise sau amânate."
        }

        repeated.firstOrNull()?.let {
            lines += "„${it.title}” a fost amânată de ${it.times} ori. Poate merită împărțită în pași mai mici " +
                    "sau mutată la altă oră."
        }
        return lines
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) one else many

    private fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}