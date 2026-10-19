package md.utm.digitalmirror.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.CategoryGuess
import md.utm.digitalmirror.domain.DayPlanner
import md.utm.digitalmirror.domain.HistoryEstimator
import md.utm.digitalmirror.domain.PlaceGuess
import md.utm.digitalmirror.domain.PlanParser
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import java.time.LocalDate
import java.time.LocalTime

/**
 * Planificarea unei zile dintr-un text scris liber: se extrag activitățile, li se ghicește categoria,
 * se stabilește durata (spusă de utilizator sau estimată din istoric) și se așază pe ore,
 * ocolind activitățile, calendarul și blocurile fixe. Ce nu mai încape se propune pentru ziua următoare.
 */
class PlanViewModel(private val app: Application) : AndroidViewModel(app) {

    enum class DurationSource { STATED, ESTIMATED, DEFAULT }

    data class Proposal(
        val index: Int,
        val title: String,
        val category: Category?,
        val minutes: Int,
        val source: DurationSource,
        val historyMinutes: Int?,                 // ce arată istoricul, când diferă mult de durata spusă
        val dayPart: PlanParser.DayPart?,
        val placement: DayPlanner.Placement,
        val placeId: Long?,                       // locul ghicit din text/istoric sau ales de utilizator
        val include: Boolean
    )

    data class UiState(
        val date: LocalDate = LocalDate.now().plusDays(1),
        val dateFromText: Boolean = false,
        val proposals: List<Proposal>? = null,    // null = încă nu s-a cerut un plan
        val working: Boolean = false
    ) {
        val placed: List<Proposal> get() = proposals.orEmpty().filter { it.placement.placed }
        val unplaced: List<Proposal> get() = proposals.orEmpty().filterNot { it.placement.placed }
    }

    private val db = AppDatabase.get(app)
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val analysisOn = AnalysisPrefs.isEnabled(app)

    /** Locurile salvate, pentru lista „Loc” de la fiecare activitate. */
    val places: StateFlow<List<Place>> = db.placeDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    // ce s-a calculat la ultima analiză, ca schimbările mici (bifă, durată, dată) să nu refacă totul
    private class Work(
        val text: String,
        val parsed: PlanParser.Result,
        val categories: List<Category>,
        val known: List<CategoryGuess.Known>,
        val history: List<HistoryEstimator.Sample>,
        val slotHistory: Map<Long, List<SlotRecommender.Outcome>>,
        val placeList: List<PlaceGuess.Named>,
        val placeHistory: List<PlaceGuess.Known>
    )
    private var work: Work? = null
    private var dateOverride: LocalDate? = null
    private val minutesOverride = mutableMapOf<Int, Int>()
    private val excluded = mutableSetOf<Int>()
    private val placeOverride = mutableMapOf<Int, Long?>()   // alegerea explicită, inclusiv „fără loc”

    /** Textul nou: analiză de la zero. */
    fun analyze(text: String) = viewModelScope.launch {
        _state.value = _state.value.copy(working = true)
        minutesOverride.clear()
        excluded.clear()
        placeOverride.clear()

        val today = LocalDate.now()
        val parsed = PlanParser.parse(text, today)
        val categories = repo.observeCategories().first()
        val activities = repo.observeActivitiesInRange("0000-01-01", "9999-12-31").first()
        val known = activities.mapNotNull { a ->
            val categoryId = a.categoryId ?: return@mapNotNull null
            CategoryGuess.Known(a.titleKey ?: TitleKey.of(a.title), categoryId)
        }
        val history = repo.estimatorHistory()

        // istoricul orelor, o singură dată pentru fiecare categorie din plan
        val categoryIds = parsed.drafts
            .mapNotNull { CategoryGuess.guess(it.title, categories.map { c -> CategoryGuess.Cat(c.id, c.name) }, known) }
            .distinct()
        val slotHistory = categoryIds.associateWith { repo.slotHistory(it) }

        // locuri și istoricul lor (cele mai noi activități primele)
        val placeList = db.placeDao().getAll().map { PlaceGuess.Named(it.id, it.name) }
        val placeHistory = activities.sortedByDescending { it.id }.mapNotNull { a ->
            val placeId = a.placeId ?: return@mapNotNull null
            PlaceGuess.Known(a.titleKey ?: TitleKey.of(a.title), placeId)
        }

        work = Work(text, parsed, categories, known, history, slotHistory, placeList, placeHistory)
        recompute()
    }

    /** Data aleasă de utilizator are prioritate față de ziua din text. */
    fun setDate(date: LocalDate) {
        dateOverride = date
        if (work != null) viewModelScope.launch { recompute() }
    }

    fun setIncluded(index: Int, include: Boolean) {
        if (include) excluded.remove(index) else excluded.add(index)
        _state.value = _state.value.copy(
            proposals = _state.value.proposals?.map { if (it.index == index) it.copy(include = include) else it }
        )
    }

    /** Locul ales de utilizator pentru o activitate; null = fără loc. */
    fun setPlace(index: Int, placeId: Long?) {
        placeOverride[index] = placeId
        _state.value = _state.value.copy(
            proposals = _state.value.proposals?.map { if (it.index == index) it.copy(placeId = placeId) else it }
        )
    }

    /** Folosește durata din istoric în locul celei spuse. */
    fun useHistoryMinutes(index: Int) {
        val proposal = _state.value.proposals?.firstOrNull { it.index == index } ?: return
        proposal.historyMinutes?.let { minutesOverride[index] = it }
        viewModelScope.launch { recompute() }
    }

    private suspend fun recompute() {
        val w = work ?: return
        val today = LocalDate.now()
        val date = dateOverride ?: w.parsed.date ?: today.plusDays(1)
        val categoryList = w.categories.map { CategoryGuess.Cat(it.id, it.name) }

        // 1. fiecare activitate: categorie, durată
        class Prepared(
            val draft: PlanParser.Draft, val category: Category?, val minutes: Int,
            val source: DurationSource, val historyMinutes: Int?
        )
        val prepared = w.parsed.drafts.mapIndexed { index, draft ->
            val categoryId = CategoryGuess.guess(draft.title, categoryList, w.known)
            val estimate = if (analysisOn)
                HistoryEstimator.estimate(w.history, TitleKey.of(draft.title), categoryId, draft.durationMinutes, OnboardingPrefs.priorRatio(app))
            else null

            val stated = draft.durationMinutes
            val chosen = minutesOverride[index]
            val (minutes, source) = when {
                chosen != null -> chosen to DurationSource.ESTIMATED
                stated != null -> stated to DurationSource.STATED
                estimate != null -> estimate.minutes to DurationSource.ESTIMATED
                else -> DEFAULT_MINUTES to DurationSource.DEFAULT
            }
            // istoricul contează doar dacă are date reale și diferă clar de ce a spus utilizatorul
            val hint = estimate?.takeIf { stated != null && chosen == null && it.level != HistoryEstimator.Level.PRIOR }
                ?.minutes?.takeIf { kotlin.math.abs(it - stated!!) * 4 >= stated!! }

            Prepared(draft, w.categories.firstOrNull { it.id == categoryId }, minutes, source, hint)
        }

        // 2. ce e deja ocupat în ziua aleasă
        val busy = busyFor(date)
        val earliest = if (date == today) {
            val now = LocalTime.now()
            maxOf(7 * 60, now.hour * 60 + now.minute + 10)
        } else 7 * 60

        // 3. așezarea pe ore
        val placements = DayPlanner.place(
            prepared.map {
                DayPlanner.Item(
                    title = it.draft.title,
                    minutes = it.minutes,
                    startMinute = it.draft.startMinute,
                    dayPart = it.draft.dayPart,
                    history = it.category?.let { c -> w.slotHistory[c.id] }.orEmpty()
                )
            },
            busy,
            earliest
        )

        _state.value = UiState(
            date = date,
            dateFromText = dateOverride == null && w.parsed.date != null,
            proposals = prepared.mapIndexed { index, p ->
                Proposal(
                    index = index,
                    title = p.draft.title,
                    category = p.category,
                    minutes = p.minutes,
                    source = p.source,
                    historyMinutes = p.historyMinutes,
                    dayPart = p.draft.dayPart,
                    placement = placements[index],
                    placeId = if (index in placeOverride) placeOverride[index]
                    else PlaceGuess.guess(p.draft.title, w.placeList, w.placeHistory),
                    include = index !in excluded
                )
            },
            working = false
        )
    }

    // activități, calendar și blocuri fixe din ziua aleasă
    private suspend fun busyFor(date: LocalDate): List<SlotRecommender.Busy> {
        val activities = repo.observeActivitiesForDate(date.toString()).first()
            .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            .mapNotNull { a ->
                a.plannedStartMinute?.let { SlotRecommender.Busy(it, it + (a.userEstimatedMinutes ?: 30)) }
            }
        val calendar = CalendarReader.eventsFor(app, date).filter { it.busy }
            .map { SlotRecommender.Busy(it.startMinute, it.endMinute) }

        val availability = db.availabilityDao()
        val rules = availability.observeRules().first().map {
            Availability.Rule(
                it.id, it.title, it.daysMask, it.startMinute, it.endMinute,
                it.travelBeforeMinutes, it.travelAfterMinutes
            )
        }
        val daysOff = availability.observeDaysOff().first().map { it.date }.toSet()
        val blocks = Availability.blocksFor(date, rules, daysOff)
            .map { SlotRecommender.Busy(it.busyStart, it.busyEnd) }

        return activities + calendar + blocks
    }

    /** Adaugă în plan ce a bifat utilizatorul. Ce nu încape ajunge în ziua următoare, fără oră. */
    fun confirm(onDone: (String) -> Unit) = viewModelScope.launch {
        val current = _state.value
        val w = work ?: return@launch
        val chosen = current.proposals.orEmpty().filter { it.include }
        var addedToday = 0
        var postponed = 0

        for (p in chosen) {
            val draft = w.parsed.drafts[p.index]
            val placed = p.placement.placed
            val day = if (placed) current.date else current.date.plusDays(1)
            val estimate = if (analysisOn)
                HistoryEstimator.estimate(
                    w.history, TitleKey.of(draft.title), p.category?.id, draft.durationMinutes,
                    OnboardingPrefs.priorRatio(app)
                )
            else null

            val activity = Activity(
                title = draft.title,
                categoryId = p.category?.id,
                date = day.toString(),
                plannedStartMinute = if (placed) p.placement.startMinute else null,
                userGuessMinutes = draft.durationMinutes,          // ce a spus utilizatorul, înainte de orice propunere
                estimatedMinutes = estimate?.minutes,
                estimatedLowMinutes = estimate?.lowMinutes,
                estimatedHighMinutes = estimate?.highMinutes,
                userEstimatedMinutes = p.minutes,
                actualMinutes = null,
                placeId = p.placeId
            )
            val id = repo.addActivity(activity)
            ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
            if (placed) addedToday++ else postponed++
        }

        onDone(
            when {
                chosen.isEmpty() -> "Nu ai bifat nicio activitate."
                postponed == 0 -> "Am adăugat $addedToday ${if (addedToday == 1) "activitate" else "activități"}."
                else -> "Am adăugat $addedToday pentru ziua aleasă și $postponed pentru ziua următoare."
            }
        )
    }

    private companion object {
        const val DEFAULT_MINUTES = 30
    }
}