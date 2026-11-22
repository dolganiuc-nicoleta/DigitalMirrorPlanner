package md.utm.digitalmirror.ui.plan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.WeekTask
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.CategoryGuess
import md.utm.digitalmirror.domain.DayPlanner
import md.utm.digitalmirror.domain.HistoryEstimator
import md.utm.digitalmirror.domain.PeriodReport
import md.utm.digitalmirror.domain.PlaceGuess
import md.utm.digitalmirror.domain.PlanParser
import md.utm.digitalmirror.domain.PrepTemplates
import md.utm.digitalmirror.domain.QuickCapture
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import md.utm.digitalmirror.ui.prep.PrepScheduler
import java.time.LocalDate
import java.time.LocalTime

/**
 * „Ce ai de făcut?”: un singur câmp. Textul se citește pe măsură ce scrii (QuickCapture), iar la confirmare
 * fiecare lucru merge unde trebuie: activitățile pe ore în ziua lor (cu drumuri, mese, trezire),
 * sarcinile în planul săptămânii, evenimentele cu pașii de pregătire așezați până în ajun.
 */
@OptIn(FlowPreview::class)
class CaptureViewModel(private val app: Application) : AndroidViewModel(app) {

    data class UiState(
        val text: String = "",
        val items: List<QuickCapture.Item> = emptyList(),
        val working: Boolean = false
    ) {
        /** Un eveniment fără zi nu se poate pregăti: trebuie aleasă ziua înainte de confirmare. */
        val ready: Boolean get() = items.isNotEmpty() && items.none { it.kind == QuickCapture.Kind.EVENT && it.date == null }
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val text = MutableStateFlow("")
    // alegerile utilizatorului, după bucata de text: alt tip, altă zi pentru eveniment
    private val kindOverride = mutableMapOf<String, QuickCapture.Kind>()
    private val dateOverride = mutableMapOf<String, LocalDate>()

    private val db = AppDatabase.get(app)
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val matrix = TravelMatrix(app)
    private val analysisOn = AnalysisPrefs.isEnabled(app)

    init {
        // citim textul după o scurtă pauză în scris, nu la fiecare literă
        viewModelScope.launch { text.debounce(250).collect { reparse() } }
    }

    fun setText(value: String) {
        _state.value = _state.value.copy(text = value)
        text.value = value
    }

    /** Adaugă text dictat la ce era deja scris. */
    fun appendDictated(spoken: String) {
        val current = _state.value.text.trimEnd()
        setText(if (current.isEmpty()) spoken else "$current, $spoken")
    }

    /** Eticheta: Zi → Săptămâna → Eveniment → Zi. */
    fun cycleKind(item: QuickCapture.Item) {
        val next = when (item.kind) {
            QuickCapture.Kind.DAY -> QuickCapture.Kind.WEEK
            QuickCapture.Kind.WEEK -> QuickCapture.Kind.EVENT
            QuickCapture.Kind.EVENT -> QuickCapture.Kind.DAY
        }
        kindOverride[item.raw] = next
        reparse()
    }

    fun setEventDate(item: QuickCapture.Item, date: LocalDate) {
        dateOverride[item.raw] = date
        reparse()
    }

    private fun reparse() {
        val today = LocalDate.now()
        val raw = text.value
        // bucățile, apoi pentru fiecare tipul ales de utilizator (dacă a ales) și ziua aleasă (pentru evenimente)
        val items = QuickCapture.parse(raw, today).map { it.raw }.distinct().flatMap { piece ->
            val read = kindOverride[piece]?.let { QuickCapture.read(piece, today, it) } ?: QuickCapture.read(piece, today)
            read.map { item -> dateOverride[piece]?.takeIf { item.kind == QuickCapture.Kind.EVENT }?.let { item.copy(date = it) } ?: item }
        }
        _state.value = _state.value.copy(items = items)
    }

    // ---- „Pune-le în plan” ----

    fun confirm(onDone: (String) -> Unit) = viewModelScope.launch {
        val current = _state.value
        if (!current.ready || current.working) return@launch
        _state.value = current.copy(working = true)

        val today = LocalDate.now()
        val dayItems = current.items.filter { it.kind == QuickCapture.Kind.DAY }
        val weekItems = current.items.filter { it.kind == QuickCapture.Kind.WEEK }
        val eventItems = current.items.filter { it.kind == QuickCapture.Kind.EVENT }

        // ce știm despre trecut: categorii, durate, locuri
        val categories = repo.observeCategories().first()
        val catList = categories.map { CategoryGuess.Cat(it.id, it.name) }
        val activities = repo.observeActivitiesInRange("0000-01-01", "9999-12-31").first()
        val known = activities.mapNotNull { a -> a.categoryId?.let { CategoryGuess.Known(a.titleKey ?: TitleKey.of(a.title), it) } }
        val placeList = db.placeDao().getAll().map { PlaceGuess.Named(it.id, it.name) }
        val placeHistory = activities.sortedByDescending { it.id }.mapNotNull { a ->
            a.placeId?.let { PlaceGuess.Known(a.titleKey ?: TitleKey.of(a.title), it) }
        }

        // 1. activitățile, pe ore, zi cu zi
        var placedCount = 0
        var movedCount = 0
        for ((date, items) in dayItems.groupBy { it.date ?: today }) {
            val (placed, moved) = placeDay(date, items, catList, known, placeList, placeHistory)
            placedCount += placed
            movedCount += moved
        }

        // 2. sarcinile săptămânii (se împart pe zile din fila Săptămâna)
        val weekStart = PeriodReport.weekStart(today)
        for (w in weekItems) {
            val total = w.minutes ?: DEFAULT_WEEK_MINUTES
            weekDao().insert(
                WeekTask(
                    title = w.title,
                    categoryId = CategoryGuess.guess(w.title, catList, known),
                    weekStart = weekStart.toString(),
                    totalMinutes = total,
                    sessionMinutes = (w.sessionMinutes ?: DEFAULT_SESSION).coerceAtMost(total),
                    deadline = w.deadline?.toString(),
                    pinnedDate = null
                )
            )
        }

        // 3. evenimentele, cu pașii de pregătire așezați până în ajun
        var prepSessions = 0
        val scheduler = PrepScheduler(app)
        for (e in eventItems) {
            val date = e.date ?: continue
            val template = PrepTemplates.byKey(e.templateKey ?: "other")
            val (_, outcome) = scheduler.create(e.title, date, PrepTemplates.build(template, template.defaultMinutes))
            prepSessions += outcome.sessions
        }

        onDone(summary(placedCount, movedCount, weekItems.size, eventItems.size, prepSessions))
        _state.value = UiState()
        kindOverride.clear()
        dateOverride.clear()
        text.value = ""
    }

    private fun weekDao() = db.weekTaskDao()

    /** Așază activitățile unei zile; ce nu încape trece pe ziua următoare, fără oră. Returnează (așezate, mutate). */
    private suspend fun placeDay(
        date: LocalDate,
        items: List<QuickCapture.Item>,
        catList: List<CategoryGuess.Cat>,
        known: List<CategoryGuess.Known>,
        placeList: List<PlaceGuess.Named>,
        placeHistory: List<PlaceGuess.Known>
    ): Pair<Int, Int> {
        val today = LocalDate.now()
        val history = repo.estimatorHistory()

        class Prepared(val item: QuickCapture.Item, val categoryId: Long?, val minutes: Int, val estimate: HistoryEstimator.Estimate?, val placeId: Long?)
        val prepared = items.map { item ->
            val categoryId = CategoryGuess.guess(item.title, catList, known)
            val estimate = if (analysisOn)
                HistoryEstimator.estimate(history, TitleKey.of(item.title), categoryId, item.minutes, OnboardingPrefs.priorRatio(app))
            else null
            Prepared(
                item, categoryId,
                minutes = item.minutes ?: estimate?.minutes ?: DEFAULT_MINUTES,
                estimate = estimate,
                placeId = PlaceGuess.guess(item.title, placeList, placeHistory)
            )
        }

        // ziua: ce e ocupat (cu locuri și mese), intervalul dintre trezire și culcare, drumurile
        val busy = DayBusy.forDate(app, date).let { b -> b + matrix.skeleton(b, date).meals.slots.map { SlotRecommender.Busy(it.start, it.end) } }
        val (dayStart, dayEnd) = DayBusy.window(app, date)
        val earliest = if (date == today) LocalTime.now().let { maxOf(dayStart, it.hour * 60 + it.minute + 10) } else dayStart
        val home = TravelPrefs.homePlaceId(app)
        val travelPlaces = prepared.map { it.placeId ?: home }
        val travel = matrix.forPlanning(travelPlaces.filterNotNull(), busy.mapNotNull { it.placeId })

        val placements = DayPlanner.place(
            prepared.mapIndexed { i, p ->
                DayPlanner.Item(
                    title = p.item.title,
                    minutes = p.minutes,
                    startMinute = p.item.startMinute,
                    dayPart = p.item.dayPart,
                    history = p.categoryId?.let { repo.slotHistory(it) }.orEmpty(),
                    placeId = travelPlaces[i]
                )
            },
            busy, earliest, latestEnd = dayEnd, travel = travel
        )

        var placed = 0
        var moved = 0
        prepared.forEachIndexed { i, p ->
            val placement = placements[i]
            val day = if (placement.placed) date else date.plusDays(1)
            val activity = Activity(
                title = p.item.title,
                categoryId = p.categoryId,
                date = day.toString(),
                plannedStartMinute = placement.startMinute,
                userGuessMinutes = p.item.minutes,
                estimatedMinutes = p.estimate?.minutes,
                estimatedLowMinutes = p.estimate?.lowMinutes,
                estimatedHighMinutes = p.estimate?.highMinutes,
                userEstimatedMinutes = p.minutes,
                actualMinutes = null,
                placeId = p.placeId
            )
            val id = repo.addActivity(activity)
            ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
            if (placement.placed) placed++ else moved++
        }
        return placed to moved
    }

    private fun summary(placed: Int, moved: Int, weekTasks: Int, events: Int, prepSessions: Int): String {
        val parts = mutableListOf<String>()
        if (placed > 0) parts += if (placed == 1) "1 activitate" else "$placed activități"
        if (weekTasks > 0) parts += if (weekTasks == 1) "1 sarcină pe săptămână" else "$weekTasks sarcini pe săptămână"
        if (events > 0) parts += (if (events == 1) "1 eveniment" else "$events evenimente") +
                if (prepSessions > 0) " cu $prepSessions sesiuni de pregătire" else ""
        val main = if (parts.isEmpty()) "Nu era nimic de pus în plan." else "Am pus în plan: ${parts.joinToString(", ")}."
        val extra = buildList {
            if (moved > 0) add("$moved n-au încăput și au trecut pe ziua următoare, fără oră.")
            if (weekTasks > 0) add("Sarcinile se împart pe zile din fila Săptămâna.")
        }
        return (listOf(main) + extra).joinToString(" ")
    }

    private companion object {
        const val DEFAULT_MINUTES = 30
        const val DEFAULT_WEEK_MINUTES = 2 * 60
        const val DEFAULT_SESSION = 90
    }
}
