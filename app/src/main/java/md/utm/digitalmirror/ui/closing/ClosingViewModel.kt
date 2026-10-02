package md.utm.digitalmirror.ui.closing

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Reflection
import md.utm.digitalmirror.data.repository.PlannerRepository

@OptIn(ExperimentalCoroutinesApi::class)
class ClosingViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val planner = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    private val reflectionDao = db.reflectionDao()

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

    fun load(date: String) {
        _date.value = date
        viewModelScope.launch {
            _reflection.value = reflectionDao.getByDate(date)
            val list = planner.observeActivitiesForDate(date).first()
            _tracked.value = list.associate { it.id to planner.trackedMinutes(it.id) }
        }
    }

    fun resolveActivity(activity: Activity, minutes: Int, state: String) =
        viewModelScope.launch {
            planner.finishActivity(activity.id, minutes, state)
        }

    fun saveReflection(
        mood: Int,
        workedOn: String,
        blockers: String,
        note: String,
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
        onDone()
    }
}