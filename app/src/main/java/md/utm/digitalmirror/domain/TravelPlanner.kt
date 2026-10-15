package md.utm.digitalmirror.domain

/**
 * Unde trebuie să apară un drum în ziua unui utilizator.
 * Un drum e nevoie între două activități consecutive care au loc și locurile lor sunt diferite.
 * Cod pur Kotlin, testat local.
 */
object TravelPlanner {

    /** O activitate din zi, redusă la ce contează pentru drum. */
    data class Stop(
        val id: Long,
        val title: String,
        val placeId: Long?,
        val startMinute: Int?,       // null = fără oră, nu putem pune drumul
        val durationMinutes: Int
    ) {
        val endMinute: Int? get() = startMinute?.plus(durationMinutes)
    }

    /** Un drum necesar între două activități. [availableMinutes] e timpul liber dintre ele (poate fi negativ). */
    data class Leg(
        val from: Stop,
        val to: Stop,
        val availableMinutes: Int
    ) {
        /** Încape drumul în timpul dintre cele două activități? */
        fun fits(travelMinutes: Int): Boolean = availableMinutes >= travelMinutes

        /** Ora la care trebuie să pleci ca să ajungi la timp. */
        fun departMinute(travelMinutes: Int): Int = to.startMinute!! - travelMinutes
    }

    /**
     * Drumurile dintre activitățile consecutive ale zilei.
     * Activitățile fără oră sau fără loc nu intră: nu știm unde să punem drumul.
     */
    fun legs(stops: List<Stop>): List<Leg> {
        val ordered = stops
            .filter { it.placeId != null && it.startMinute != null }
            .sortedBy { it.startMinute }
        return ordered.zipWithNext()
            .filter { (a, b) -> a.placeId != b.placeId }
            .map { (a, b) -> Leg(a, b, b.startMinute!! - a.endMinute!!) }
    }
}