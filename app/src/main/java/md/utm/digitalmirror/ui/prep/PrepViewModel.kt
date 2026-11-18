package md.utm.digitalmirror.ui.prep

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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.PrepEvent
import md.utm.digitalmirror.data.entity.PrepStep
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.PrepPlanner
import md.utm.digitalmirror.domain.PrepTemplates
import md.utm.digitalmirror.domain.SleepPlanner
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.WeekPlanner
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Pregătirea pentru un eveniment: pași cu timp, așezați pe zile de azi până în ajunul evenimentului.
 * Așezarea o face WeekPlanner, cu o fereastră de zile pentru fiecare pas (PrepPlanner).
 * Sesiunile devin activități obișnuite în zilele tale, legate de pas (Activity.prepStepId).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrepViewModel(private val app: Application) : AndroidViewModel(app) {

    data class StepRow(val step: PrepStep, val doneMinutes: Int)

    data class EventRow(
        val event: PrepEvent,
        val daysLeft: Int,
        val totalMinutes: Int,
        val doneMinutes: Int,
        val percent: Int
    )

    data class Detail(
        val event: PrepEvent,
        val daysLeft: Int,
        val steps: List<StepRow>,
        val sessions: List<Activity>,
        val percent: Int,
        val totalMinutes: Int,
        val doneMinutes: Int,
        val unscheduledMinutes: Int          // cât mai e de făcut și nu are încă sesiune în plan
    )

    private class Outcome(val sessions: Int, val unplacedMinutes: Int, val chancePercent: Int?, val noDays: Boolean)

    private val db = AppDatabase.get(app)
    private val prep = db.prepDao()
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val availability = db.availabilityDao()
    private val analysisOn = AnalysisPrefs.isEnabled(app)
    private val travelMatrix = TravelMatrix(app)

    // ---- lista evenimentelor ----

    val events: StateFlow<List<EventRow>> =
        combine(prep.observeEvents(), prep.observeAllSteps(), prep.observeAllSessions()) { events, steps, sessions ->
            val today = LocalDate.now()
            events.map { event ->
                val mySteps = steps.filter { it.eventId == event.id }
                val mine = sessions.filter { s -> mySteps.any { it.id == s.prepStepId } }
                val summary = summarize(event, mySteps, mine, today)
                EventRow(event, summary.daysLeft, summary.totalMinutes, summary.doneMinutes, summary.percent)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- un eveniment deschis ----

    private val selected = MutableStateFlow<Long?>(null)

    fun select(eventId: Long) { selected.value = eventId }

    val detail: StateFlow<Detail?> = selected.flatMapLatest { id ->
        if (id == null) flowOf(null)
        else combine(prep.observeEvent(id), prep.observeSteps(id), prep.observeSessions(id)) { event, steps, sessions ->
            event?.let { summarize(it, steps, sessions, LocalDate.now()) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ---- ce face utilizatorul ----

    /** Salvează evenimentul cu pașii lui și așază sesiunile în zile. */
    fun create(title: String, date: LocalDate, steps: List<PrepTemplates.StepDraft>, onDone: (Long, String) -> Unit) =
        viewModelScope.launch {
            val id = prep.insertEvent(PrepEvent(title = title.trim(), eventDate = date.toString()))
            steps.forEachIndexed { index, s ->
                prep.insertStep(PrepStep(eventId = id, title = s.title.trim(), minutes = s.minutes, position = index))
            }
            onDone(id, message(schedule(id)))
        }

    /** Refă sesiunile neîncepute, din azi încolo, pentru ce a rămas de făcut. */
    fun replan(eventId: Long, onDone: (String) -> Unit) = viewModelScope.launch {
        val from = LocalDate.now().toString()
        prep.movableSessions(eventId, from).forEach { ActivityReminders.onActivityClosed(app, it.id) }
        prep.deleteMovable(eventId, from)
        onDone(message(schedule(eventId)))
    }

    /** Pasul e gata: se socotește 100% și sesiunile lui neîncepute dispar, ca timpul să se elibereze. */
    fun completeStep(step: PrepStep) = viewModelScope.launch {
        val from = LocalDate.now().toString()
        prep.movableForStep(step.id, from).forEach { ActivityReminders.onActivityClosed(app, it.id) }
        prep.deleteMovableForStep(step.id, from)
        prep.updateStep(step.copy(completed = true))
    }

    /** Pasul se redeschide și ce a rămas din el se așază din nou în zile. */
    fun reopenStep(step: PrepStep, onDone: (String) -> Unit) = viewModelScope.launch {
        prep.updateStep(step.copy(completed = false))
        onDone(message(schedule(step.eventId)))
    }

    /** Șterge evenimentul și sesiunile neîncepute; ce ai terminat rămâne în istoric. */
    fun delete(eventId: Long, onDone: () -> Unit) = viewModelScope.launch {
        prep.movableSessions(eventId, "0000-01-01").forEach { ActivityReminders.onActivityClosed(app, it.id) }
        prep.deleteMovable(eventId, "0000-01-01")
        prep.unlinkSessions(eventId)
        prep.deleteSteps(eventId)
        prep.deleteEvent(eventId)
        onDone()
    }

    // ---- așezarea pe zile ----

    private suspend fun schedule(eventId: Long): Outcome {
        val event = prep.observeEvent(eventId).first() ?: return Outcome(0, 0, null, true)
        val steps = prep.getSteps(eventId)
        val today = LocalDate.now()
        val eventDate = LocalDate.parse(event.eventDate)

        // cel mult 60 de zile, cele mai apropiate de eveniment
        val days = PrepPlanner.daysBefore(eventDate, today).takeLast(MAX_DAYS)
        if (days.isEmpty()) return Outcome(0, 0, null, true)

        // cât s-a lucrat deja la fiecare pas
        val existing = prep.getSessions(eventId)
        val remaining = steps.filter { !it.completed }.map { step ->
            val done = existing.filter { it.prepStepId == step.id && it.state in DONE_STATES }.sumOf { it.actualMinutes ?: 0 }
            step to (step.minutes - done).coerceAtLeast(0)
        }.filter { it.second > 0 }
        if (remaining.isEmpty()) return Outcome(0, 0, null, false)

        // pregătirea se face de obicei acasă: sesiunile lasă timp de drum față de ce e în alt loc
        val home = travelMatrix.homePlaceId
        val windows = PrepPlanner.windows(days, remaining.map { it.second })
        val tasks = remaining.mapIndexed { index, (step, minutes) ->
            WeekPlanner.Task(
                id = step.id,
                title = "${event.title}: ${step.title}",
                totalMinutes = minutes,
                sessionMinutes = minOf(minutes, SESSION_MINUTES),
                deadline = windows[index].to,
                notBefore = windows[index].from,
                placeId = home
            )
        }

        // ce e deja ocupat în fiecare zi, cu locul fiecărui lucru
        val rules = DayBusy.rules(app)
        val daysOff = availability.observeDaysOff().first().map { it.date }.toSet()
        val totals = if (analysisOn) repo.dailyTotals(today.toString()).filter { it.date.toString() !in daysOff } else emptyList()
        val calendarPlaces = DayBusy.calendarPlaces(app)
        // sesiunile stau între trezirea ta obișnuită (plus dimineața) și ora ta de culcare
        val (dayStart, dayEnd) = SleepPlanner.activeWindow(DayBusy.recentNights(app, today))

        val inputs = days.map { date ->
            val fixed = repo.observeActivitiesForDate(date.toString()).first()
                .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            val busy = mutableListOf<SlotRecommender.Busy>()
            busy += DayBusy.activities(fixed)
            busy += DayBusy.calendar(app, date, calendarPlaces)
            busy += DayBusy.blocks(date, rules, daysOff)
            // mesele din ferestrele tale: sesiunile nu se pun peste ele
            busy += travelMatrix.skeleton(busy.toList()).meals.slots.map { SlotRecommender.Busy(it.start, it.end) }
            if (date.toString() in daysOff) busy += SlotRecommender.Busy(0, 24 * 60)
            val planned = fixed.sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
            val typical = if (analysisOn) DayLoad.compute(0, totals, date).typicalMinutes else null
            WeekPlanner.DayInput(date, busy, planned, typical, dayStart, dayEnd)
        }

        val earliestToday = LocalTime.now().let { maxOf(8 * 60, it.hour * 60 + it.minute + 10) }
        val travel = travelMatrix.forPlanning(listOfNotNull(home), inputs.flatMap { day -> day.busy.mapNotNull { it.placeId } })
        val plan = WeekPlanner.plan(tasks, inputs, null, earliestToday, today, travel)

        for (s in plan.sessions) {
            val activity = Activity(
                title = s.title,
                categoryId = null,
                date = s.date.toString(),
                plannedStartMinute = s.startMinute,
                userGuessMinutes = s.minutes,
                estimatedMinutes = null,
                userEstimatedMinutes = s.minutes,
                actualMinutes = null,
                prepStepId = s.taskId
            )
            val id = repo.addActivity(activity)
            ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
        }
        return Outcome(plan.sessions.size, plan.unplaced.sumOf { it.minutes }, plan.chancePercent, false)
    }

    private fun message(o: Outcome): String = when {
        o.noDays -> "Evenimentul e azi sau a trecut, deci nu mai am zile de planificat."
        o.sessions == 0 -> "Nu era nimic de așezat."
        else -> buildString {
            append("Am așezat ${o.sessions} ${if (o.sessions == 1) "sesiune" else "sesiuni"}.")
            if (o.unplacedMinutes > 0) append(" ${formatDuration(o.unplacedMinutes)} n-au încăput.")
            o.chancePercent?.let { append(" Șanse: $it%.") }
        }
    }

    companion object {
        private const val MAX_DAYS = 60
        private const val SESSION_MINUTES = 90
        private val DONE_STATES = setOf("DONE", "PARTIAL")
        private val OPEN_STATES = setOf("PLANNED", "IN_PROGRESS")

        private fun summarize(event: PrepEvent, steps: List<PrepStep>, sessions: List<Activity>, today: LocalDate): Detail {
            val rows = steps.map { step ->
                // un pas marcat „gata” se socotește făcut în întregime
                val done = if (step.completed) step.minutes
                else sessions.filter { it.prepStepId == step.id && it.state in DONE_STATES }.sumOf { it.actualMinutes ?: 0 }
                StepRow(step, done)
            }
            val planned = rows.map { it.step.minutes }
            val done = rows.map { it.doneMinutes }
            val need = rows.sumOf { (it.step.minutes - it.doneMinutes).coerceAtLeast(0) }
            val scheduled = sessions.filter { it.state in OPEN_STATES && it.date >= today.toString() }
                .sumOf { it.userEstimatedMinutes ?: 0 }
            return Detail(
                event = event,
                daysLeft = ChronoUnit.DAYS.between(today, LocalDate.parse(event.eventDate)).toInt(),
                steps = rows,
                sessions = sessions,
                percent = PrepPlanner.readyPercent(planned, done),
                totalMinutes = planned.sum(),
                doneMinutes = rows.sumOf { minOf(it.step.minutes, it.doneMinutes) },
                unscheduledMinutes = (need - scheduled).coerceAtLeast(0)
            )
        }
    }
}
