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
import md.utm.digitalmirror.data.entity.DayOverride
import md.utm.digitalmirror.domain.MealPlanner
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
import md.utm.digitalmirror.domain.SleepPlanner
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.domain.WeekPlanner
import md.utm.digitalmirror.domain.WeekScenario
import md.utm.digitalmirror.domain.WeekBoard
import md.utm.digitalmirror.reminder.ActivityReminders
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.distinctUntilChanged

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

    /**
     * Un rând din scheletul săptămânii: trezirea, ce e fix, mesele și excepțiile alese pentru ziua asta.
     * Sesiunile propuse din plan nu intră aici: scheletul e ce e fix, iar sarcinile se potrivesc în el.
     */
    data class SkeletonDay(
        val date: LocalDate,
        val wake: Int,
        val wakeReason: SleepPlanner.Reason,
        val fixedFrom: Int?,                       // primul lucru fix (curs, eveniment, bloc)
        val fixedTo: Int?,                         // ultimul lucru fix
        val meals: List<MealPlanner.Kind>,         // mesele care au loc în ziua asta
        val missingMeals: List<MealPlanner.Kind>,  // mesele pornite, dar fără loc
        val override: DayOverride?,
        val dayOff: Boolean
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
        val analysisOn: Boolean = true,
        val skeleton: List<SkeletonDay> = emptyList()
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

    private val overrideDao = db.dayOverrideDao()

    val state: StateFlow<UiState> =
        combine(
            combine(offset, scope, scenarioText) { o, s, t -> Triple(o, s, t) },
            data,
            overrideDao.observeAll()          // o excepție nouă schimbă scheletul și locul sesiunilor
        ) { (o, s, t), d, _ ->
            Triple(o, s, t) to d
        }.mapLatest { (controls, d) -> compute(controls.first, controls.second, controls.third, d) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    // ---- calendarul: ziua aleasă (în bandă sau în lună) și ce e planificat în ea ----

    enum class View { WEEK, MONTH }

    /** Ce fel de lucru e în agenda unei zile. */
    enum class Kind { ACTIVITY, PROPOSED, EVENT, FIXED }

    data class AgendaItem(
        val key: String,
        val title: String,
        val start: Int?,            // null = fără oră
        val end: Int?,
        val kind: Kind,
        val colorHex: String? = null,
        val state: String? = null,  // doar pentru activități
        val allDay: Boolean = false,
        val activityId: Long? = null   // doar pentru activități: se deschide la apăsare
    )

    data class Agenda(val date: LocalDate, val items: List<AgendaItem>, val dayOff: Boolean)

    private val selected = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = selected
    private val shownMonth = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = shownMonth
    private val shownView = MutableStateFlow(View.WEEK)
    val view: StateFlow<View> = shownView

    /** Ce e planificat în ziua aleasă: activități, evenimente din calendar, programul fix și sesiunile propuse. */
    val agenda: StateFlow<Agenda?> = combine(
        selected.flatMapLatest { date -> repo.observeActivitiesForDate(date.toString()).map { date to it } },
        availability.observeRules(),
        availability.observeDaysOff(),
        repo.observeCategories(),
        state.map { it.sessions }.distinctUntilChanged()
    ) { (date, activities), rules, daysOffList, categories, sessions ->
        val colors = categories.associate { it.id to md.utm.digitalmirror.ui.components.CategoryColor.hex(it) }
        val daysOff = daysOffList.map { it.date }.toSet()
        val items = mutableListOf<AgendaItem>()
        activities.filter { it.state != "POSTPONED" && it.state != "ABANDONED" }.forEach { a ->
            val minutes = a.actualMinutes ?: a.userEstimatedMinutes ?: a.estimatedMinutes
            items += AgendaItem(
                key = "a${a.id}", title = a.title,
                start = a.plannedStartMinute,
                end = a.plannedStartMinute?.let { s -> minutes?.let { s + it } },
                kind = Kind.ACTIVITY, colorHex = a.categoryId?.let(colors::get), state = a.state, activityId = a.id
            )
        }
        // propunerea planului, doar ce nu e deja scris în zi
        sessions.filter { it.date == date }.forEach { s ->
            val written = activities.any { it.weekTaskId == s.taskId && it.plannedStartMinute == s.startMinute }
            if (!written) items += AgendaItem("p${s.taskId}-${s.startMinute}", s.title, s.startMinute, s.endMinute, Kind.PROPOSED)
        }
        val ruleList = rules.map {
            Availability.Rule(it.id, it.title, it.daysMask, it.startMinute, it.endMinute, it.travelBeforeMinutes, it.travelAfterMinutes, it.placeId)
        }
        Availability.blocksFor(date, ruleList, daysOff).forEach { b ->
            items += AgendaItem("f${b.ruleId}", b.title, b.start, b.end, Kind.FIXED)
        }
        if (CalendarReader.hasPermission(app)) {
            CalendarReader.eventsFor(app, date).forEach { e ->
                items += AgendaItem(
                    "e${e.id}-${e.startMinute}", e.title,
                    if (e.allDay) null else e.startMinute, if (e.allDay) null else e.endMinute,
                    Kind.EVENT, allDay = e.allDay
                )
            }
        }
        Agenda(date, items.sortedWith(compareBy({ it.start ?: Int.MAX_VALUE }, { it.kind.ordinal })), date.toString() in daysOff)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Pentru grila lunii: în ce zile e ceva planificat și culorile categoriilor (cel mult 3 puncte). */
    val monthDots: StateFlow<Map<LocalDate, List<String?>>> = shownMonth.flatMapLatest { m ->
        combine(
            repo.observeActivitiesInRange(m.atDay(1).toString(), m.atEndOfMonth().toString()),
            repo.observeCategories()
        ) { activities, categories ->
            val colors = categories.associate { it.id to md.utm.digitalmirror.ui.components.CategoryColor.hex(it) }
            activities.filter { it.state != "POSTPONED" && it.state != "ABANDONED" }
                .groupBy { LocalDate.parse(it.date) }
                .mapValues { (_, list) -> list.map { a -> a.categoryId?.let(colors::get) }.distinct().take(3) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Alege o zi; dacă e în săptămâna asta sau următoarea, banda și planul o urmează. */
    fun select(date: LocalDate) {
        selected.value = date
        shownMonth.value = YearMonth.from(date)
        val weeks = ChronoUnit.WEEKS.between(weekStart(0), PeriodReport.weekStart(date)).toInt()
        if (weeks in 0..2) offset.value = weeks
    }

    fun setView(value: View) {
        shownView.value = value
        // banda arată doar săptămâna asta și următoarea: altfel revenim la azi
        if (value == View.WEEK) {
            val weeks = ChronoUnit.WEEKS.between(weekStart(0), PeriodReport.weekStart(selected.value)).toInt()
            if (weeks !in 0..2) select(LocalDate.now())
        }
    }

    fun shiftMonth(delta: Int) { shownMonth.value = shownMonth.value.plusMonths(delta.toLong()) }


    // ---- tabla: săptămâna asta, următoarea și cea de după, zi cu zi ----

    /** Un lucru de pe tablă: o activitate (se mută, se anulează) sau ceva fix (curs, eveniment; doar se vede). */
    data class BoardItem(
        val key: String,
        val activityId: Long?,
        val title: String,
        val minutes: Int,
        val start: Int?,
        val kind: Kind,
        val state: String? = null,
        val colorHex: String? = null,
        val movable: Boolean = false,
        val allDay: Boolean = false
    )

    data class BoardDay(
        val date: LocalDate,
        val items: List<BoardItem>,
        val capacity: Int,                // cât încape realist pentru sarcinile tale
        val fixedMinutes: Int,            // cursuri, programări, evenimente
        val dayOff: Boolean
    ) {
        /** Sarcinile tale din zi (și cele deja făcute: și ele au ocupat ziua). */
        val plannedMinutes: Int get() = items.filter { it.kind == Kind.ACTIVITY }.sumOf { it.minutes }
        val load: WeekBoard.Load get() = WeekBoard.load(plannedMinutes, capacity)
    }

    private val boardStart = weekStart(0)
    private val boardRefresh = MutableStateFlow(0)

    /** Toate cele 21 de zile; ecranul arată săptămâna aleasă, iar AI-ul le vede pe toate. */
    val board: StateFlow<List<BoardDay>> =
        combine(
            repo.observeActivitiesInRange(boardStart.toString(), boardStart.plusDays(BOARD_DAYS - 1L).toString()),
            availability.observeRules(),
            availability.observeDaysOff(),
            repo.observeCategories(),
            boardRefresh
        ) { activities, rules, daysOff, categories, _ ->
            Triple(activities, rules.map {
                Availability.Rule(it.id, it.title, it.daysMask, it.startMinute, it.endMinute, it.travelBeforeMinutes, it.travelAfterMinutes, it.placeId)
            }, daysOff.map { it.date }.toSet()) to categories
        }.mapLatest { (data, categories) -> buildBoard(data.first, data.second, data.third, categories) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private suspend fun buildBoard(
        activities: List<Activity>,
        rules: List<Availability.Rule>,
        daysOff: Set<String>,
        categories: List<Category>
    ): List<BoardDay> {
        val today = LocalDate.now()
        val colors = categories.associate { it.id to md.utm.digitalmirror.ui.components.CategoryColor.hex(it) }
        val totals = if (analysisOn) repo.dailyTotals(today.toString()).filter { it.date.toString() !in daysOff } else emptyList()
        val calendar = CalendarReader.hasPermission(app)

        return (0 until BOARD_DAYS).map { i ->
            val date = boardStart.plusDays(i.toLong())
            val dayOff = date.toString() in daysOff
            val items = mutableListOf<BoardItem>()

            activities.filter { it.date == date.toString() && it.state != "POSTPONED" && it.state != "ABANDONED" }.forEach { a ->
                items += BoardItem(
                    key = "a${a.id}", activityId = a.id, title = a.title,
                    minutes = a.actualMinutes ?: a.userEstimatedMinutes ?: a.estimatedMinutes ?: 30,
                    start = a.plannedStartMinute, kind = Kind.ACTIVITY, state = a.state,
                    colorHex = a.categoryId?.let(colors::get),
                    movable = a.state == "PLANNED" && !date.isBefore(today)
                )
            }
            val fixed = mutableListOf<Pair<Int, Int>>()
            Availability.blocksFor(date, rules, daysOff).forEach { b ->
                items += BoardItem("f${b.ruleId}", null, b.title, b.end - b.start, b.start, Kind.FIXED)
                fixed += b.start to b.end
            }
            if (calendar) {
                CalendarReader.eventsFor(app, date).forEach { e ->
                    items += BoardItem(
                        "e${e.id}-${e.startMinute}", null, e.title,
                        if (e.allDay) 0 else e.endMinute - e.startMinute,
                        if (e.allDay) null else e.startMinute, Kind.EVENT, allDay = e.allDay
                    )
                    if (e.busy && !e.allDay) fixed += e.startMinute to e.endMinute
                }
            }

            val fixedMinutes = WeekBoard.fixedMinutes(fixed)
            val (wake, bed) = DayBusy.window(app, date)
            val typical = if (analysisOn) DayLoad.compute(0, totals, date).typicalMinutes else null
            val budget = WeekPlanner.DayInput(date, emptyList(), typicalMinutes = typical).budget
            BoardDay(
                date = date,
                items = items.sortedWith(compareBy({ it.start ?: Int.MAX_VALUE }, { it.kind.ordinal })),
                capacity = if (dayOff) 0 else WeekBoard.capacity(budget, bed - wake, fixedMinutes),
                fixedMinutes = fixedMinutes,
                dayOff = dayOff
            )
        }
    }

    /** Ce scrii într-o zi („licență 2h, sală 1h”): una sau mai multe activități pe ziua aceea. */
    fun addToDay(date: LocalDate, text: String) = viewModelScope.launch {
        val parsed = md.utm.digitalmirror.domain.QuickCapture.parse(text, date).filter { it.title.isNotBlank() }
        if (parsed.isEmpty()) return@launch
        val categories = repo.observeCategories().first()
        val known = repo.observeActivitiesInRange("0000-01-01", "9999-12-31").first().mapNotNull { a ->
            a.categoryId?.let { CategoryGuess.Known(a.titleKey ?: TitleKey.of(a.title), it) }
        }
        parsed.forEach { p ->
            val minutes = p.minutes ?: DEFAULT_TASK_MINUTES
            val activity = Activity(
                title = p.title.trim(),
                categoryId = CategoryGuess.guess(p.title, categories.map { CategoryGuess.Cat(it.id, it.name) }, known),
                date = date.toString(),
                plannedStartMinute = p.startMinute,
                userGuessMinutes = p.minutes,
                estimatedMinutes = null,
                userEstimatedMinutes = minutes,
                actualMinutes = null
            )
            val id = repo.addActivity(activity)
            ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
        }
    }

    /** Tras pe altă zi: ora rămâne (dacă avea), doar ziua se schimbă. */
    fun moveToDay(activityId: Long, date: LocalDate) = viewModelScope.launch { moveNow(activityId, date) }

    private suspend fun moveNow(activityId: Long, date: LocalDate) {
        val fresh = repo.getActivity(activityId) ?: return
        val from = LocalDate.parse(fresh.date)
        if (date == from) return
        val today = LocalDate.now()
        // mutată după ce i-a venit ziua: e o amânare; dintr-o zi viitoare: doar o replanificare
        repo.postponeActivity(activityId, date.toString(), countAsPostponed = !from.isAfter(today) && date.isAfter(from))
        val moved = repo.getActivity(activityId) ?: return
        ActivityReminders.onActivityClosed(app, moved.id)
        ActivityReminders.onActivityPlanned(app, moved)
    }

    /** „Anulează”: nu se mai face; rămâne în istoric ca abandonată. */
    fun cancelActivity(activityId: Long) = viewModelScope.launch {
        repo.closeActivity(activityId, null, "ABANDONED", null)
        ActivityReminders.onActivityClosed(app, activityId)
    }

    // ---- „✨ Echilibrează” cu AI ----

    sealed interface AiUi {
        data object Idle : AiUi
        data object Loading : AiUi
        data class Done(val summary: String, val moves: List<AiMove>) : AiUi
        data class Error(val message: String) : AiUi
    }

    /** O propunere, cu tot ce trebuie arătat: ce, de unde, unde și de ce. */
    data class AiMove(val activityId: Long, val title: String, val from: LocalDate, val to: LocalDate, val reason: String)

    private val _ai = MutableStateFlow<AiUi>(AiUi.Idle)
    val ai: StateFlow<AiUi> = _ai

    val aiAvailable: Boolean get() = md.utm.digitalmirror.data.ai.AiBalancer.isAvailable

    /** Trimite zilele de azi încolo (din cele 3 săptămâni) și dorința din simulare, dacă e una. */
    fun balance() {
        if (_ai.value == AiUi.Loading) return
        val today = LocalDate.now()
        val days = board.value.filter { !it.date.isBefore(today) }
        if (days.isEmpty()) return
        _ai.value = AiUi.Loading
        viewModelScope.launch {
            val byKey = days.flatMap { d -> d.items.filter { it.kind == Kind.ACTIVITY }.map { it.key to (it to d.date) } }.toMap()
            val request = days.map { d ->
                md.utm.digitalmirror.data.ai.AiBalancer.Day(
                    date = d.date.toString(),
                    capacityMinutes = d.capacity,
                    fixedMinutes = d.fixedMinutes,
                    dayOff = d.dayOff,
                    items = d.items.filter { it.kind == Kind.ACTIVITY }
                        .map { md.utm.digitalmirror.data.ai.AiBalancer.Item(it.key, it.title, it.minutes, it.movable) }
                )
            }
            _ai.value = try {
                val result = md.utm.digitalmirror.data.ai.AiBalancer.balance(request, scenarioText.value)
                AiUi.Done(
                    result.summary,
                    result.moves.mapNotNull { m ->
                        val (item, from) = byKey[m.itemId] ?: return@mapNotNull null
                        val to = runCatching { LocalDate.parse(m.toDate) }.getOrNull() ?: return@mapNotNull null
                        AiMove(item.activityId ?: return@mapNotNull null, item.title, from, to, m.reason)
                    }
                )
            } catch (e: md.utm.digitalmirror.data.ai.AiBalancer.AiException) {
                AiUi.Error(e.message ?: "AI-ul nu a răspuns.")
            } catch (e: Exception) {
                AiUi.Error("Răspunsul AI-ului nu a putut fi citit.")
            }
        }
    }

    fun acceptMove(move: AiMove) = viewModelScope.launch {
        moveNow(move.activityId, move.to)
        dropMove(move)
    }

    fun acceptAllMoves() = viewModelScope.launch {
        val current = _ai.value as? AiUi.Done ?: return@launch
        current.moves.forEach { moveNow(it.activityId, it.to) }
        _ai.value = AiUi.Idle
    }

    fun dropMove(move: AiMove) {
        val current = _ai.value as? AiUi.Done ?: return
        val left = current.moves - move
        _ai.value = if (left.isEmpty()) AiUi.Idle else current.copy(moves = left)
    }

    fun dismissAi() { _ai.value = AiUi.Idle }

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
            // sesiunile stau între trezire (cea obișnuită sau cea aleasă pentru ziua asta) și ora de culcare
            val (dayStart, dayEnd) = DayBusy.window(app, date)
            val busy = mutableListOf<SlotRecommender.Busy>()
            val activitiesToday = fixed.filter { it.date == date.toString() }
            // cu locul fiecărui lucru, ca sesiunile să lase timp de drum
            busy += DayBusy.activities(activitiesToday)
            busy += DayBusy.calendar(app, date, calendarPlaces)
            busy += DayBusy.blocks(date, d.rules, d.daysOff)
            busy += scenario.blocks.filter { it.date == date }.map { SlotRecommender.Busy(it.fromMinute, it.toMinute) }
            // mesele din ferestrele tale: sesiunile nu se pun peste ele
            busy += travelMatrix.skeleton(busy.toList(), date).meals.slots.map { SlotRecommender.Busy(it.start, it.end) }
            if (date.toString() in d.daysOff) busy += SlotRecommender.Busy(0, 24 * 60)

            val planned = activitiesToday.sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
            val typical = if (analysisOn) DayLoad.compute(0, totals, date).typicalMinutes else null
            WeekPlanner.DayInput(date, busy, planned, typical, dayStart, dayEnd)
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

        // scheletul: din azi (sau de luni, săptămâna viitoare) până duminică
        val skeleton = week.filter { !it.isBefore(today) }.map { date ->
            val fixedToday = fixed.filter { it.date == date.toString() }
            val busy = DayBusy.activities(fixedToday) +
                    DayBusy.calendar(app, date, calendarPlaces) +
                    DayBusy.blocks(date, d.rules, d.daysOff)
            val sk = travelMatrix.skeleton(busy, date)
            val sleep = DayBusy.sleepPlan(app, date, DayBusy.firstStart(busy, sk))
            SkeletonDay(
                date = date,
                wake = sleep.wake,
                wakeReason = sleep.reason,
                fixedFrom = busy.minOfOrNull { it.start },
                fixedTo = busy.maxOfOrNull { it.end },
                meals = sk.meals.slots.map { it.kind },
                missingMeals = sk.meals.missing.map { it.kind },
                override = DayBusy.override(app, date),
                dayOff = date.toString() in d.daysOff
            )
        }

        return UiState(
            skeleton = skeleton,
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

    // ---- excepțiile din schelet ----

    /** „Vineri mă trezesc la 10:00”; null = ora obișnuită. */
    fun setWake(date: LocalDate, minute: Int?) = updateOverride(date) { it.copy(wakeMinute = minute) }

    /** „Joi sar prânzul” (sau îl repune). */
    fun toggleMeal(date: LocalDate, kind: MealPlanner.Kind) = updateOverride(date) { o ->
        val skipped = o.skipped.toMutableSet()
        if (!skipped.add(kind.name)) skipped.remove(kind.name)
        o.copy(skippedMeals = skipped.sorted().joinToString(","))
    }

    /** Ziua revine la scheletul obișnuit. */
    fun resetDay(date: LocalDate) = viewModelScope.launch { overrideDao.delete(date.toString()) }

    private fun updateOverride(date: LocalDate, change: (DayOverride) -> DayOverride) = viewModelScope.launch {
        val current = overrideDao.get(date.toString()) ?: DayOverride(date.toString())
        val updated = change(current)
        if (updated.isEmpty) overrideDao.delete(date.toString()) else overrideDao.upsert(updated)
    }

    fun setOffset(value: Int) {
        offset.value = value.coerceIn(0, 2)
        // ziua aleasă trece în săptămâna arătată: azi, sau lunea săptămânii viitoare
        select(if (offset.value == 0) LocalDate.now() else weekStart(offset.value))
    }
    fun setScope(value: Scope) { scope.value = value }
    fun setScenario(text: String) { scenarioText.value = text }

    /** [onDay] = toate sesiunile într-o zi anume (sarcina e a săptămânii acelei zile); altfel a săptămânii arătate. */
    fun addTask(title: String, totalMinutes: Int, sessionMinutes: Int, deadline: LocalDate?, onDay: LocalDate? = null) = viewModelScope.launch {
        val categories = repo.observeCategories().first()
        val known = repo.observeActivitiesInRange("0000-01-01", "9999-12-31").first().mapNotNull { a ->
            a.categoryId?.let { CategoryGuess.Known(a.titleKey ?: TitleKey.of(a.title), it) }
        }
        val categoryId = CategoryGuess.guess(title, categories.map { CategoryGuess.Cat(it.id, it.name) }, known)
        weekDao.insert(
            WeekTask(
                title = title.trim(),
                categoryId = categoryId,
                weekStart = (onDay?.let(PeriodReport::weekStart) ?: weekStart(offset.value)).toString(),
                totalMinutes = totalMinutes,
                sessionMinutes = sessionMinutes.coerceAtMost(totalMinutes),
                deadline = deadline?.toString(),
                pinnedDate = onDay?.toString()
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
        const val BOARD_DAYS = 21          // săptămâna asta, următoarea și cea de după
        const val DEFAULT_TASK_MINUTES = 60
    }
}