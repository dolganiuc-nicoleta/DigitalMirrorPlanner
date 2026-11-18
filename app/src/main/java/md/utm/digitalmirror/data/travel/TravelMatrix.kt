package md.utm.digitalmirror.data.travel

import android.content.Context
import kotlinx.coroutines.flow.first
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.DayOverride
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.data.prefs.MealPrefs
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DaySkeleton
import md.utm.digitalmirror.domain.SleepPlanner
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
    suspend fun skeleton(busy: List<Busy>, date: LocalDate): DaySkeleton.Result {
        val home = homePlaceId
        val stops = busy.mapNotNull { b -> b.placeId?.let { TimelineTravel.Stop(b.start, b.end, it) } }
        val travel = forPairs(TimelineTravel.pairsNeeded(stops, home))
        return DaySkeleton.build(busy, home, travel, TravelPrefs.prepMinutes(context), DayBusy.meals(context, date))
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

    /** Primul lucru din zi: o activitate, un eveniment, un bloc sau începutul pregătirii de plecare. */
    fun firstStart(busy: List<Busy>, skeleton: DaySkeleton.Result): Int? =
        (busy.map { it.start } + skeleton.legs.map { it.prepStart }).minOrNull()

    /**
     * Ce e ocupat într-o zi oarecare (activități, calendar, blocuri fixe), cu locul fiecăruia.
     * Folosit pentru ziua de mâine, când propunem ora de culcare din seara asta.
     */
    suspend fun forDate(context: Context, date: LocalDate): List<Busy> {
        val db = AppDatabase.get(context)
        val activities = db.activityDao().observeByDate(date.toString()).first()
        val daysOff = db.availabilityDao().observeDaysOff().first().map { it.date }.toSet()
        return activities(activities) + calendar(context, date, calendarPlaces(context)) +
                blocks(date, rules(context), daysOff)
    }

    /** Nopțile recente dinaintea zilei [date] (cel mult 28), pentru ritmul tău de somn. */
    suspend fun recentNights(context: Context, date: LocalDate): List<SleepPlanner.Night> =
        AppDatabase.get(context).dailyActivityDao()
            .between(date.minusDays(28).toString(), date.minusDays(1).toString())
            .map { SleepPlanner.Night(it.sleepMinutes, it.bedtimeMinute, it.wakeMinute) }

    // ---- regulile unei zile: cele obișnuite, plus excepțiile ei din scheletul săptămânii ----

    suspend fun override(context: Context, date: LocalDate): DayOverride? =
        AppDatabase.get(context).dayOverrideDao().get(date.toString())

    /** Ferestrele meselor pentru ziua dată; cele sărite în ziua asta sunt oprite. */
    suspend fun meals(context: Context, date: LocalDate): List<MealPlanner.Window> {
        val skipped = override(context, date)?.skipped.orEmpty()
        return MealPrefs.windows(context).map { if (it.kind.name in skipped) it.copy(enabled = false) else it }
    }

    /** Intervalul pentru lucruri flexibile în ziua dată (după trezire și dimineață, până la culcare). */
    suspend fun window(context: Context, date: LocalDate): Pair<Int, Int> =
        SleepPlanner.activeWindow(recentNights(context, date), override(context, date)?.wakeMinute)

    /** Trezirea propusă pentru ziua dată, cu ora aleasă de tine (dacă există) și micul dejun al zilei. */
    suspend fun sleepPlan(context: Context, date: LocalDate, firstStart: Int?): SleepPlanner.Plan {
        val breakfast = meals(context, date)
            .firstOrNull { it.kind == MealPlanner.Kind.BREAKFAST && it.enabled }?.minutes ?: 0
        return SleepPlanner.plan(recentNights(context, date), firstStart, breakfast, override(context, date)?.wakeMinute)
    }

    /** Blocurile fixe din Setări, cu locul lor. */
    suspend fun rules(context: Context): List<Availability.Rule> =
        AppDatabase.get(context).availabilityDao().observeRules().first().map {
            Availability.Rule(
                it.id, it.title, it.daysMask, it.startMinute, it.endMinute,
                it.travelBeforeMinutes, it.travelAfterMinutes, it.placeId
            )
        }
}
