package md.utm.digitalmirror.domain

/**
 * Cât durează un drum între două locuri.
 * Cod pur Kotlin, fără Android și fără rețea, ca să poată fi testat local.
 *
 * Durata de bază vine de la OpenRouteService (pe jos, bicicletă, mașină). OpenRouteService nu are transport public:
 * acolo pornim de la traseul pe jos (cel mai scurt drum pe străzi, aproape de cel al autobuzului pe bulevard, nu ocolul
 * mașinii prin străzile cu sens unic), cu viteza medie a transportului și așteptarea în stație. Apoi, cu cât avem mai multe drumuri reale detectate
 * (ieșirea din locul A până la intrarea în locul B), cu atât estimarea se apropie de ele.
 */
object TravelEstimator {

    /** Cum se deplasează utilizatorul. [orsProfile] e profilul OpenRouteService folosit pentru distanță. */
    enum class Mode(val orsProfile: String, val label: String) {
        WALK("foot-walking", "Pe jos"),
        TRANSIT("foot-walking", "Transport public"),  // traseul pe jos (ca al autobuzului pe bulevard), apoi aproximare
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

    /**
     * Cât de diferit e drumul de la o zi la alta (pe jos, cu mașina, în ambuteiaj).
     * [typical] = ce durează de obicei; [planned] = cu cât planificăm, ca să nu întârzii în zilele mai lente;
     * [low]–[high] = intervalul obișnuit (null până avem destule drumuri reale).
     */
    data class Spread(val typical: Int, val planned: Int, val low: Int?, val high: Int?, val count: Int)

    /**
     * Se potrivește un drum real cu modul ales? Telefonul nu știe cu ce te-ai deplasat: un drum de 33 de minute
     * nu e făcut cu mașina pe un traseu de 5 minute (ai mers pe jos sau te-ai oprit pe drum), iar unul de 8 minute
     * nu e făcut pe jos pe un traseu de 21. Păstrăm doar drumurile între 40% și de 2,5 ori traseul (cel puțin +15 min).
     */
    fun plausible(base: Estimate, minutes: Int): Boolean =
        minutes >= base.minutes * 0.4 && minutes <= maxOf(base.minutes * 2.5, base.minutes + 15.0)

    /** De la câte drumuri reale arătăm intervalul și planificăm prudent. */
    const val MIN_FOR_SPREAD = 3

    /**
     * Intervalul din drumurile reale: între al 20-lea și al 80-lea percentil (un drum ieșit din comun nu contează).
     * Planificăm cu capătul de sus, amestecat cu cel obișnuit după câte drumuri avem: cu 3 drumuri cam la mijloc,
     * cu multe aproape de capătul de sus. Marja fixă de la fiecare drum rămâne în plus (vezi TravelGaps).
     */
    fun spread(base: Estimate, observedMinutes: List<Int>): Spread {
        val observed = observedMinutes.filter { it > 0 }.sorted()
        val typical = learn(base, observed).minutes
        if (observed.size < MIN_FOR_SPREAD) return Spread(typical, typical, null, null, observed.size)
        val low = percentile(observed, 0.2)
        val high = percentile(observed, 0.8)
        val weight = observed.size / (observed.size + 2.0)
        val planned = maxOf(typical, Math.round(weight * high + (1 - weight) * typical).toInt())
        return Spread(typical, planned, minOf(low, typical), maxOf(high, typical), observed.size)
    }

    // percentila prin rang (fără interpolare), pe o listă sortată
    private fun percentile(sorted: List<Int>, p: Double): Int =
        sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    /** Un drum din timpul prezențelor: ieșirea din A până la intrarea în B. Peste 3 ore nu mai e un drum. */
    const val MAX_TRIP_MINUTES = 180

    fun observedTrip(exitFromA: Long, enterB: Long): Int? {
        val minutes = ((enterB - exitFromA) / 60_000L).toInt()
        return minutes.takeIf { it in 1..MAX_TRIP_MINUTES }
    }

    /** Un drum observat între două locuri diferite. */
    data class Trip(val fromId: Long, val toId: Long, val minutes: Int, val departAt: Long = 0)   // departAt = ieșirea din A

    /**
     * Drumurile reale din vizitele curățate: la două vizite consecutive la locuri diferite,
     * drumul e de la ieșirea din prima până la intrarea în a doua.
     */
    fun observedTrips(visits: List<PresenceRules.Visit>): List<Trip> =
        visits.sortedBy { it.start }.zipWithNext().mapNotNull { (a, b) ->
            if (a.placeId == b.placeId) null
            else observedTrip(a.end, b.start)?.let { Trip(a.placeId, b.placeId, it, a.end) }
        }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid].toDouble() else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    private fun ceilMinutes(minutes: Double): Int = maxOf(1, Math.ceil(minutes).toInt())
}