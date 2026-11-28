package md.utm.digitalmirror.domain

/**
 * Regulile pentru prezența într-un loc (din intrările și ieșirile detectate de telefon).
 * Cod pur Kotlin, fără Android, ca să poată fi testat local.
 *
 * GPS-ul și geofencing-ul greșesc des la marginea zonei: o ieșire falsă de 2 minute
 * ar rupe o oră de curs în două. De aceea vizitele apropiate se unesc, iar cele foarte scurte se ignoră.
 */
object PresenceRules {

    /** O vizită mai scurtă de 5 minute nu e prezență (trecere prin zonă). */
    const val MIN_VISIT_MILLIS = 5 * 60_000L

    /** Două vizite la același loc, cu pauză de cel mult 10 minute între ele, sunt una singură. */
    const val MERGE_GAP_MILLIS = 10 * 60_000L

    /**
     * O ieșire nedetectată (telefon oprit, economisire de baterie) ar lăsa vizita deschisă la nesfârșit.
     * O intrare fără ieșire se socotește cel mult 12 ore.
     */
    const val MAX_OPEN_MILLIS = 12 * 60 * 60_000L

    /** Sfârșitul unei vizite: ieșirea detectată, sau „acum” pentru una încă deschisă (cu limita de mai sus). */
    fun resolveEnd(enterAt: Long, exitAt: Long?, now: Long): Long =
        exitAt ?: minOf(now, enterAt + MAX_OPEN_MILLIS)

    data class Visit(val placeId: Long, val start: Long, val end: Long) {
        val minutes: Int get() = ((end - start) / 60_000L).toInt()
    }

    /** Unește vizitele apropiate de la același loc, apoi scoate vizitele prea scurte. Rezultatul e sortat după start. */
    fun clean(visits: List<Visit>): List<Visit> {
        val merged = mutableListOf<Visit>()
        visits.groupBy { it.placeId }.forEach { (placeId, group) ->
            var current: Visit? = null
            for (visit in group.sortedBy { it.start }) {
                val c = current
                current = when {
                    c == null -> visit
                    visit.start - c.end <= MERGE_GAP_MILLIS -> Visit(placeId, c.start, maxOf(c.end, visit.end))
                    else -> {
                        merged += c
                        visit
                    }
                }
            }
            current?.let { merged += it }
        }
        return merged.filter { it.end - it.start >= MIN_VISIT_MILLIS }.sortedBy { it.start }
    }

    /** Păstrează din fiecare vizită doar partea din intervalul [from, to). */
    fun clipTo(visits: List<Visit>, from: Long, to: Long): List<Visit> =
        visits.mapNotNull { v ->
            val s = maxOf(v.start, from)
            val e = minOf(v.end, to)
            if (e > s) Visit(v.placeId, s, e) else null
        }

    /** Minutele totale petrecute la fiecare loc. */
    fun minutesByPlace(visits: List<Visit>): Map<Long, Int> =
        visits.groupBy { it.placeId }.mapValues { (_, list) -> list.sumOf { it.minutes } }
}