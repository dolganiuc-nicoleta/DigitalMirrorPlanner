package md.utm.digitalmirror.ui.day

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.CalendarPlace
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.DayOff
import md.utm.digitalmirror.data.entity.DailyActivity
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.entity.Reflection
import md.utm.digitalmirror.data.entity.WorkSession
import md.utm.digitalmirror.data.prefs.MealPrefs
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.TravelRepository
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.DaySkeleton
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.domain.MoveSuggestions
import md.utm.digitalmirror.domain.SleepPlanner
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.HistoryEstimator
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.Tags
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.domain.TravelEstimator
import md.utm.digitalmirror.domain.TravelGaps
import md.utm.digitalmirror.domain.TimelineTravel
import md.utm.digitalmirror.domain.TravelPlanner
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(private val app: Application) : AndroidViewModel(app) {

    data class FinishDraft(val activity: Activity, val trackedMinutes: Int)

    /** Estimarea unui drum din zi: se calculează la cerere, ca să nu apelăm rețeaua degeaba. */
    data class LegEstimate(
        val loading: Boolean = false,
        val minutes: Int? = null,
        val error: String? = null
    )

    /** Un drum necesar între două activități consecutive, gata de afișat. */
    data class LegUi(
        val leg: TravelPlanner.Leg,
        val fromName: String,
        val toName: String,
        val estimate: LegEstimate,
        val added: Boolean            // există deja o activitate „Drum: …” pentru el
    )

    // 1. repository
    private val repo: PlannerRepository = AppDatabase.get(app).let { db ->
        PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    }
    private val reflectionDao = AppDatabase.get(app).reflectionDao()
    private val dailyActivityDao = AppDatabase.get(app).dailyActivityDao()
    private val availabilityDao = AppDatabase.get(app).availabilityDao()

    // zilele libere și blocurile fixe (cursuri, masă, somn) din Setări
    private val daysOff: Flow<Set<String>> =
        availabilityDao.observeDaysOff().map { list -> list.map { it.date }.toSet() }

    private val rules: Flow<List<Availability.Rule>> =
        availabilityDao.observeRules().map { list ->
            list.map {
                Availability.Rule(
                    it.id, it.title, it.daysMask, it.startMinute, it.endMinute,
                    it.travelBeforeMinutes, it.travelAfterMinutes, it.placeId
                )
            }
        }

    private val travel = TravelRepository(app)

    // drumurile dintre locuri calculate în această sesiune (pentru formular), pe moduri de deplasare
    private val travelCache = mutableMapOf<Triple<Long, Long, TravelEstimator.Mode>, Int?>()

    /**
     * Drumurile de la și până la [placeId] din locurile date, pentru a așeza o activitate nouă.
     * Ce e deja calculat vine de pe telefon; restul se cere o singură dată.
     */
    suspend fun travelFor(placeId: Long, busyPlaces: Collection<Long>): TravelGaps.Travel =
        TravelGaps.withPrep(
            travelBetween(TravelGaps.pairsNeeded(listOf(placeId), busyPlaces)),
            homePlaceId, TravelPrefs.prepMinutes(app)
        )

    /** Drumurile pentru perechile date; ce e deja calculat vine de pe telefon, restul se cere o singură dată. */
    private suspend fun travelBetween(pairs: Set<Pair<Long, Long>>): TravelGaps.Travel {
        val mode = _travelMode.value
        val missing = pairs.filter { Triple(it.first, it.second, mode) !in travelCache }.toSet()
        if (missing.isNotEmpty()) {
            val found = travel.minutesFor(missing, mode)
            missing.forEach { travelCache[Triple(it.first, it.second, mode)] = found[it] }
        }
        val known = travelCache.toMap()
        return TravelGaps.Travel { from, to -> known[Triple(from, to, mode)] }
    }

    /** Locul de unde pleci de obicei; o activitate fără loc se presupune acolo, pentru drum. */
    val homePlaceId: Long? get() = TravelPrefs.homePlaceId(app)

    private val calendarPlaceDao = AppDatabase.get(app).calendarPlaceDao()

    /** Locul evenimentelor din calendar, după titlul lor normalizat (TitleKey). */
    val calendarPlaces: StateFlow<Map<String, Long>> =
        calendarPlaceDao.observeAll()
            .map { list -> list.associate { it.titleKey to it.placeId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Alege locul pentru toate evenimentele din calendar cu acest nume; null = fără loc. */
    fun setCalendarPlace(eventTitle: String, placeId: Long?) = viewModelScope.launch {
        val key = TitleKey.of(eventTitle)
        if (key.isEmpty()) return@launch
        if (placeId == null) calendarPlaceDao.delete(key)
        else calendarPlaceDao.upsert(CalendarPlace(key, placeId, CalendarPlace.SOURCE_USER))
    }

    // 2. starea
    private val _date = MutableStateFlow(LocalDate.now().toString())
    val date: StateFlow<String> = _date

    // cronometrul pornit, urmărit direct din baza de date:
    // așa ecranul se actualizează și când utilizatorul apasă Start/Pauză/Gata dintr-o notificare
    val runningSession: StateFlow<WorkSession?> =
        repo.observeRunningSession()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // timpul din sesiunile anterioare ale activității care rulează
    val runningOffset: StateFlow<Long> =
        repo.observeRunningSession()
            .mapLatest { session -> session?.let { repo.closedMillis(it.activityId) } ?: 0L }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _finishDraft = MutableStateFlow<FinishDraft?>(null)
    val finishDraft: StateFlow<FinishDraft?> = _finishDraft

    val categories: StateFlow<List<Category>> =
        repo.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // locurile pentru câmpul „Unde?” din formular
    val places: StateFlow<List<Place>> =
        AppDatabase.get(app).placeDao().observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activities: StateFlow<List<Activity>> =
        _date.flatMapLatest { repo.observeActivitiesForDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // punctele de sub zilele benzii din partea de sus: câte activități are fiecare zi din săptămâna aleasă
    val weekDots: StateFlow<Map<String, DayDot>> =
        _date.flatMapLatest { d ->
            val day = LocalDate.parse(d)
            val start = day.minusDays((day.dayOfWeek.value - 1).toLong())
            repo.observeActivitiesInRange(start.toString(), start.plusDays(6).toString())
        }.map { list ->
            list.filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
                .groupBy { it.date }
                .mapValues { (_, items) -> DayDot(items.size, items.count { it.state == "DONE" || it.state == "PARTIAL" }) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // modul de deplasare (același ca în „Locuri”) și estimările făcute în această sesiune
    private val _travelMode = MutableStateFlow(TravelPrefs.mode(app))
    val travelMode: StateFlow<TravelEstimator.Mode> = _travelMode

    private val legEstimates = MutableStateFlow<Map<String, LegEstimate>>(emptyMap())

    private fun legKey(leg: TravelPlanner.Leg, mode: TravelEstimator.Mode) =
        "${leg.from.id}-${leg.to.id}-${mode.name}"

    // drumurile dintre activitățile zilei care au loc și oră, la locuri diferite
    val legs: StateFlow<List<LegUi>> =
        combine(activities, places, _travelMode, legEstimates) { list, allPlaces, mode, estimates ->
            val active = list.filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            val stops = active.map {
                TravelPlanner.Stop(
                    id = it.id,
                    title = it.title,
                    placeId = it.placeId,
                    startMinute = it.plannedStartMinute,
                    durationMinutes = it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0
                )
            }
            val names = allPlaces.associate { it.id to it.name }
            TravelPlanner.legs(stops).map { leg ->
                val from = names[leg.from.placeId] ?: return@map null
                val to = names[leg.to.placeId] ?: return@map null
                val title = TravelPlanner.tripTitle(from, to)
                LegUi(
                    leg = leg,
                    fromName = from,
                    toName = to,
                    estimate = estimates[legKey(leg, mode)] ?: LegEstimate(),
                    added = active.any { it.title == title && TravelPlanner.TRAVEL_TAG in Tags.decode(it.tags) }
                )
            }.filterNotNull()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setTravelMode(mode: TravelEstimator.Mode) {
        TravelPrefs.setMode(app, mode)
        _travelMode.value = mode
    }

    /** Estimează drumul unei perechi de activități; rezultatul rămâne cât ține ecranul. */
    fun estimateLeg(item: LegUi) {
        val mode = _travelMode.value
        val key = legKey(item.leg, mode)
        if (legEstimates.value[key]?.loading == true) return
        val from = places.value.find { it.id == item.leg.from.placeId } ?: return
        val to = places.value.find { it.id == item.leg.to.placeId } ?: return
        legEstimates.value = legEstimates.value + (key to LegEstimate(loading = true))
        viewModelScope.launch {
            val result = travel.estimate(from, to, mode)
            legEstimates.value = legEstimates.value + (key to result.fold(
                onSuccess = { LegEstimate(minutes = it.estimate.minutes) },
                onFailure = { LegEstimate(error = it.message ?: "Ceva nu a mers.") }
            ))
        }
    }

    /** Adaugă drumul ca activitate, care începe la ora la care trebuie să pleci. */
    fun addTravel(item: LegUi) = viewModelScope.launch {
        val minutes = item.estimate.minutes ?: return@launch
        if (item.added) return@launch
        val activity = Activity(
            title = TravelPlanner.tripTitle(item.fromName, item.toName),
            categoryId = null,
            date = _date.value,
            plannedStartMinute = item.leg.departMinute(minutes).coerceAtLeast(0),
            estimatedMinutes = null,
            userEstimatedMinutes = minutes,
            actualMinutes = null,
            tags = TravelPlanner.TRAVEL_TAG
        )
        val id = repo.addActivity(activity)
        ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
    }

    // reflecția zilei afișate; null = ziua nu a fost încă închisă
    val reflection: StateFlow<Reflection?> =
        _date.flatMapLatest { reflectionDao.observeByDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // somnul și pașii zilei afișate (din Health Connect)
    // încărcarea zilei: cât e planificat vs. cât reușește utilizatorul de obicei
    val dayLoad: StateFlow<DayLoad.Result?> =
        _date.flatMapLatest { date ->
            val history = flow { emit(repo.dailyTotals(date)) }
            combine(repo.observeActivitiesForDate(date), history, daysOff) { list, totals, off ->
                // într-o zi liberă nu avertizăm, iar zilele libere nu intră în media zilelor tipice
                if (date in off) return@combine null
                val planned = list
                    .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
                    .sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
                DayLoad.compute(planned, totals.filter { it.date.toString() !in off }, LocalDate.parse(date))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val isDayOff: StateFlow<Boolean> =
        combine(_date, daysOff) { date, off -> date in off }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val fixedBlocks: StateFlow<List<Availability.Block>> =
        combine(_date, rules, daysOff) { date, list, off ->
            Availability.blocksFor(LocalDate.parse(date), list, off)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Scheletul zilei afișate, pentru linia de timp: drumurile (cu pregătirea de plecare) și mesele,
     * din activitățile, evenimentele din calendar și blocurile fixe. Doar pentru azi și zilele viitoare.
     * `by lazy`, pentru că folosește fluxuri declarate mai jos în clasă (calendarul).
     */
    // excepțiile din scheletul săptămânii (altă oră de trezire, mese sărite): la schimbare, ziua se recalculează
    private val overrides = AppDatabase.get(app).dayOverrideDao().observeAll()

    val skeleton: StateFlow<DaySkeleton.Result?> by lazy {
        combine(activities, calendarEvents, fixedBlocks, calendarPlaces, combine(_travelMode, overrides) { m, _ -> m }) { list, events, blocks, links, _ ->
            val active = list.filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            val busy = active.mapNotNull { a ->
                val start = a.plannedStartMinute ?: return@mapNotNull null
                // drumurile adăugate deja ca activitate nu sunt locuri prin care treci
                val place = a.placeId.takeIf { TravelPlanner.TRAVEL_TAG !in Tags.decode(a.tags) }
                SlotRecommender.Busy(start, start + (a.userEstimatedMinutes ?: a.estimatedMinutes ?: 30), place)
            } + events.filter { it.busy && !it.allDay }.map { e ->
                SlotRecommender.Busy(e.startMinute, e.endMinute, links[TitleKey.of(e.title)])
            } + blocks.map { it.asBusy() }
            // drumurile puse de mână ca activități, ca să nu le arătăm de două ori
            val manual = active.filter { TravelPlanner.TRAVEL_TAG in Tags.decode(it.tags) }
                .mapNotNull { a -> a.plannedStartMinute?.let { it to it + (a.userEstimatedMinutes ?: 0) } }
            Triple(busy, manual, _date.value)
        }.mapLatest { (busy, manual, date) ->
            if (LocalDate.parse(date).isBefore(LocalDate.now())) return@mapLatest null
            val home = homePlaceId
            val stops = busy.mapNotNull { b -> b.placeId?.let { TimelineTravel.Stop(b.start, b.end, it) } }
            val travel = travelBetween(TimelineTravel.pairsNeeded(stops, home))
            // azi: mesele cu fereastra deja trecută nu mai contează, iar celelalte se pun de acum încolo
            var windows = DayBusy.meals(app, LocalDate.parse(date))
            var mealBusy = busy
            if (LocalDate.parse(date) == LocalDate.now()) {
                val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
                windows = windows.filter { it.to - it.minutes > now }
                mealBusy = busy + SlotRecommender.Busy(0, now)
            }
            val meals = DaySkeleton.build(mealBusy, home, travel, TravelPrefs.prepMinutes(app), windows).meals
            val result = DaySkeleton.build(busy, home, travel, TravelPrefs.prepMinutes(app), emptyList()).copy(meals = meals)
            result.copy(legs = result.legs.filter { leg -> manual.none { (s, e) -> s < leg.end && leg.prepStart < e } })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    /**
     * Somnul zilei afișate: ora de trezire (din ritmul tău și din primul lucru al zilei) și ora de culcare
     * din seara ei (ca să dormi cât dormi de obicei până la trezirea de a doua zi).
     */
    data class SleepUi(
        val today: SleepPlanner.Plan,
        val firstStart: Int?,              // primul lucru din zi, pentru explicație
        val tomorrow: SleepPlanner.Plan,
        val tomorrowFirstStart: Int?
    ) {
        /** Ora de culcare pe linia de timp a zilei afișate (peste 24:00 dacă e după miezul nopții). */
        val bedtime: Int get() = tomorrow.bedtimeBefore
    }

    private val skeletonMatrix by lazy { TravelMatrix(app) }

    /** Intervalul pentru lucruri flexibile: după trezirea obișnuită și dimineață, până la ora de culcare. */
    val dayWindow: StateFlow<Pair<Int, Int>> by lazy {
        combine(_date, overrides) { d, _ -> d }
            .mapLatest { DayBusy.window(app, LocalDate.parse(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SleepPlanner.activeWindow(emptyList()))
    }

    val sleep: StateFlow<SleepUi?> by lazy {
        combine(skeleton, activities, calendarEvents, fixedBlocks, calendarPlaces) { sk, list, events, blocks, links ->
            val busy = list.filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
                .mapNotNull { a -> a.plannedStartMinute?.let { SlotRecommender.Busy(it, it + (a.userEstimatedMinutes ?: 30)) } } +
                    events.filter { it.busy && !it.allDay }.map { SlotRecommender.Busy(it.startMinute, it.endMinute) } +
                    blocks.map { SlotRecommender.Busy(it.start, it.end) }
            Triple(sk, busy, _date.value)
        }.mapLatest { (sk, busy, date) ->
            val day = LocalDate.parse(date)
            if (sk == null || day.isBefore(LocalDate.now())) return@mapLatest null
            val first = DayBusy.firstStart(busy, sk)
            val today = DayBusy.sleepPlan(app, day, first)

            // ziua de mâine, ca să știm cât de devreme te trezești și deci când te culci în seara asta
            val next = day.plusDays(1)
            val nextBusy = DayBusy.forDate(app, next)
            val nextFirst = DayBusy.firstStart(nextBusy, skeletonMatrix.skeleton(nextBusy, next))
            val tomorrow = DayBusy.sleepPlan(app, next, nextFirst)

            SleepUi(today, first, tomorrow, nextFirst)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    // ---- „Mută”: unde mai încape o activitate ----

    /**
     * Propuneri gata calculate pentru a muta o activitate: din azi (sau din ziua ei, dacă e viitoare și mai
     * devreme nu se poate) până la limita ei: ajunul evenimentului pentru o sesiune de pregătire, termenul sau
     * duminica pentru o sesiune din planul săptămânii, altfel o săptămână. Țin cont de tot: celelalte lucruri
     * din zi, drumuri, mese, trezire și perna de seară.
     */
    suspend fun moveSuggestions(activity: Activity): List<MoveSuggestions.Suggestion> {
        val today = LocalDate.now()
        val minutes = activity.userEstimatedMinutes ?: activity.estimatedMinutes ?: 30

        val db = AppDatabase.get(app)
        val last: LocalDate = when {
            activity.prepStepId != null ->
                db.prepDao().eventDateForStep(activity.prepStepId)?.let { LocalDate.parse(it).minusDays(1) } ?: today.plusDays(6)
            activity.weekTaskId != null -> db.weekTaskDao().get(activity.weekTaskId)?.let { task ->
                task.deadline?.let(LocalDate::parse) ?: LocalDate.parse(task.weekStart).plusDays(6)
            } ?: today.plusDays(6)
            else -> today.plusDays(6)
        }

        val place = activity.placeId ?: homePlaceId
        val rules = DayBusy.rules(app)
        val daysOff = availabilityDao.observeDaysOff().first().map { it.date }.toSet()
        val links = DayBusy.calendarPlaces(app)
        val nowMinute = java.time.LocalTime.now().let { it.hour * 60 + it.minute + 10 }

        val dates = generateSequence(today) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }
            .filter { it.toString() !in daysOff }.toList()
        val days = dates.map { date ->
                val others = repo.observeActivitiesForDate(date.toString()).first().filter { it.id != activity.id }
                val busy = DayBusy.activities(others) + DayBusy.calendar(app, date, links) + DayBusy.blocks(date, rules, daysOff)
                val meals = skeletonMatrix.skeleton(busy, date).meals.slots.map { SlotRecommender.Busy(it.start, it.end) }
                val travel = skeletonMatrix.forPlanning(listOfNotNull(place), busy.mapNotNull { it.placeId })
                val (start, end) = DayBusy.window(app, date)
                MoveSuggestions.Day(
                    date = date,
                    busy = TravelGaps.expand(busy, place, travel) + meals,
                    from = if (date == today) maxOf(start, nowMinute) else start,
                    to = end
                )
            }

        return MoveSuggestions.suggest(minutes, days, current = LocalDate.parse(activity.date) to activity.plannedStartMinute)
    }

    /**
     * Mută activitatea la ziua și ora alese. E amânare doar dacă ziua ei a venit și o muți mai încolo;
     * mutarea unei activități viitoare, sau mai devreme, e doar replanificare.
     */
    fun moveTo(activity: Activity, date: LocalDate, start: Int) = viewModelScope.launch {
        val fresh = repo.getActivity(activity.id) ?: return@launch
        val today = LocalDate.now()
        val from = LocalDate.parse(fresh.date)
        if (date != from) {
            val due = !from.isAfter(today) && date.isAfter(from)
            repo.postponeActivity(fresh.id, date.toString(), countAsPostponed = due)
        }
        val moved = (repo.getActivity(fresh.id) ?: return@launch).copy(plannedStartMinute = start)
        repo.updateActivity(moved)
        ActivityReminders.onActivityClosed(app, moved.id)
        ActivityReminders.onActivityPlanned(app, moved)
    }

    // ---- confirmarea somnului, dimineața ----

    private val sleepPrefs by lazy { app.getSharedPreferences("sleep_check", android.content.Context.MODE_PRIVATE) }
    private val sleepCheckRefresh = MutableStateFlow(0)

    /**
     * Noaptea trecută de confirmat, doar pentru azi și doar dacă n-ai confirmat-o (sau introdus-o) deja.
     * null = nu întrebăm nimic.
     */
    val sleepCheck: StateFlow<SleepPlanner.Check?> by lazy {
        combine(_date, health, sleepCheckRefresh) { date, record, _ -> date to record }
            .mapLatest { (date, record) ->
                val day = LocalDate.parse(date)
                if (day != LocalDate.now()) return@mapLatest null
                if (record?.sleepSource == "MANUAL") return@mapLatest null
                if (sleepPrefs.getString(KEY_DISMISSED, null) == date) return@mapLatest null
                val saved = record?.let { SleepPlanner.Night(it.sleepMinutes, it.bedtimeMinute, it.wakeMinute) }
                SleepPlanner.check(saved, DayBusy.recentNights(app, day))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    }

    /** Salvează noaptea trecută cum a confirmat-o utilizatorul; Health nu o mai suprascrie. */
    fun confirmSleep(bedtime: Int, wake: Int) = viewModelScope.launch {
        val today = LocalDate.now().toString()
        val existing = dailyActivityDao.getByDate(today)
        dailyActivityDao.upsert(
            (existing ?: DailyActivity(date = today, steps = null, activeMinutes = null)).copy(
                sleepMinutes = SleepPlanner.duration(bedtime, wake),
                bedtimeMinute = bedtime,
                wakeMinute = wake,
                sleepSource = "MANUAL"
            )
        )
    }

    /** „Azi nu mai întreba.” */
    fun dismissSleepCheck() {
        sleepPrefs.edit().putString(KEY_DISMISSED, LocalDate.now().toString()).apply()
        sleepCheckRefresh.value++
    }

    fun toggleDayOff() = viewModelScope.launch {
        val date = _date.value
        if (isDayOff.value) availabilityDao.deleteDayOff(date) else availabilityDao.insertDayOff(DayOff(date))
    }

    val health: StateFlow<DailyActivity?> =
        _date.flatMapLatest { dailyActivityDao.observeByDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // evenimentele din calendarul telefonului pentru ziua afișată (doar citire)
    private val calendarRefresh = MutableStateFlow(0)
    val calendarEvents: StateFlow<List<CalendarReader.Event>> =
        combine(_date, calendarRefresh) { date, _ -> date }
            .mapLatest { CalendarReader.eventsFor(app, LocalDate.parse(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // apelat când ecranul revine în prim-plan: poate s-a schimbat ceva în calendar
    fun refreshCalendar() {
        calendarRefresh.value++
    }

    // 3. păstrat pentru compatibilitate cu DayScreen; starea cronometrului vine acum direct din baza de date
    fun refreshRunning() = Unit

    // 4. navigare între zile
    fun changeDate(newDate: String) {
        _date.value = newDate
    }

    fun previousDay() {
        _date.value = LocalDate.parse(_date.value).minusDays(1).toString()
    }

    fun nextDay() {
        _date.value = LocalDate.parse(_date.value).plusDays(1).toString()
    }

    fun goToToday() {
        _date.value = LocalDate.now().toString()
    }

    // 5. categorii și activități
    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.addCategory(name.trim())
    }

    /**
     * Adaugă o activitate nouă.
     * @param plannedMinutes durata planificată finală (ce a rămas în formular)
     * @param guessMinutes cât a crezut utilizatorul înainte să vadă propunerea
     * @param estimate propunerea estimatorului, așa cum a văzut-o utilizatorul
     */
    fun addActivity(
        title: String,
        categoryId: Long?,
        plannedMinutes: Int?,
        startMinute: Int?,
        guessMinutes: Int?,
        estimate: HistoryEstimator.Estimate?,
        note: String = "",
        tags: String = "",
        placeId: Long? = null
    ) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        val activity = Activity(
            title = title.trim(),
            categoryId = categoryId,
            date = _date.value,
            plannedStartMinute = startMinute,
            userGuessMinutes = guessMinutes,
            estimatedMinutes = estimate?.minutes,
            estimatedLowMinutes = estimate?.lowMinutes,
            estimatedHighMinutes = estimate?.highMinutes,
            userEstimatedMinutes = plannedMinutes,
            actualMinutes = null,
            note = note.trim(),
            tags = tags,
            placeId = placeId
        )
        val id = repo.addActivity(activity)
        ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
    }

    // la editare păstrăm ghicitul și propunerea inițiale; se schimbă doar ce a modificat utilizatorul
    fun editActivity(
        activityId: Long,
        title: String,
        categoryId: Long?,
        plannedMinutes: Int?,
        startMinute: Int?,
        note: String = "",
        tags: String = "",
        placeId: Long? = null
    ) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        // citim varianta proaspătă din baza de date, ca să nu pierdem timpul cronometrat între timp
        val fresh = repo.getActivity(activityId) ?: return@launch
        val updated = fresh.copy(
            title = title.trim(),
            categoryId = categoryId,
            userEstimatedMinutes = plannedMinutes,
            plannedStartMinute = startMinute,
            note = note.trim(),
            tags = tags,
            placeId = placeId
        )
        repo.updateActivity(updated)
        ActivityReminders.onActivityPlanned(app, updated)
    }

    // valoarea de pornire a estimatorului, din tiparul ales la configurarea inițială
    val priorRatio: Double get() = OnboardingPrefs.priorRatio(app)

    // istoricul pentru estimatorul pe niveluri (doar activități finalizate, din surse sigure)
    suspend fun estimatorHistory(): List<HistoryEstimator.Sample> = repo.estimatorHistory()

    // istoricul orelor de start (și dacă activitatea a fost finalizată), folosit de recomandarea intervalului
    suspend fun slotHistory(categoryId: Long): List<SlotRecommender.Outcome> =
        repo.slotHistory(categoryId)

    fun postponeActivity(activity: Activity, newDate: String) = viewModelScope.launch {
        ActivityReminders.onActivityClosed(app, activity.id)
        // din azi sau din trecut = amânare; dintr-o zi care n-a venit încă = doar replanificare
        val due = !LocalDate.parse(activity.date).isAfter(LocalDate.now())
        repo.postponeActivity(activity.id, newDate, countAsPostponed = due)
        repo.getActivity(activity.id)?.let { ActivityReminders.onActivityPlanned(app, it) }
    }

    fun setState(activity: Activity, state: String) = viewModelScope.launch {
        repo.updateActivity(activity.copy(state = state))
    }

    fun deleteActivity(activity: Activity) = viewModelScope.launch {
        repo.runningSession()
            ?.takeIf { it.activityId == activity.id }
            ?.let { repo.stopTimer(it) }
        repo.deleteActivity(activity)
        ActivityReminders.onActivityClosed(app, activity.id)
    }

    // 6. cronometru și finalizare
    fun toggleTimer(activity: Activity) = viewModelScope.launch {
        val running = repo.runningSession()
        if (running != null && running.activityId == activity.id) {
            repo.stopTimer(running)
            ActivityReminders.onTimerStopped(app, activity.id)
        } else {
            // dacă rula altă activitate, startTimer o oprește; scoatem și notificarea ei
            running?.let { ActivityReminders.onTimerStopped(app, it.activityId) }
            repo.startTimer(activity.id)
            repo.runningSession()?.let { session ->
                ActivityReminders.onTimerStarted(
                    app, repo.getActivity(activity.id) ?: activity,
                    session.startedAt, repo.closedMillis(activity.id)
                )
            }
        }
    }

    fun requestFinish(activity: Activity) = viewModelScope.launch {
        _finishDraft.value = FinishDraft(activity, repo.trackedMinutes(activity.id))
    }

    fun confirmFinish(minutes: Int, state: String) = viewModelScope.launch {
        val draft = _finishDraft.value ?: return@launch
        repo.finishActivity(draft.activity.id, minutes, state)
        ActivityReminders.onActivityClosed(app, draft.activity.id)
        _finishDraft.value = null
    }

    fun dismissFinish() {
        _finishDraft.value = null
    }

    private companion object {
        const val KEY_DISMISSED = "dismissed_date"
    }
}
