package md.utm.digitalmirror.domain

/**
 * Cât timp a fost fiecare aplicație în prim-plan într-un interval, din evenimentele
 * „aplicația a fost deschisă / a fost închisă” date de Android.
 *
 * Evenimentele trebuie să fie în ordine cronologică. Pot începe înaintea intervalului:
 * o aplicație deschisă înainte de [from] se numără doar din [from] încolo. La fel, una
 * rămasă deschisă la [to] se numără până la [to].
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object ForegroundTime {

    const val RESUMED = 1    // aplicația a ajuns în prim-plan
    const val PAUSED = 2     // aplicația a ieșit din prim-plan

    data class Event(val pkg: String, val type: Int, val time: Long)

    /** Pachet → milisecunde în prim-plan în [from, to]. */
    fun compute(events: List<Event>, from: Long, to: Long): Map<String, Long> {
        val opened = HashMap<String, Long>()
        val total = HashMap<String, Long>()

        fun add(pkg: String, start: Long, end: Long) {
            val ms = minOf(end, to) - maxOf(start, from)
            if (ms > 0) total[pkg] = (total[pkg] ?: 0L) + ms
        }

        for (e in events) {
            when (e.type) {
                RESUMED -> opened[e.pkg] = e.time
                PAUSED -> opened.remove(e.pkg)?.let { add(e.pkg, it, e.time) }
            }
        }
        // aplicațiile încă deschise la sfârșitul intervalului
        opened.forEach { (pkg, start) -> add(pkg, start, to) }
        return total
    }
}