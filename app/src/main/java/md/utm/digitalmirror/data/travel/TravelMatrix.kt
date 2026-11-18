package md.utm.digitalmirror.data.travel

import android.content.Context
import kotlinx.coroutines.flow.first
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.prefs.MealPrefs
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DaySkeleton
import md.utm.digitalmirror.domain.TimelineTravel
import md.utm.digitalmirror.domain.SlotRecommender.Busy
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.domain.TravelGaps
import java.time.LocalDate

/**
 * Drumurile dintre locuri pentru planificare (zi, săptămână, pregătire), cu memorie pe durata ecranului:
 * fiecare pereche se calculează o singură dată (de pe telefon sau, dacă lipsește, de la serviciul de rute).
 */
class TravelMatrix(private val context: Context) {

    private val repo = TravelRepository(context)
    private val cache = mutableMapOf<Triple<Long, Long, String>, Int?>()

    /** Drumurile de la și până la locurile [candidates], din și spre locurile [busyPlaces]. */
    suspend fun forPlaces(candidates: Collection<Long>, busyPlaces: Collection<Long>): TravelGaps.Travel =
        forPairs(TravelGaps.pairsNeeded(candidates.toSet(), busyPlaces.toSet()))

    /** Drumurile pentru perechile date, fără pregătire. */
    suspend fun forPairs(pairs: Set<Pair<Long, Long>>): TravelGaps.Travel {
        val mode = TravelPrefs.mode(context)
        val missing = pairs.filter { Triple(it.first, it.second, mode.name) !in cache }.toSet()
        if (missing.isNotEmpty()) {
            val found = repo.minutesFor(missing, mode)
            missing.forEach { cache[Triple(it.first, it.second, mode.name)] = found[it] }
        }
        val known = cache.toMap()
        return TravelGaps.Travel { from, to -> known[Triple(from, to, mode.name)] }
    }

    /**
     * Scheletul zilei (drumuri cu pregătire și mese) pentru ce e deja ocupat.
     * @param busy cu locul fiecărui lucru, ca în [DayBusy]
     */
    suspend fun skeleton(busy: List<Busy>): DaySkeleton.Result {
        val home = homePlaceId
        val stops = busy.mapNotNull { b -> b.placeId?.let { TimelineTravel.Stop(b.start, b.end, it) } }
        val travel = forPairs(TimelineTravel.pairsNeeded(stops, home))
        return DaySkeleton.build(busy, home, travel, TravelPrefs.prepMinutes(context), MealPrefs.windows(context))
    }

    /**
     * Ca [forPlaces], dar cu timpul tău de pregătire adăugat la plecările de acasă:
     * așa o activitate de acasă se termină cât să ai timp să te pregătești și să ajungi.
     */
    suspend fun forPlanning(candidates: Collection<Long>, busyPlaces: Collection<Long>): TravelGaps.Travel =
        TravelGaps.withPrep(forPlaces(candidates, busyPlaces), homePlaceId, TravelPrefs.prepMinutes(context))

    /** Locul de unde pleci de obicei; o activitate fără loc se presupune acolo, pentru drum. */
    val homePlaceId: Long? get() = TravelPrefs.homePlaceId(context)
}

/**
 * Ce e ocupat într-o zi, cu locul fiecărui lucru, ca planificarea să poată calcula drumurile.
 * Blocurile fixe vin fără drumul fix inclus (drumul fix rămâne rezervă, vezi [TravelGaps]).
 */
object DayBusy {

    fun activities(list: List<Activity>): List<Busy> =
        list.filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            .mapNotNull { a -> a.plannedStartMinute?.let { Busy(it, it + (a.userEstimatedMinutes ?: 30), a.placeId) } }

    suspend fun calendar(context: Context, date: LocalDate, calendarPlaces: Map<String, Long>): List<Busy> =
        CalendarReader.eventsFor(context, date).filter { it.busy }
            .map { Busy(it.startMinute, it.endMinute, calendarPlaces[TitleKey.of(it.title)]) }

    fun blocks(date: LocalDate, rules: List<Availability.Rule>, daysOff: Set<String>): List<Busy> =
        Availability.blocksFor(date, rules, daysOff).map { it.asBusy() }

    /** Locul evenimentelor din calendar, după numele lor. */
    suspend fun calendarPlaces(context: Context): Map<String, Long> =
        AppDatabase.get(context).calendarPlaceDao().getAll().associate { it.titleKey to it.placeId }

    /** Blocurile fixe din Setări, cu locul lor. */
    suspend fun rules(context: Context): List<Availability.Rule> =
        AppDatabase.get(context).availabilityDao().observeRules().first().map {
            Availability.Rule(
                it.id, it.title, it.daysMask, it.startMinute, it.endMinute,
                it.travelBeforeMinutes, it.travelAfterMinutes, it.placeId
            )
        }
}
