package md.utm.digitalmirror.domain

/**
 * Cât durează un drum între două locuri.
 * Cod pur Kotlin, fără Android și fără rețea, ca să poată fi testat local.
 *
 * Durata de bază vine de la OpenRouteService (pe jos, bicicletă, mașină). OpenRouteService nu are transport public,
 * așa că acolo folosim o aproximare din distanța rutieră. Apoi, cu cât avem mai multe drumuri reale detectate
 * (ieșirea din locul A până la intrarea în locul B), cu atât estimarea se apropie de ele.
 */
object TravelEstimator {

    /** Cum se deplasează utilizatorul. [orsProfile] e profilul OpenRouteService folosit pentru distanță. */
    enum class Mode(val orsProfile: String, val label: String) {
        WALK("foot-walking", "Pe jos"),
        TRANSIT("driving-car", "Transport public"),   // distanța rutieră, apoi aproximare
        CAR("driving-car", "Mașină"),
        BIKE("cycling-regular", "Bicicletă");

        companion object {
            fun fromName(name: String?): Mode = entries.firstOrNull { it.name == name } ?: WALK
        }
    }

    /** De unde vine estimarea. */
    enum class Source {
        ROUTE,           // calculată direct de serviciul de rute
        APPROXIMATION,   // aproximare (transport public)
        LEARNED          // corectată cu drumurile reale ale utilizatorului
    }

    data class Estimate(val minutes: Int, val source: Source)

    // transportul public: viteza medie în oraș, cu opriri, și așteptarea în stație
    const val TRANSIT_KMH = 17.0
    const val TRANSIT_WAIT_MINUTES = 8

    /** Estimarea de bază, înainte de învățare. */
    fun base(mode: Mode, distanceMeters: Double, routeSeconds: Double): Estimate = when (mode) {
        Mode.TRANSIT -> {
            val ride = distanceMeters / 1000.0 / TRANSIT_KMH * 60.0
            Estimate(ceilMinutes(ride + TRANSIT_WAIT_MINUTES), Source.APPROXIMATION)
        }
        else -> Estimate(ceilMinutes(routeSeconds / 60.0), Source.ROUTE)
    }

    /**
     * Amestecă estimarea de bază cu drumurile reale observate.
     * Greutatea observațiilor crește cu numărul lor: n / (n + 2).
     * Cu 0 observații rămâne estimarea de bază, cu 2 jumătate-jumătate, cu 8 aproape doar observațiile.
     * Pentru observații folosim mediana, ca un drum ieșit din comun să nu strice estimarea.
     */
    fun learn(base: Estimate, observedMinutes: List<Int>): Estimate {
        val observed = observedMinutes.filter { it > 0 }
        if (observed.isEmpty()) return base
        val weight = observed.size / (observed.size + 2.0)
        val blended = weight * median(observed) + (1 - weight) * base.minutes
        return Estimate(maxOf(1, Math.round(blended).toInt()), Source.LEARNED)
    }

    /** Un drum din timpul prezențelor: ieșirea din A până la intrarea în B. Peste 3 ore nu mai e un drum. */
    const val MAX_TRIP_MINUTES = 180

    fun observedTrip(exitFromA: Long, enterB: Long): Int? {
        val minutes = ((enterB - exitFromA) / 60_000L).toInt()
        return minutes.takeIf { it in 1..MAX_TRIP_MINUTES }
    }

    /** Un drum observat între două locuri diferite. */
    data class Trip(val fromId: Long, val toId: Long, val minutes: Int)

    /**
     * Drumurile reale din vizitele curățate: la două vizite consecutive la locuri diferite,
     * drumul e de la ieșirea din prima până la intrarea în a doua.
     */
    fun observedTrips(visits: List<PresenceRules.Visit>): List<Trip> =
        visits.sortedBy { it.start }.zipWithNext().mapNotNull { (a, b) ->
            if (a.placeId == b.placeId) null
            else observedTrip(a.end, b.start)?.let { Trip(a.placeId, b.placeId, it) }
        }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun ceilMinutes(minutes: Double): Int = maxOf(1, Math.ceil(minutes).toInt())
}