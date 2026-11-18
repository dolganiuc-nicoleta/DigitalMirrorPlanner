package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy

/**
 * Drumul nu ține de un bloc, ci de trecerea de la un loc la altul.
 *
 * Pentru o activitate nouă la locul P, fiecare interval ocupat se „lărgește” cu drumul până la el și înapoi:
 * cursurile 08:00–15:00 la Facultate, văzute de o activitate la Sală (20 min drum), ocupă 08:00–15:30
 * (20 min drum + 10 min marjă). Văzute de o activitate tot la Facultate, rămân 08:00–15:00.
 *
 * Când nu știm unul dintre locuri sau drumul dintre ele, folosim drumul fix pus de utilizator
 * la blocul respectiv (Busy.travelBefore / travelAfter), ca înainte.
 *
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object TravelGaps {

    /** Marja de siguranță adăugată la fiecare drum: un plan fără nicio marjă cade la prima întârziere. */
    const val DEFAULT_MARGIN = 10

    /** Cât durează drumul între două locuri, în minute; null = nu știm. */
    fun interface Travel {
        fun minutes(fromPlaceId: Long, toPlaceId: Long): Int?
    }

    /** Fără drumuri cunoscute: rămân doar drumurile fixe ale blocurilor. */
    val NONE = Travel { _, _ -> null }

    /**
     * Drumul necesar între două locuri, cu marjă.
     * null = nu se poate calcula (lipsește un loc sau drumul), 0 = același loc.
     */
    fun needed(from: Long?, to: Long?, travel: Travel, margin: Int = DEFAULT_MARGIN): Int? {
        if (from == null || to == null) return null
        if (from == to) return 0
        val minutes = travel.minutes(from, to) ?: return null
        return minutes + margin
    }

    /**
     * Intervalele ocupate, așa cum le vede o activitate nouă la locul [placeId]:
     * fiecare e lărgit înainte cu drumul de la activitate la el, iar după cu drumul de la el la activitate.
     */
    fun expand(busy: List<Busy>, placeId: Long?, travel: Travel, margin: Int = DEFAULT_MARGIN): List<Busy> =
        busy.map { b ->
            val before = needed(placeId, b.placeId, travel, margin) ?: b.travelBefore
            val after = needed(b.placeId, placeId, travel, margin) ?: b.travelAfter
            Busy((b.start - before).coerceAtLeast(0), b.end + after)
        }

    /**
     * Cel mai devreme moment de start, la sau după [wanted], în care activitatea de [minutes] minute
     * la locul [placeId] încape cu tot cu drumuri. null = nu încape până la [latestEnd].
     */
    fun earliestFit(
        wanted: Int,
        minutes: Int,
        busy: List<Busy>,
        placeId: Long?,
        travel: Travel,
        margin: Int = DEFAULT_MARGIN,
        latestEnd: Int = 24 * 60
    ): Int? {
        val view = expand(busy, placeId, travel, margin).sortedBy { it.start }
        var start = wanted
        // la fiecare suprapunere sărim după intervalul respectiv și verificăm din nou
        while (start + minutes <= latestEnd) {
            val clash = view.firstOrNull { start < it.end && it.start < start + minutes } ?: return start
            start = clash.end
        }
        return null
    }

    /** Toate perechile de locuri diferite pentru care avem nevoie de drum, ca să le calculăm din timp. */
    fun pairsNeeded(candidatePlaces: Collection<Long>, busyPlaces: Collection<Long>): Set<Pair<Long, Long>> {
        val pairs = mutableSetOf<Pair<Long, Long>>()
        val all = candidatePlaces + busyPlaces
        for (p in candidatePlaces) for (q in all) if (p != q) {
            pairs += p to q
            pairs += q to p
        }
        return pairs
    }
}
