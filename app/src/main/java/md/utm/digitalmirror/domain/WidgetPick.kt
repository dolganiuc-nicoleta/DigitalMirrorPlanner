package md.utm.digitalmirror.domain

/**
 * Ce arată widgetul: pe ce activitate pornește butonul de cronometru și care sunt următoarele lucruri din zi.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object WidgetPick {

    /** O activitate de azi, cât îi trebuie widgetului. */
    data class Candidate(val id: Long, val title: String, val start: Int?, val minutes: Int, val state: String)

    /** Un rând din „urmează”: activitate, curs, masă. */
    data class Line(val start: Int, val end: Int, val title: String)

    private val OPEN = setOf("PLANNED", "IN_PROGRESS")

    /**
     * Activitatea pe care o pornește butonul: cea în desfășurare acum, altfel următoarea cu oră,
     * altfel prima fără oră. null = nimic de pornit azi.
     */
    fun timerTarget(activities: List<Candidate>, now: Int): Candidate? {
        val open = activities.filter { it.state in OPEN }
        return open.firstOrNull { it.start != null && now >= it.start && now < it.start + it.minutes }
            ?: open.filter { it.start != null && it.start > now }.minByOrNull { it.start!! }
            ?: open.firstOrNull { it.start == null }
    }

    /** Următoarele [count] lucruri care nu s-au terminat încă, în ordine. */
    fun upcoming(lines: List<Line>, now: Int, count: Int = 3): List<Line> =
        lines.filter { it.end > now }.sortedBy { it.start }.take(count)
}
