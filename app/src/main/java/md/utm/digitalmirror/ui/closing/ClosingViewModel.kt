package md.utm.digitalmirror.ui.closing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.DailyActivity
import md.utm.digitalmirror.data.entity.Reflection
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.reminder.ActivityReminders
import java.time.LocalDate

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
        combine(activities, _tracked, _edits) { list, tracked, edits ->
            list.filter { it.state !in CLOSED_STATES }
                .map { a -> edits[a.id] ?: defaultDraft(a, tracked[a.id] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun defaultDraft(a: Activity, tracked: Int): Draft {
        val planned = a.userEstimatedMinutes ?: a.estimatedMinutes
        return when {
            tracked > 0 -> Draft(a, "DONE", tracked, Activity.SOURCE_TIMER, tracked)
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

    companion object {
        // de la culcare la trezire, trecând peste miezul nopții: 23:30 → 07:00 = 450 min
        fun sleepDuration(bedtime: Int, wake: Int): Int = (wake - bedtime + 24 * 60) % (24 * 60)
    }
}