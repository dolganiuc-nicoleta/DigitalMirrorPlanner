package md.utm.digitalmirror.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import md.utm.digitalmirror.MainActivity
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.travel.DayBusy
import md.utm.digitalmirror.data.travel.TravelMatrix
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DayArc
import md.utm.digitalmirror.domain.Tags
import md.utm.digitalmirror.domain.TravelPlanner
import md.utm.digitalmirror.domain.WidgetPick
import md.utm.digitalmirror.reminder.ActivityReminders
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// culorile după-amiezii din arcul zilei, ca widgetul să arate ca aplicația
private val Paper = Color(0xFFF4E4C9)
private val Ink = Color(0xFF17181F)
private val Muted = Color(0xFF5A4A38)

/**
 * Widgetul „Ziua ta”: ce contează acum (același mesaj ca în mijlocul arcului), următoarele lucruri
 * din zi și un buton care pornește sau oprește cronometrul, fără să deschizi aplicația.
 */
class DayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val state = runCatching { DayWidgetData.load(context) }.getOrNull()
        provideContent { WidgetContent(state) }
    }
}

class DayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DayWidget()
}

/** Butonul cronometrului: oprește ce rulează, altfel pornește activitatea aleasă de widget. */
class ToggleTimerAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val db = AppDatabase.get(context)
        val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
        val running = repo.runningSession()
        if (running != null) {
            repo.stopTimer(running)
            ActivityReminders.onTimerStopped(context, running.activityId)
        } else {
            val target = parameters[TARGET] ?: return
            repo.startTimer(target)
            val session = repo.runningSession() ?: return
            val activity = repo.getActivity(target) ?: return
            ActivityReminders.onTimerStarted(context, activity, session.startedAt, repo.closedMillis(target))
        }
        DayWidget().updateAll(context)
    }

    companion object {
        val TARGET = ActionParameters.Key<Long>("target")
    }
}

/** Reîmprospătează widgetul după orice schimbare din zi (chemat din ActivityReminders). */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun refresh(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { DayWidget().updateAll(app) } }
    }
}

/** Ce arată widgetul, calculat cu aceleași reguli ca ecranul zilei. */
data class WidgetState(
    val dateLabel: String,
    val focus: DayArc.Focus,
    val lines: List<WidgetPick.Line>,
    val timerId: Long?,
    val timerTitle: String?,
    val running: Boolean,
    val runningSince: String?
)

object DayWidgetData {

    private val DAY_NAMES = listOf("Luni", "Marți", "Miercuri", "Joi", "Vineri", "Sâmbătă", "Duminică")
    private val MONTHS = listOf("ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")

    suspend fun load(context: Context): WidgetState {
        val today = LocalDate.now()
        val now = LocalTime.now().let { it.hour * 60 + it.minute }
        val db = AppDatabase.get(context)
        val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
        val matrix = TravelMatrix(context)

        val activities = repo.observeActivitiesForDate(today.toString()).first()
            .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            .filter { TravelPlanner.TRAVEL_TAG !in Tags.decode(it.tags) }
        val events = CalendarReader.eventsFor(context, today).filter { it.busy && !it.allDay }
        val daysOff = db.availabilityDao().observeDaysOff().first().map { it.date }.toSet()
        val blocks = Availability.blocksFor(today, DayBusy.rules(context), daysOff)

        // scheletul zilei (drumuri, mese) și somnul, ca în ecranul zilei
        val busy = DayBusy.forDate(context, today)
        val skeleton = matrix.skeleton(busy, today)
        val sleep = DayBusy.sleepPlan(context, today, DayBusy.firstStart(busy, skeleton))
        val next = today.plusDays(1)
        val nextBusy = DayBusy.forDate(context, next)
        val bedtime = DayBusy.sleepPlan(context, next, DayBusy.firstStart(nextBusy, matrix.skeleton(nextBusy, next))).bedtimeBefore

        val places = db.placeDao().getAll().associate { it.id to it.name }
        val items = activities.mapNotNull { a ->
            a.plannedStartMinute?.let { s ->
                DayArc.Item(
                    s, s + (a.userEstimatedMinutes ?: a.estimatedMinutes ?: 30), a.title,
                    if (a.state == "DONE" || a.state == "PARTIAL") DayArc.Kind.DONE else DayArc.Kind.ACTIVITY
                )
            }
        } + events.map { DayArc.Item(it.startMinute, it.endMinute, it.title, DayArc.Kind.EVENT) } +
                blocks.map { DayArc.Item(it.start, it.end, it.title, DayArc.Kind.EVENT) } +
                skeleton.meals.slots.map { DayArc.Item(it.start, it.end, it.kind.label, DayArc.Kind.MEAL) }
        val legs = skeleton.legs.map { DayArc.Leg(it.prepStart, it.start, it.end, places[it.toPlaceId] ?: "alt loc") }

        val runningSession = repo.runningSession()
        val runningActivity = runningSession?.let { repo.getActivity(it.activityId) }
        val focus = DayArc.build(sleep.wake, bedtime, items, legs, now, runningActivity?.title).focus

        // ce urmează: tot ce nu e gata, inclusiv cursurile și mesele
        val lines = items.filter { it.kind != DayArc.Kind.DONE }.map { WidgetPick.Line(it.start, it.end, it.title) }

        val target = WidgetPick.timerTarget(
            activities.map { WidgetPick.Candidate(it.id, it.title, it.plannedStartMinute, it.userEstimatedMinutes ?: it.estimatedMinutes ?: 30, it.state) },
            now
        )

        return WidgetState(
            dateLabel = "${DAY_NAMES[today.dayOfWeek.value - 1]}, ${today.dayOfMonth} ${MONTHS[today.monthValue - 1]}",
            focus = focus,
            lines = WidgetPick.upcoming(lines, now),
            timerId = runningActivity?.id ?: target?.id,
            timerTitle = runningActivity?.title ?: target?.title,
            running = runningActivity != null,
            runningSince = runningSession?.let {
                Instant.ofEpochMilli(it.startedAt).atZone(ZoneId.systemDefault()).toLocalTime()
                    .let { t -> "%d:%02d".format(t.hour, t.minute) }
            }
        )
    }
}

@Composable
private fun WidgetContent(state: WidgetState?) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(Paper)
            .cornerRadius(24.dp)
            .padding(16.dp)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        if (state == null) {
            Text("Deschide aplicația ca să-ți vezi ziua.", style = TextStyle(color = ColorProvider(Ink), fontSize = 14.sp))
            return@Column
        }

        Text(state.dateLabel, style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp, fontWeight = FontWeight.Medium))
        Spacer(GlanceModifier.height(6.dp))
        Text(
            state.focus.label.uppercase(),
            style = TextStyle(color = ColorProvider(Muted), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DayArc.duration(state.focus.minutes),
                style = TextStyle(color = ColorProvider(Ink), fontSize = 28.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(GlanceModifier.width(10.dp))
            Text(state.focus.sentence, style = TextStyle(color = ColorProvider(Ink), fontSize = 13.sp), maxLines = 2)
        }

        if (state.lines.isNotEmpty()) {
            Spacer(GlanceModifier.height(8.dp))
            state.lines.forEach { line ->
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(
                        DayArc.clock(line.start),
                        style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.width(46.dp)
                    )
                    Text(line.title, style = TextStyle(color = ColorProvider(Ink), fontSize = 13.sp), maxLines = 1)
                }
            }
        }

        // cronometrul: un singur buton, închis la culoare, ca „+” din aplicație
        val id = state.timerId
        val title = state.timerTitle
        if (id != null && title != null) {
            Spacer(GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .background(Ink)
                    .cornerRadius(20.dp)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .clickable(actionRunCallback<ToggleTimerAction>(actionParametersOf(ToggleTimerAction.TARGET to id))),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (state.running) "⏸  Oprește: $title" + (state.runningSince?.let { " · din $it" } ?: "")
                    else "▶  Pornește: $title",
                    style = TextStyle(color = ColorProvider(Paper), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
            }
        }
    }
}
