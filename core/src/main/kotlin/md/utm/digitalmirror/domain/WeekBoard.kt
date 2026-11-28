package md.utm.digitalmirror.domain

/**
 * Tabla săptămânii: cât încape realist într-o zi și cât de plină e.
 *
 * Capacitatea unei zile pentru sarcinile tale e cea mai mică dintre:
 *  - cât reușești de obicei într-o zi (din istoric, sau valoarea de pornire);
 *  - timpul în care ești treaz, minus ce e fix (cursuri, programări) și minus „viața” (mese, drumuri, pauze).
 * Așa o zi cu 6 ore de cursuri nu pare liberă doar pentru că n-ai scris încă sarcini în ea.
 * Nu depinde de Android, deci se poate testa simplu.
 */
object WeekBoard {

    enum class Load { EMPTY, OK, FULL, OVER }

    /** Mesele, drumurile și pauzele dintr-o zi obișnuită: timp care nu e nici fix, nici pentru sarcini. */
    const val LIFE_MINUTES = 90

    fun capacity(budget: Int, awakeMinutes: Int, fixedMinutes: Int): Int =
        minOf(budget, (awakeMinutes - fixedMinutes - LIFE_MINUTES).coerceAtLeast(0))

    /** Plin între 85% și 115% din capacitate; peste, prea mult. Ziua fără capacitate și fără sarcini e goală. */
    fun load(planned: Int, capacity: Int): Load = when {
        planned <= 0 -> Load.EMPTY
        capacity <= 0 -> Load.OVER
        planned > capacity * 1.15 -> Load.OVER
        planned >= capacity * 0.85 -> Load.FULL
        else -> Load.OK
    }

    /** Cât timp ocupă lucrurile fixe, fără să numere de două ori intervalele care se suprapun. */
    fun fixedMinutes(intervals: List<Pair<Int, Int>>): Int {
        var total = 0
        var end = -1
        for ((s, e) in intervals.filter { it.second > it.first }.sortedBy { it.first }) {
            if (s >= end) {
                total += e - s
                end = e
            } else if (e > end) {
                total += e - end
                end = e
            }
        }
        return total
    }
}
