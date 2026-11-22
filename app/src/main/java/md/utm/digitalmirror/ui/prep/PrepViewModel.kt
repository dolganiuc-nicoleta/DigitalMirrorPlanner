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

    private val db = AppDatabase.get(app)
    private val prep = db.prepDao()
    // așezarea pașilor pe zile (comună cu „Ce ai de făcut?”)
    private val scheduler = PrepScheduler(app)

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
            val (id, outcome) = scheduler.create(title, date, steps)
            onDone(id, PrepScheduler.message(outcome))
        }

    /** Refă sesiunile neîncepute, din azi încolo, pentru ce a rămas de făcut. */
    fun replan(eventId: Long, onDone: (String) -> Unit) = viewModelScope.launch {
        val from = LocalDate.now().toString()
        prep.movableSessions(eventId, from).forEach { ActivityReminders.onActivityClosed(app, it.id) }
        prep.deleteMovable(eventId, from)
        onDone(PrepScheduler.message(scheduler.schedule(eventId)))
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
        onDone(PrepScheduler.message(scheduler.schedule(step.eventId)))
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

    companion object {
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
