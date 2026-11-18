package md.utm.digitalmirror.ui.week

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.WeekTask
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.CategoryGuess
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.PeriodReport
import md.utm.digitalmirror.domain.PlaceGuess
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.domain.WeekPlanner
import md.utm.digitalmirror.domain.WeekScenario
import md.utm.digitalmirror.reminder.ActivityReminders
import java.time.LocalDate
import java.time.LocalTime

/**
 * Planul săptămânii: sarcini cu timp total, sesiuni, termen și zi fixată; aplicația le distribuie pe zile
 * după ce e deja ocupat, cât reușești de obicei într-o zi și cât ai reușit în ultimele săptămâni.
 * Planul se calculează mereu din nou (previzualizare); „Redistribuie” îl scrie în activitățile zilelor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WeekViewModel(private val app: Application) : AndroidViewModel(app) {

    enum class Scope { WEEK, TODAY }

    data class TaskRow(
        val task: WeekTask,
        val result: WeekPlanner.TaskResult?,   // null = nimic de planificat (terminată)
        val doneMinutes: Int,
        val remainingMinutes: Int
    )

    data class DayCell(
        val date: LocalDate,
        val minutes: Int,                      // tot ce e planificat în zi, inclusiv propunerea
        val budget: Int,
        val dayOff: Boolean,
        val risky: Boolean
    )

    data class UiState(
        val weekStart: LocalDate = PeriodReport.weekStart(LocalDate.now()),
        val offset: Int = 0,
        val scope: Scope = Scope.WEEK,
        val tasks: List<TaskRow> = emptyList(),
        val sessions: List<WeekPlanner.Session> = emptyList(),
        val unplaced: List<WeekPlanner.Unplaced> = emptyList(),
        val days: List<DayCell> = emptyList(),
        val chancePercent: Int? = null,
        val riskyDay: LocalDate? = null,
        val notApplied: Boolean = false,       // propunerea diferă de ce e deja în zile
        val scenarioText: String = "",
        val scenarioBlocks: List<WeekScenario.Block> = emptyList(),
        val scenarioIgnored: List<String> = emptyList(),
        val categories: List<Category> = emptyList(),
        val analysisOn: Boolean = true
    )

    private val db = AppDatabase.get(app)
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val weekDao = db.weekTaskDao()
    private val availability = db.availabilityDao()
    private val analysisOn = AnalysisPrefs.isEnabled(app)
    private val travelMatrix = TravelMatrix(app)

    private val offset = MutableStateFlow(0)                 // 0 = săptămâna curentă, 1 = următoarea
    private val scope = MutableStateFlow(Scope.WEEK)
    private val scenarioText = MutableStateFlow("")

    private class Data(
        val tasks: List<WeekTask>,
        val activities: List<Activity>,
        val rules: List<Availability.Rule>,
        val daysOff: Set<String>,
        val categories: List<Category>
    )

    private fun weekStart(off: Int) = PeriodReport.weekStart(LocalDate.now()).plusWeeks(off.toLong())

    private val data = offset.flatMapLatest { off ->
        val start = weekStart(off)
        combine(
            weekDao.observeForWeek(start.toString()),
            repo.observeActivitiesInRange(start.toString(), start.plusDays(6).toString()),
            availability.observeRules(),
            availability.observeDaysOff(),
            repo.observeCategories()
        ) { tasks, activities, rules, daysOff, categories ->
            Data(
                tasks, activities,
                rules.map {
                    Availability.Rule(
                        it.id, it.title, it.daysMask, it.startMinute, it.endMinute,
                        it.travelBeforeMinutes, it.travelAfterMinutes, it.placeId
                    )
                },
                daysOff.map { it.date }.toSet(),
                categories
            )
        }
    }

    val state: StateFlow<UiState> =
        combine(combine(offset, scope, scenarioText) { o, s, t -> Triple(o, s, t) }, data) { (o, s, t), d ->
            Triple(o, s, t) to d
        }.mapLatest { (controls, d) -> compute(controls.first, controls.second, controls.third, d) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    // ---- calculul planului ----

    private suspend fun compute(off: Int, scopeNow: Scope, text: String, d: Data): UiState {
        val today = LocalDate.now()
        val start = weekStart(off)
        val week = (0..6).map { start.plusDays(it.toLong()) }
        val scenario = WeekScenario.parse(text, start, today)

        val taskIds = d.tasks.map { it.id }.toSet()
        fun movable(a: Activity): Boolean {
            if (a.weekTaskId == null || a.weekTaskId !in taskIds || a.state != "PLANNED") return false
            val date = LocalDate.parse(a.date)
            return if (scopeNow == Scope.WEEK) !date.isBefore(today) else date == today
        }
        val movableList = d.activities.filter(::movable)
        val fixed = d.activities.filter { !movable(it) && it.state != "ABANDONED" && it.state != "POSTPONED" }

        // cât s-a lucrat deja la fiecare sarcină
        val done = d.tasks.associate { t ->
            t.id to d.activities.filter { it.weekTaskId == t.id && it.state in setOf("DONE", "PARTIAL") }
                .sumOf { it.actualMinutes ?: 0 }
        }

        // ce poate fi planificat: azi și zilele următoare (sau doar azi)
        val planDays = when {
            scopeNow == Scope.TODAY -> listOf(today).filter { it in week }
            else -> week.filter { !it.isBefore(today) }
        }

        val totals = if (analysisOn) repo.dailyTotals(today.toString()).filter { it.date.toString() !in d.daysOff } else emptyList()
        val earliestToday = LocalTime.now().let { maxOf(8 * 60, it.hour * 60 + it.minute + 10) }
        val calendarPlaces = DayBusy.calendarPlaces(app)

        val inputs = planDays.map { date ->
            val busy = mutableListOf<SlotRecommender.Busy>()
            val activitiesToday = fixed.filter { it.date == date.toString() }
            // cu locul fiecărui lucru, ca sesiunile să lase timp de drum
            busy += DayBusy.activities(activitiesToday)
            busy += DayBusy.calendar(app, date, calendarPlaces)
            busy += DayBusy.blocks(date, d.rules, d.daysOff)
            busy += scenario.blocks.filter { it.date == date }.map { SlotRecommender.Busy(it.fromMinute, it.toMinute) }
            // mesele din ferestrele tale: sesiunile nu se pun peste ele
            busy += travelMatrix.skeleton(busy.toList()).meals.slots.map { SlotRecommender.Busy(it.start, it.end) }
            if (date.toString() in d.daysOff) busy += SlotRecommender.Busy(0, 24 * 60)

            val planned = activitiesToday.sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
            val typical = if (analysisOn) DayLoad.compute(0, totals, date).typicalMinutes else null
            WeekPlanner.DayInput(date, busy, planned, typical)
        }

        // rata de reușită din ultimele 4 săptămâni (doar cu analiza pornită și destule date)
        val successRate = if (analysisOn) successRate(today) else null

        // unde se lucrează la fiecare sarcină: ghicit din titlu, altfel acasă
        val placeList = db.placeDao().getAll().map { PlaceGuess.Named(it.id, it.name) }
        val home = travelMatrix.homePlaceId
        fun placeFor(t: WeekTask): Long? = PlaceGuess.guess(t.title, placeList, emptyList()) ?: home

        val planTasks = d.tasks.mapNotNull { t ->
            val need = when (scopeNow) {
                Scope.WEEK -> t.totalMinutes - done.getValue(t.id)
                Scope.TODAY -> movableList.filter { it.weekTaskId == t.id }.sumOf { it.userEstimatedMinutes ?: 0 }
            }
            if (need <= 0) return@mapNotNull null
            WeekPlanner.Task(
                id = t.id,
                title = t.title,
                totalMinutes = need,
                sessionMinutes = t.sessionMinutes,
                deadline = t.deadline?.let(LocalDate::parse),
                pinnedDate = t.pinnedDate?.let(LocalDate::parse),
                history = t.categoryId?.let { repo.slotHistory(it) }.orEmpty(),
                placeId = placeFor(t)
            )
        }

        val travel = travelMatrix.forPlanning(
            planTasks.mapNotNull { it.placeId },
            inputs.flatMap { day -> day.busy.mapNotNull { it.placeId } }
        )
        val plan = WeekPlanner.plan(planTasks, inputs, successRate, earliestToday, today, travel)

        // zilele săptămânii, pentru grilă
        val dayResults = plan.days.associateBy { it.date }
        val cells = week.map { date ->
            val result = dayResults[date]
            val existing = fixed.filter { it.date == date.toString() }.sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
            val budget = result?.budget ?: WeekPlanner.DayInput(date, emptyList()).budget
            DayCell(
                date = date,
                minutes = result?.totalMinutes ?: existing,
                budget = budget,
                dayOff = date.toString() in d.daysOff,
                risky = date == plan.riskyDay
            )
        }

        val rows = d.tasks.map { t ->
            TaskRow(
                task = t,
                result = plan.tasks.firstOrNull { it.taskId == t.id },
                doneMinutes = done.getValue(t.id),
                remainingMinutes = (t.totalMinutes - done.getValue(t.id)).coerceAtLeast(0)
            )
        }

        // diferă propunerea de ce e deja scris în zile?
        val proposed = plan.sessions.map { Triple(it.taskId, it.date.toString() to it.startMinute, it.minutes) }.toSet()
        val current = movableList.map { Triple(it.weekTaskId!!, it.date to (it.plannedStartMinute ?: -1), it.userEstimatedMinutes ?: 0) }.toSet()

        return UiState(
            weekStart = start,
            offset = off,
            scope = scopeNow,
            tasks = rows,
            sessions = plan.sessions,
            unplaced = plan.unplaced,
            days = cells,
            chancePercent = plan.chancePercent,
            riskyDay = plan.riskyDay,
            notApplied = proposed != current && (proposed.isNotEmpty() || current.isNotEmpty()),
            scenarioText = text,
            scenarioBlocks = scenario.blocks,
            scenarioIgnored = scenario.ignored,
            categories = d.categories,
            analysisOn = analysisOn
        )
    }

    private suspend fun successRate(today: LocalDate): Double? {
        val items = repo.observeActivitiesInRange(today.minusDays(28).toString(), today.toString()).first().map {
            PeriodReport.Item(
                LocalDate.parse(it.date), it.state, it.actualMinutes, it.categoryId, it.title, it.titleKey, it.postponedCount
            )
        }
        val summary = PeriodReport.summarize(items, today.minusDays(28), today, today)
        if (summary.due < MIN_ACTIVITIES_FOR_RATE) return null
        return (summary.done + 0.5 * summary.partial) / summary.due
    }

    // ---- ce face utilizatorul ----

    fun setOffset(value: Int) { offset.value = value.coerceIn(0, 1) }
    fun setScope(value: Scope) { scope.value = value }
    fun setScenario(text: String) { scenarioText.value = text }

    fun addTask(title: String, totalMinutes: Int, sessionMinutes: Int, deadline: LocalDate?) = viewModelScope.launch {
        val categories = repo.observeCategories().first()
        val known = repo.observeActivitiesInRange("0000-01-01", "9999-12-31").first().mapNotNull { a ->
            a.categoryId?.let { CategoryGuess.Known(a.titleKey ?: TitleKey.of(a.title), it) }
        }
        val categoryId = CategoryGuess.guess(title, categories.map { CategoryGuess.Cat(it.id, it.name) }, known)
        weekDao.insert(
            WeekTask(
                title = title.trim(),
                categoryId = categoryId,
                weekStart = weekStart(offset.value).toString(),
                totalMinutes = totalMinutes,
                sessionMinutes = sessionMinutes.coerceAtMost(totalMinutes),
                deadline = deadline?.toString(),
                pinnedDate = null
            )
        )
    }

    fun setDeadline(task: WeekTask, deadline: LocalDate?) = viewModelScope.launch {
        weekDao.update(task.copy(deadline = deadline?.toString()))
    }

    /** Fixează sarcina pe o zi (nu se mută la redistribuire); null o eliberează. */
    fun setPinned(task: WeekTask, date: LocalDate?) = viewModelScope.launch {
        weekDao.update(task.copy(pinnedDate = date?.toString()))
    }

    fun deleteTask(task: WeekTask) = viewModelScope.launch {
        removeMovable(task.id, from = LocalDate.now(), onlyToday = false)
        weekDao.unlink(task.id)
        weekDao.delete(task.id)
    }

    private suspend fun removeMovable(taskId: Long, from: LocalDate, onlyToday: Boolean) {
        val sessions = if (onlyToday) weekDao.movableOn(taskId, from.toString()) else weekDao.movableSessions(taskId, from.toString())
        sessions.forEach { ActivityReminders.onActivityClosed(app, it.id) }
        if (onlyToday) weekDao.deleteMovableOn(taskId, from.toString()) else weekDao.deleteMovable(taskId, from.toString())
    }

    /**
     * Scrie propunerea în zilele săptămânii: sesiunile neîncepute se refac, cele terminate rămân.
     * La „doar azi”, ce nu mai încape azi trece pe ziua următoare, fără oră.
     */
    fun apply(onDone: (String) -> Unit) = viewModelScope.launch {
        val current = state.value
        val today = LocalDate.now()
        val onlyToday = current.scope == Scope.TODAY

        for (row in current.tasks) removeMovable(row.task.id, today, onlyToday)

        var added = 0
        for (s in current.sessions) {
            val task = current.tasks.first { it.task.id == s.taskId }.task
            added += insertSession(task, s.date, s.startMinute, s.minutes)
        }

        var moved = 0
        if (onlyToday) {
            for (u in current.unplaced) {
                val task = current.tasks.first { it.task.id == u.taskId }.task
                moved += insertSession(task, today.plusDays(1), null, u.minutes)
            }
        }

        onDone(
            when {
                added + moved == 0 -> "Nu era nimic de așezat."
                moved > 0 -> "Am așezat $added sesiuni; $moved au trecut pe mâine."
                else -> "Am așezat $added ${if (added == 1) "sesiune" else "sesiuni"} în zilele săptămânii."
            }
        )
    }

    private suspend fun insertSession(task: WeekTask, date: LocalDate, start: Int?, minutes: Int): Int {
        // locul se salvează doar când e ghicit din titlu („acasă” rămâne doar o presupunere pentru drum)
        val placeId = PlaceGuess.guess(task.title, db.placeDao().getAll().map { PlaceGuess.Named(it.id, it.name) }, emptyList())
        val activity = Activity(
            placeId = placeId,
            title = task.title,
            categoryId = task.categoryId,
            date = date.toString(),
            plannedStartMinute = start,
            userGuessMinutes = minutes,
            estimatedMinutes = null,
            userEstimatedMinutes = minutes,
            actualMinutes = null,
            weekTaskId = task.id
        )
        val id = repo.addActivity(activity)
        ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
        return 1
    }

    private companion object {
        const val MIN_ACTIVITIES_FOR_RATE = 8
    }
}