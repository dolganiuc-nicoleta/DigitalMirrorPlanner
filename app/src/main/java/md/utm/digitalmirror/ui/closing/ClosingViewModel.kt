package md.utm.digitalmirror.ui.closing

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
import md.utm.digitalmirror.data.entity.DailyActivity
import md.utm.digitalmirror.data.entity.Reflection
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.prefs.MealPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.EveningReview
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.domain.PresenceRules
import md.utm.digitalmirror.domain.SleepPlanner
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.TimelineTravel
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.reminder.ActivityReminders
import java.time.LocalDate
import java.time.ZoneId

private val CLOSED_STATES = setOf("DONE", "PARTIAL", "POSTPONED", "ABANDONED")

@OptIn(ExperimentalCoroutinesApi::class)
class ClosingViewModel(private val app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val planner = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val reflectionDao = db.reflectionDao()
    private val dailyDao = db.dailyActivityDao()

    private val _date = MutableStateFlow<String?>(null)

    val activities: StateFlow<List<Activity>> =
        _date.filterNotNull()
            .flatMapLatest { planner.observeActivitiesForDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // minutele cronometrate pentru fiecare activitate, ca valoare propusă
    private val _tracked = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val tracked: StateFlow<Map<Long, Int>> = _tracked

    // prezențele detectate în ziua aceasta, curățate (vizite unite, cele foarte scurte scoase)
    data class PresenceView(val placeId: Long, val placeName: String, val start: Long, val end: Long) {
        val minutes: Int get() = ((end - start) / 60_000L).toInt()
    }

    private val _presence = MutableStateFlow<Map<Long, Int>>(emptyMap())     // minute pe loc
    private val _presenceViews = MutableStateFlow<List<PresenceView>>(emptyList())
    val presenceViews: StateFlow<List<PresenceView>> = _presenceViews

    private suspend fun loadPresence(date: String) {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse(date)
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val now = System.currentTimeMillis()

        val raw = db.presenceLogDao().overlapping(from, to).map {
            PresenceRules.Visit(it.placeId, it.enterAt, PresenceRules.resolveEnd(it.enterAt, it.exitAt, now))
        }
        // întâi curățăm (unim, scoatem trecerile), apoi tăiem la ziua cerută
        val clipped = PresenceRules.clipTo(PresenceRules.clean(raw), from, to)
        val names = db.placeDao().getAll().associate { it.id to it.name }

        _presence.value = PresenceRules.minutesByPlace(clipped)
        _presenceViews.value = clipped.map {
            PresenceView(it.placeId, names[it.placeId] ?: "loc șters", it.start, it.end)
        }
    }

    private val _reflection = MutableStateFlow<Reflection?>(null)
    val reflection: StateFlow<Reflection?> = _reflection

    // somnul din noaptea dinaintea zilei (din Health Connect sau introdus anterior)
    private val _daily = MutableStateFlow<DailyActivity?>(null)
    val daily: StateFlow<DailyActivity?> = _daily

    fun load(date: String) {
        _date.value = date
        viewModelScope.launch {
            _reflection.value = reflectionDao.getByDate(date)
            _daily.value = dailyDao.getByDate(date)
            loadPresence(date)
            val list = planner.observeActivitiesForDate(date).first()
            _tracked.value = list.associate { it.id to planner.trackedMinutes(it.id) }
        }
    }

    // ---------- confirmarea activităților, precompletată ----------

    /**
     * Propunerea pentru o activitate încă deschisă.
     * source spune de unde vine durata: TIMER (cronometru), AS_PLANNED (copiată din plan), MANUAL (corectată de om).
     */
    data class Draft(
        val activity: Activity,
        val state: String,
        val minutes: Int?,
        val source: String?,
        val trackedMinutes: Int
    )

    // ce a modificat utilizatorul; restul activităților păstrează propunerea implicită
    private val _edits = MutableStateFlow<Map<Long, Draft>>(emptyMap())

    val drafts: StateFlow<List<Draft>> =
        combine(activities, _tracked, _edits, _presence) { list, tracked, edits, presence ->
            val open = list.filter { it.state !in CLOSED_STATES }
            // câte activități deschise are fiecare loc: durata din locație o propunem doar dacă e una singură
            val perPlace = open.mapNotNull { it.placeId }.groupingBy { it }.eachCount()
            open.map { a -> edits[a.id] ?: defaultDraft(a, tracked[a.id] ?: 0, presence, perPlace) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun defaultDraft(a: Activity, tracked: Int, presence: Map<Long, Int>, perPlace: Map<Long, Int>): Draft {
        val planned = a.userEstimatedMinutes ?: a.estimatedMinutes
        // cu două activități la același loc nu știm cum se împarte prezența, deci nu propunem nimic
        val located = a.placeId?.takeIf { perPlace[it] == 1 }?.let { presence[it] }?.takeIf { it > 0 }
        return when {
            tracked > 0 -> Draft(a, "DONE", tracked, Activity.SOURCE_TIMER, tracked)
            located != null -> Draft(a, "DONE", located, Activity.SOURCE_LOCATION, 0)
            planned != null -> Draft(a, "DONE", planned, Activity.SOURCE_AS_PLANNED, 0)
            else -> Draft(a, "DONE", null, null, 0)
        }
    }

    fun changeState(draft: Draft, state: String) {
        _edits.value = _edits.value + (draft.activity.id to draft.copy(state = state))
    }

    // dacă utilizatorul corectează durata, ea devine introdusă manual
    fun changeMinutes(draft: Draft, minutes: Int?) {
        _edits.value = _edits.value + (draft.activity.id to draft.copy(minutes = minutes, source = Activity.SOURCE_MANUAL))
    }

    /** „Totul corect”: confirmă toate activitățile deschise, cu propunerile (eventual corectate). */
    fun confirmAll() = viewModelScope.launch {
        val date = _date.value ?: return@launch
        drafts.value.forEach { d ->
            val id = d.activity.id
            ActivityReminders.onActivityClosed(app, id)
            when (d.state) {
                // amânată → se mută pe ziua următoare, ca în meniul ⋮
                "POSTPONED" -> {
                    planner.postponeActivity(id, LocalDate.parse(date).plusDays(1).toString())
                    planner.getActivity(id)?.let { ActivityReminders.onActivityPlanned(app, it) }
                }
                "ABANDONED" -> planner.closeActivity(id, d.trackedMinutes.takeIf { it > 0 }, "ABANDONED",
                    if (d.trackedMinutes > 0) Activity.SOURCE_TIMER else null)
                else -> planner.closeActivity(id, d.minutes ?: 0, d.state, d.source)
            }
        }
        _edits.value = emptyMap()
    }

    /**
     * „Închide ziua”: întâi confirmă activitățile (cele amânate trec pe mâine), apoi salvează reflecția.
     * În ordinea asta, ca rezumatul de mâine să le vadă deja mutate.
     */
    fun closeDay(
        mood: Int, workedOn: String, blockers: String, note: String,
        bedtime: Int?, wake: Int?, sleepEdited: Boolean,
        onDone: () -> Unit
    ) = viewModelScope.launch {
        confirmAll().join()
        save(mood, workedOn, blockers, note, bedtime, wake, sleepEdited, onDone).join()
    }

    /**
     * Salvează reflecția și, dacă utilizatorul a completat sau a modificat somnul, și somnul.
     * bedtime / wake sunt minute de la miezul nopții; sleepEdited = true doar dacă le-a atins utilizatorul.
     */
    fun save(
        mood: Int,
        workedOn: String,
        blockers: String,
        note: String,
        bedtime: Int?,
        wake: Int?,
        sleepEdited: Boolean,
        onDone: () -> Unit
    ) = viewModelScope.launch {
        val date = _date.value ?: return@launch

        val reflection = Reflection(
            date = date,
            moodScore = mood,
            workedOn = workedOn.trim(),
            blockers = blockers.trim(),
            note = note.trim()
        )
        reflectionDao.upsert(reflection)
        _reflection.value = reflection

        if (sleepEdited && bedtime != null && wake != null) {
            val existing = dailyDao.getByDate(date)
            dailyDao.upsert(
                (existing ?: DailyActivity(date = date, steps = null, activeMinutes = null)).copy(
                    sleepMinutes = sleepDuration(bedtime, wake),
                    bedtimeMinute = bedtime,
                    wakeMinute = wake,
                    sleepSource = "MANUAL"
                )
            )
        }
        onDone()
    }

    // ---------- pasul 2: ziua de mâine ----------

    /** Rezumatul zilei de mâine și întrebările (doar la excepții). */
    data class Tomorrow(
        val date: LocalDate,
        val sleep: SleepPlanner.Plan,              // trezirea de mâine
        val first: EveningReview.Item?,            // primul lucru de mâine
        val firstLeg: TimelineTravel.Leg?,         // prima plecare de acasă (pregătire, drum)
        val meals: List<MealPlanner.Slot>,
        val issues: List<EveningReview.Issue>
    ) {
        /** Ora de culcare din seara asta (peste 24:00 dacă e după miezul nopții). */
        val bedtimeTonight: Int get() = sleep.bedtimeBefore
    }

    private val _tomorrow = MutableStateFlow<Tomorrow?>(null)
    val tomorrow: StateFlow<Tomorrow?> = _tomorrow

    val places: StateFlow<List<md.utm.digitalmirror.data.entity.Place>> =
        db.placeDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val matrix by lazy { TravelMatrix(app) }

    fun loadTomorrow() = viewModelScope.launch {
        val today = LocalDate.parse(_date.value ?: return@launch)
        val next = today.plusDays(1)

        val links = DayBusy.calendarPlaces(app)
        val activities = planner.observeActivitiesForDate(next.toString()).first()
            .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
        val events = CalendarReader.eventsFor(app, next).filter { it.busy && !it.allDay }
        val daysOff = db.availabilityDao().observeDaysOff().first().map { it.date }.toSet()
        val blocks = Availability.blocksFor(next, DayBusy.rules(app), daysOff)

        // ce e mâine, cu oră; flexibil = pus de aplicație dintr-un plan (se poate muta)
        val items = activities.mapNotNull { a ->
            a.plannedStartMinute?.let {
                EveningReview.Item(
                    it, it + (a.userEstimatedMinutes ?: a.estimatedMinutes ?: 30), a.title,
                    flexible = a.weekTaskId != null || a.prepStepId != null,
                    activityId = a.id, placeId = a.placeId
                )
            }
        } + events.map {
            EveningReview.Item(it.startMinute, it.endMinute, it.title, flexible = false, placeId = links[TitleKey.of(it.title)])
        } + blocks.map { EveningReview.Item(it.start, it.end, it.title, flexible = false, placeId = it.placeId) }

        val busy = DayBusy.forDate(app, next)
        val skeleton = matrix.skeleton(busy, next)
        val sleep = DayBusy.sleepPlan(app, next, DayBusy.firstStart(busy, skeleton))

        // cât e planificat mâine față de cât reușești de obicei
        val load = if (AnalysisPrefs.isEnabled(app) && next.toString() !in daysOff) {
            val planned = activities.sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
            DayLoad.compute(planned, planner.dailyTotals(today.toString()), next)
        } else null

        val hasPlaces = db.placeDao().getAll().isNotEmpty()
        val issues = EveningReview.issues(
            items = items,
            sleep = sleep,
            window = DayBusy.window(app, next),
            mealBusy = skeleton.meals.slots.map { SlotRecommender.Busy(it.start, it.end) },
            missingMeals = skeleton.meals.missing,
            eventsWithoutPlace = if (hasPlaces) events.filter { links[TitleKey.of(it.title)] == null }.map { it.title } else emptyList(),
            load = load
        )

        _tomorrow.value = Tomorrow(
            date = next,
            sleep = sleep,
            first = items.minByOrNull { it.start },
            firstLeg = skeleton.legs.firstOrNull { it.prepMinutes > 0 || it.fromPlaceId == matrix.homePlaceId },
            meals = skeleton.meals.slots,
            issues = issues
        )
    }

    /** Mută o activitate de mâine la altă oră (răspunsul la „O mut mai târziu?”). */
    fun moveTomorrow(activityId: Long, start: Int) = viewModelScope.launch {
        val a = planner.getActivity(activityId) ?: return@launch
        val moved = a.copy(plannedStartMinute = start)
        planner.updateActivity(moved)
        ActivityReminders.onActivityPlanned(app, moved)
        loadTomorrow()
    }

    /** Locul unui eveniment din calendar (răspunsul la „Unde e C.PAD?”), pentru toate cu același nume. */
    fun setEventPlace(title: String, placeId: Long) = viewModelScope.launch {
        db.calendarPlaceDao().upsert(CalendarPlace(TitleKey.of(title), placeId, CalendarPlace.SOURCE_USER))
        loadTomorrow()
    }

    companion object {
        // de la culcare la trezire, trecând peste miezul nopții: 23:30 → 07:00 = 450 min
        fun sleepDuration(bedtime: Int, wake: Int): Int = (wake - bedtime + 24 * 60) % (24 * 60)
    }
}