package md.utm.digitalmirror.ui.day

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.WorkSession
import md.utm.digitalmirror.data.repository.PlannerRepository
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(app: Application) : AndroidViewModel(app) {

    data class FinishDraft(val activity: Activity, val trackedMinutes: Int)

    // 1. repository
    private val repo: PlannerRepository = AppDatabase.get(app).let { db ->
        PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    }

    // 2. starea
    private val _date = MutableStateFlow(LocalDate.now().toString())
    val date: StateFlow<String> = _date

    private val _runningSession = MutableStateFlow<WorkSession?>(null)
    val runningSession: StateFlow<WorkSession?> = _runningSession

    private val _runningOffset = MutableStateFlow(0L)
    val runningOffset: StateFlow<Long> = _runningOffset

    private val _finishDraft = MutableStateFlow<FinishDraft?>(null)
    val finishDraft: StateFlow<FinishDraft?> = _finishDraft

    val categories: StateFlow<List<Category>> =
        repo.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activities: StateFlow<List<Activity>> =
        _date.flatMapLatest { repo.observeActivitiesForDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 3. restaurează cronometrul dacă aplicația a fost închisă cu el pornit
    init {
        viewModelScope.launch {
            val session = repo.runningSession()
            session?.let { _runningOffset.value = repo.closedMillis(it.activityId) }
            _runningSession.value = session
        }
    }

    // 4. acțiuni
    fun changeDate(newDate: String) {
        _date.value = newDate
    }
    fun previousDay() {
        _date.value = LocalDate.parse(_date.value).minusDays(1).toString()
    }

    fun nextDay() {
        _date.value = LocalDate.parse(_date.value).plusDays(1).toString()
    }

    fun goToToday() {
        _date.value = LocalDate.now().toString()
    }

    fun refreshRunning() = viewModelScope.launch {
        val session = repo.runningSession()
        session?.let { _runningOffset.value = repo.closedMillis(it.activityId) }
        _runningSession.value = session
    }

    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.addCategory(name.trim())
    }

    fun addActivity(title: String, categoryId: Long?, estimatedMinutes: Int?) =
        viewModelScope.launch {
            if (title.isBlank()) return@launch
            repo.addActivity(
                Activity(
                    title = title.trim(),
                    categoryId = categoryId,
                    date = _date.value,
                    plannedStartMinute = null,
                    estimatedMinutes = null,
                    userEstimatedMinutes = estimatedMinutes,
                    actualMinutes = null
                )
            )
        }

    fun toggleTimer(activity: Activity) = viewModelScope.launch {
        val running = repo.runningSession()
        if (running != null && running.activityId == activity.id) {
            repo.stopTimer(running)
            _runningSession.value = null
        } else {
            _runningOffset.value = repo.closedMillis(activity.id)
            repo.startTimer(activity.id)
            _runningSession.value = repo.runningSession()
        }
    }

    fun setState(activity: Activity, state: String) = viewModelScope.launch {
        repo.updateActivity(activity.copy(state = state))
    }

    fun deleteActivity(activity: Activity) = viewModelScope.launch {
        repo.runningSession()
            ?.takeIf { it.activityId == activity.id }
            ?.let { repo.stopTimer(it) }
        repo.deleteActivity(activity)
        _runningSession.value = repo.runningSession()
    }

    fun requestFinish(activity: Activity) = viewModelScope.launch {
        _finishDraft.value = FinishDraft(activity, repo.trackedMinutes(activity.id))
    }

    fun confirmFinish(minutes: Int, state: String) = viewModelScope.launch {
        val draft = _finishDraft.value ?: return@launch
        repo.finishActivity(draft.activity.id, minutes, state)
        _runningSession.value = repo.runningSession()
        _finishDraft.value = null
    }

    fun dismissFinish() {
        _finishDraft.value = null
    }
}