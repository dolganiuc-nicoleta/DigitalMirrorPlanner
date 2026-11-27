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

    /** Cum a încăput masa: întreagă în fereastră, mai scurtă (într-o pauză), sau puțin în afara ferestrei. */
    enum class Fit { NORMAL, SHORT, EARLY, LATE }

    data class Slot(val kind: Kind, val start: Int, val end: Int, val fit: Fit = Fit.NORMAL)

    /** O masă scurtă are cel puțin 2/3 din durată și cel puțin 15 minute (o pauză între cursuri). */
    const val MIN_SHORT_MINUTES = 15

    /** Cât poate ieși masa din fereastră, când înăuntru nu e niciun loc. */
    const val STRETCH_MINUTES = 60

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
            val slot = fit(w, occupied)
            if (slot == null) {
                missing += w
            } else {
                slots += slot
                occupied += Busy(slot.start, slot.end)
            }
        }
        return Result(slots, missing)
    }

    /**
     * Locul unei mese, în ordinea preferinței:
     *  1. întreagă, în fereastră, cât mai aproape de mijlocul ei;
     *  2. mai scurtă, în fereastră (o pauză de 20–25 min între cursuri e tot o masă);
     *  3. întreagă, până la o oră înainte sau după fereastră (pauza de la 11:00 e bună de prânz);
     *  4. mai scurtă, până la o oră în afara ferestrei.
     */
    private fun fit(w: Window, occupied: List<Busy>): Slot? {
        val ideal = (w.from + w.to - w.minutes) / 2
        val shortest = maxOf(MIN_SHORT_MINUTES, (w.minutes * 2 + 2) / 3).coerceAtMost(w.minutes)
        val wideFrom = (w.from - STRETCH_MINUTES).coerceAtLeast(0)
        val wideTo = (w.to + STRETCH_MINUTES).coerceAtMost(24 * 60)

        // cel mai bun început pentru o masă de [minutes] în [from, to)
        fun best(from: Int, to: Int, minutes: Int): Int? =
            (from..(to - minutes) step STEP)
                .filter { start -> SlotRecommender.isFree(start, start + minutes, occupied) }
                .minByOrNull { kotlin.math.abs(it - ideal) }

        // cea mai lungă masă scurtă din [from, to), între [shortest] și durata întreagă
        fun bestShort(from: Int, to: Int): Pair<Int, Int>? =
            (w.minutes - STEP downTo shortest step STEP).firstNotNullOfOrNull { m -> best(from, to, m)?.let { it to m } }

        fun outside(start: Int) = if (start < w.from) Fit.EARLY else Fit.LATE

        best(w.from, w.to, w.minutes)?.let { return Slot(w.kind, it, it + w.minutes) }
        bestShort(w.from, w.to)?.let { (s, m) -> return Slot(w.kind, s, s + m, Fit.SHORT) }
        best(wideFrom, wideTo, w.minutes)?.let { return Slot(w.kind, it, it + w.minutes, outside(it)) }
        bestShort(wideFrom, wideTo)?.let { (s, m) -> return Slot(w.kind, s, s + m, if (s in w.from until w.to) Fit.SHORT else outside(s)) }
        return null
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
