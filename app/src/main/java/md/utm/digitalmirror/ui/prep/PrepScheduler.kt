package md.utm.digitalmirror.ui.prep

import android.content.Context
import kotlinx.coroutines.flow.first
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.PrepEvent
import md.utm.digitalmirror.data.entity.PrepStep
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.PrepPlanner
import md.utm.digitalmirror.domain.PrepTemplates
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.WeekPlanner
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pregătirea pentru un eveniment: salvează evenimentul cu pașii și îi așază în zile, de azi până în ajun.
 * Folosită de ecranul Pregătire și de „Ce ai de făcut?”, ca amândouă să așeze la fel.
 */
class PrepScheduler(private val context: Context) {

    class Outcome(val sessions: Int, val unplacedMinutes: Int, val chancePercent: Int?, val noDays: Boolean)

    private val db = AppDatabase.get(context)
    private val prep = db.prepDao()
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val analysisOn = AnalysisPrefs.isEnabled(context)
    private val travelMatrix = TravelMatrix(context)

    /** Salvează evenimentul cu pașii lui și îi așază în zile. Returnează id-ul și ce s-a întâmplat. */
    suspend fun create(title: String, date: LocalDate, steps: List<PrepTemplates.StepDraft>): Pair<Long, Outcome> {
        val id = prep.insertEvent(PrepEvent(title = title.trim(), eventDate = date.toString()))
        steps.forEachIndexed { index, s ->
            prep.insertStep(PrepStep(eventId = id, title = s.title.trim(), minutes = s.minutes, position = index))
        }
        return id to schedule(id)
    }

    suspend fun schedule(eventId: Long): Outcome {
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
        val rules = DayBusy.rules(context)
        val daysOff = db.availabilityDao().observeDaysOff().first().map { it.date }.toSet()
        val totals = if (analysisOn) repo.dailyTotals(today.toString()).filter { it.date.toString() !in daysOff } else emptyList()
        val calendarPlaces = DayBusy.calendarPlaces(context)
        val inputs = days.map { date ->
            // sesiunile stau între trezire (cea obișnuită sau cea aleasă pentru ziua asta) și ora de culcare
            val (dayStart, dayEnd) = DayBusy.window(context, date)
            val fixed = repo.observeActivitiesForDate(date.toString()).first()
                .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            val busy = mutableListOf<SlotRecommender.Busy>()
            busy += DayBusy.activities(fixed)
            busy += DayBusy.calendar(context, date, calendarPlaces)
            busy += DayBusy.blocks(date, rules, daysOff)
            // mesele din ferestrele tale: sesiunile nu se pun peste ele
            busy += travelMatrix.skeleton(busy.toList(), date).meals.slots.map { SlotRecommender.Busy(it.start, it.end) }
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
            ActivityReminders.onActivityPlanned(context, activity.copy(id = id))
        }
        return Outcome(plan.sessions.size, plan.unplaced.sumOf { it.minutes }, plan.chancePercent, false)
    }

    companion object {
        private const val MAX_DAYS = 60
        private const val SESSION_MINUTES = 90
        private val DONE_STATES = setOf("DONE", "PARTIAL")

        fun message(o: Outcome): String = when {
            o.noDays -> "Evenimentul e azi sau a trecut, deci nu mai am zile de planificat."
            o.sessions == 0 -> "Nu era nimic de așezat."
            else -> buildString {
                append("Am așezat ${o.sessions} ${if (o.sessions == 1) "sesiune" else "sesiuni"}.")
                if (o.unplacedMinutes > 0) append(" ${formatDuration(o.unplacedMinutes)} n-au încăput.")
                o.chancePercent?.let { append(" Șanse: $it%.") }
            }
        }
    }
}
