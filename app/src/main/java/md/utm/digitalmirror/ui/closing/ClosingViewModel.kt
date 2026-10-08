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

@OptIn(ExperimentalCoroutinesApi::class)
class ClosingViewModel(app: Application) : AndroidViewModel(app) {

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

    fun resolveActivity(activity: Activity, minutes: Int, state: String) =
        viewModelScope.launch {
            planner.finishActivity(activity.id, minutes, state)
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