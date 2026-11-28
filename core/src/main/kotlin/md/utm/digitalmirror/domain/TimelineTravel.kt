package md.utm.digitalmirror.domain

/**
 * Drumurile unei zile, pentru linia de timp: din lucrurile care au loc (activități, evenimente din calendar,
 * blocuri fixe) deducem pe unde treci și când trebuie să pleci.
 *
 * Presupunerea: când nu ai nimic planificat, ești acasă. Deci:
 *  - înaintea primului lucru din afara casei, drumul de acasă până acolo;
 *  - între două lucruri în locuri diferite: dacă pauza ajunge să treci pe acasă (drum dus-întors + cel puțin
 *    [MIN_HOME_STAY] minute acasă), două drumuri prin casă; altfel drumul direct;
 *  - după ultimul lucru din afara casei, drumul spre casă.
 * Fiecare drum include marja de siguranță. Fără loc „acasă” ales, nu presupunem nimic despre casă.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object TimelineTravel {

    /** Cât trebuie să stai acasă ca să merite drumul dus-întors, în loc să rămâi pe drum. */
    const val MIN_HOME_STAY = 30

    /** Un lucru din zi care are loc. */
    data class Stop(val start: Int, val end: Int, val placeId: Long)

    /**
     * Un drum pe linia de timp. [travelMinutes] e drumul fără marjă; intervalul [start]–[end] include marja.
     * La plecările de acasă, înainte de drum vine pregătirea: [prepMinutes], de la [prepStart] la [start].
     * [tight] = nu încape: ar trebui să pleci (sau să te pregătești) înainte să se termine lucrul de dinainte.
     */
    data class Leg(
        val start: Int,
        val end: Int,
        val fromPlaceId: Long,
        val toPlaceId: Long,
        val travelMinutes: Int,
        val tight: Boolean = false,
        val prepMinutes: Int = 0
    ) {
        val prepStart: Int get() = start - prepMinutes
    }

    /** Perechile de locuri pentru care avem nevoie de drum, ca să le calculăm din timp. */
    fun pairsNeeded(stops: List<Stop>, home: Long?): Set<Pair<Long, Long>> {
        val ordered = stops.sortedBy { it.start }
        val pairs = mutableSetOf<Pair<Long, Long>>()
        ordered.zipWithNext().forEach { (a, b) ->
            if (a.placeId != b.placeId) pairs += a.placeId to b.placeId
        }
        if (home != null) {
            ordered.filter { it.placeId != home }.forEach {
                pairs += home to it.placeId
                pairs += it.placeId to home
            }
        }
        return pairs
    }

    /** @param prepMinutes cât îți ia să te pregătești de plecare; se adaugă doar la plecările de acasă */
    fun legs(
        stops: List<Stop>,
        home: Long?,
        travel: TravelGaps.Travel,
        margin: Int = TravelGaps.DEFAULT_MARGIN,
        prepMinutes: Int = 0
    ): List<Leg> {
        val ordered = stops.sortedBy { it.start }
        if (ordered.isEmpty()) return emptyList()
        val legs = mutableListOf<Leg>()

        // ajungi la începutul lucrului; de acasă, cu pregătirea înainte
        fun arrive(from: Long, to: Stop, notBefore: Int?): Leg? {
            val t = travel.minutes(from, to.placeId) ?: return null
            val prep = if (from == home) prepMinutes else 0
            val wanted = to.start - t - margin
            val start = if (notBefore != null) maxOf(wanted, notBefore + prep) else maxOf(wanted, prep)
            return Leg(
                start, to.start, from, to.placeId, t,
                tight = notBefore != null && wanted - prep < notBefore,
                prepMinutes = prep
            )
        }

        // pleci imediat după lucru
        fun leave(from: Stop, to: Long): Leg? {
            val t = travel.minutes(from.placeId, to) ?: return null
            return Leg(from.end, from.end + t + margin, from.placeId, to, t)
        }

        val first = ordered.first()
        if (home != null && first.placeId != home) arrive(home, first, null)?.let { legs += it }

        for ((a, b) in ordered.zipWithNext()) {
            if (a.placeId == b.placeId) continue
            val gap = b.start - a.end

            // pauza ajunge să treci pe acasă
            if (home != null && a.placeId != home && b.placeId != home) {
                val toHome = travel.minutes(a.placeId, home)
                val fromHome = travel.minutes(home, b.placeId)
                if (toHome != null && fromHome != null &&
                    gap >= toHome + fromHome + 2 * margin + prepMinutes + MIN_HOME_STAY
                ) {
                    leave(a, home)?.let { legs += it }
                    arrive(home, b, a.end)?.let { legs += it }
                    continue
                }
            }

            // spre casă pleci imediat; spre altundeva, cât să ajungi la timp
            val leg = if (b.placeId == home) leave(a, home)?.let { l ->
                if (l.end > b.start) l.copy(tight = true) else l
            } else arrive(a.placeId, b, a.end)
            leg?.let { legs += it }
        }

        val last = ordered.last()
        if (home != null && last.placeId != home) leave(last, home)?.let { legs += it }

        return legs
    }
}
