package md.utm.digitalmirror.domain

import md.utm.digitalmirror.domain.SlotRecommender.Busy

/**
 * Mesele, ca ferestre flexibile: „prânzul e cândva între 12:00 și 15:00, cam 30 min”.
 * În fiecare zi alegem în fereastră un loc liber (printre cursuri, drumuri, pregătire), cât mai aproape
 * de mijlocul ferestrei. Dacă nu e loc, masa rămâne „fără loc” și utilizatorul primește doar o notă blândă.
 *
 * Masa nu are un loc anume (cantină, acasă, pe drum), deci nu adaugă drumuri.
 * Nu depinde de Android, deci se poate testa cu teste unitare simple.
 */
object MealPlanner {

    enum class Kind(val label: String, val emoji: String) {
        BREAKFAST("Mic dejun", "🍳"),
        LUNCH("Prânz", "🍽"),
        DINNER("Cină", "🍲")
    }

    /** Fereastra unei mese. [optional] = în zilele fără loc nu insistăm. */
    data class Window(
        val kind: Kind,
        val from: Int,
        val to: Int,
        val minutes: Int,
        val optional: Boolean,
        val enabled: Boolean = true
    )

    data class Slot(val kind: Kind, val start: Int, val end: Int)

    data class Result(
        val slots: List<Slot>,
        val missing: List<Window>            // ferestrele în care n-a fost loc azi
    )

    /** Valorile de pornire; utilizatorul le schimbă în Setări. */
    val DEFAULTS = listOf(
        Window(Kind.BREAKFAST, 7 * 60, 10 * 60, 20, optional = true),
        Window(Kind.LUNCH, 12 * 60, 15 * 60, 30, optional = true),
        Window(Kind.DINNER, 18 * 60, 21 * 60, 30, optional = false)
    )

    private const val STEP = 5

    /**
     * Locul fiecărei mese în zi, în ordinea ferestrelor; mesele deja alese nu se suprapun între ele.
     * @param busy tot ce e ocupat: activități, calendar, blocuri, drumuri cu pregătire
     */
    fun place(windows: List<Window>, busy: List<Busy>): Result {
        val occupied = busy.toMutableList()
        val slots = mutableListOf<Slot>()
        val missing = mutableListOf<Window>()

        for (w in windows.filter { it.enabled && it.to - it.from >= it.minutes }.sortedBy { it.from }) {
            // cât mai aproape de mijlocul ferestrei
            val ideal = (w.from + w.to - w.minutes) / 2
            val best = (w.from..(w.to - w.minutes) step STEP)
                .filter { start -> SlotRecommender.isFree(start, start + w.minutes, occupied) }
                .minByOrNull { kotlin.math.abs(it - ideal) }

            if (best == null) {
                missing += w
            } else {
                slots += Slot(w.kind, best, best + w.minutes)
                occupied += Busy(best, best + w.minutes)
            }
        }
        return Result(slots, missing)
    }
}

/**
 * Scheletul unei zile: drumurile (cu pregătirea de plecare) și mesele, pornind de la ce e deja ocupat.
 * Folosit pe linia de timp și de toată planificarea, ca peste tot să se vadă aceeași zi.
 */
object DaySkeleton {

    data class Result(
        val legs: List<TimelineTravel.Leg>,
        val meals: MealPlanner.Result
    ) {
        /** Drumurile, pregătirea și mesele, ca intervale ocupate (fără loc: nu cer alte drumuri). */
        val busy: List<Busy>
            get() = legs.map { Busy(it.prepStart, it.end) } + meals.slots.map { Busy(it.start, it.end) }
    }

    /**
     * @param busy ce e ocupat, cu locul fiecărui lucru (activități, calendar, blocuri fixe fără drumul inclus)
     * @param travel drumurile dintre locuri, fără pregătire (pregătirea se adaugă aici, doar la plecările de acasă)
     */
    fun build(
        busy: List<Busy>,
        home: Long?,
        travel: TravelGaps.Travel,
        prepMinutes: Int,
        meals: List<MealPlanner.Window>,
        margin: Int = TravelGaps.DEFAULT_MARGIN
    ): Result {
        val stops = busy.mapNotNull { b -> b.placeId?.let { TimelineTravel.Stop(b.start, b.end, it) } }
        val legs = TimelineTravel.legs(stops, home, travel, margin, prepMinutes)
        // lucrurile fără loc își păstrează drumul fix (de ex. blocurile fără loc din Setări)
        val withFixedTravel = busy.map { b ->
            if (b.placeId == null) Busy((b.start - b.travelBefore).coerceAtLeast(0), b.end + b.travelAfter) else b
        }
        val mealResult = MealPlanner.place(meals, withFixedTravel + legs.map { Busy(it.prepStart, it.end) })
        return Result(legs, mealResult)
    }
}
