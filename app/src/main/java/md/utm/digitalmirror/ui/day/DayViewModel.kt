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
import md.utm.digitalmirror.data.entity.DailyActivity
import md.utm.digitalmirror.data.entity.Reflection
import md.utm.digitalmirror.data.entity.WorkSession
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.HistoryEstimator
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.reminder.ActivityReminders
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DayViewModel(private val app: Application) : AndroidViewModel(app) {

    data class FinishDraft(val activity: Activity, val trackedMinutes: Int)

    // 1. repository
    private val repo: PlannerRepository = AppDatabase.get(app).let { db ->
        PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())
    }
    private val reflectionDao = AppDatabase.get(app).reflectionDao()
    private val dailyActivityDao = AppDatabase.get(app).dailyActivityDao()

    // 2. starea
    private val _date = MutableStateFlow(LocalDate.now().toString())
    val date: StateFlow<String> = _date

    // cronometrul pornit, urmărit direct din baza de date:
    // așa ecranul se actualizează și când utilizatorul apasă Start/Pauză/Gata dintr-o notificare
    val runningSession: StateFlow<WorkSession?> =
        repo.observeRunningSession()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // timpul din sesiunile anterioare ale activității care rulează
    val runningOffset: StateFlow<Long> =
        repo.observeRunningSession()
            .mapLatest { session -> session?.let { repo.closedMillis(it.activityId) } ?: 0L }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _finishDraft = MutableStateFlow<FinishDraft?>(null)
    val finishDraft: StateFlow<FinishDraft?> = _finishDraft

    val categories: StateFlow<List<Category>> =
        repo.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activities: StateFlow<List<Activity>> =
        _date.flatMapLatest { repo.observeActivitiesForDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // reflecția zilei afișate; null = ziua nu a fost încă închisă
    val reflection: StateFlow<Reflection?> =
        _date.flatMapLatest { reflectionDao.observeByDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // somnul și pașii zilei afișate (din Health Connect)
    // încărcarea zilei: cât e planificat vs. cât reușește utilizatorul de obicei
    val dayLoad: StateFlow<DayLoad.Result?> =
        _date.flatMapLatest { date ->
            val history = flow { emit(repo.dailyTotals(date)) }
            combine(repo.observeActivitiesForDate(date), history) { list, totals ->
                val planned = list
                    .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
                    .sumOf { it.userEstimatedMinutes ?: it.estimatedMinutes ?: 0 }
                DayLoad.compute(planned, totals, LocalDate.parse(date))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val health: StateFlow<DailyActivity?> =
        _date.flatMapLatest { dailyActivityDao.observeByDate(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // 3. păstrat pentru compatibilitate cu DayScreen; starea cronometrului vine acum direct din baza de date
    fun refreshRunning() = Unit

    // 4. navigare între zile
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

    // 5. categorii și activități
    fun addCategory(name: String) = viewModelScope.launch {
        if (name.isNotBlank()) repo.addCategory(name.trim())
    }

    /**
     * Adaugă o activitate nouă.
     * @param plannedMinutes durata planificată finală (ce a rămas în formular)
     * @param guessMinutes cât a crezut utilizatorul înainte să vadă propunerea
     * @param estimate propunerea estimatorului, așa cum a văzut-o utilizatorul
     */
    fun addActivity(
        title: String,
        categoryId: Long?,
        plannedMinutes: Int?,
        startMinute: Int?,
        guessMinutes: Int?,
        estimate: HistoryEstimator.Estimate?
    ) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        val activity = Activity(
            title = title.trim(),
            categoryId = categoryId,
            date = _date.value,
            plannedStartMinute = startMinute,
            userGuessMinutes = guessMinutes,
            estimatedMinutes = estimate?.minutes,
            estimatedLowMinutes = estimate?.lowMinutes,
            estimatedHighMinutes = estimate?.highMinutes,
            userEstimatedMinutes = plannedMinutes,
            actualMinutes = null
        )
        val id = repo.addActivity(activity)
        ActivityReminders.onActivityPlanned(app, activity.copy(id = id))
    }

    // la editare păstrăm ghicitul și propunerea inițiale; se schimbă doar ce a modificat utilizatorul
    fun editActivity(
        activityId: Long,
        title: String,
        categoryId: Long?,
        plannedMinutes: Int?,
        startMinute: Int?
    ) = viewModelScope.launch {
        if (title.isBlank()) return@launch
        // citim varianta proaspătă din baza de date, ca să nu pierdem timpul cronometrat între timp
        val fresh = repo.getActivity(activityId) ?: return@launch
        val updated = fresh.copy(
            title = title.trim(),
            categoryId = categoryId,
            userEstimatedMinutes = plannedMinutes,
            plannedStartMinute = startMinute
        )
        repo.updateActivity(updated)
        ActivityReminders.onActivityPlanned(app, updated)
    }

    // istoricul pentru estimatorul pe niveluri (doar activități finalizate, din surse sigure)
    suspend fun estimatorHistory(): List<HistoryEstimator.Sample> = repo.estimatorHistory()

    // istoricul orelor de start (și dacă activitatea a fost finalizată), folosit de recomandarea intervalului
    suspend fun slotHistory(categoryId: Long): List<SlotRecommender.Outcome> =
        repo.slotHistory(categoryId)

    fun postponeActivity(activity: Activity, newDate: String) = viewModelScope.launch {
        ActivityReminders.onActivityClosed(app, activity.id)
        repo.postponeActivity(activity.id, newDate)
        repo.getActivity(activity.id)?.let { ActivityReminders.onActivityPlanned(app, it) }
    }

    fun setState(activity: Activity, state: String) = viewModelScope.launch {
        repo.updateActivity(activity.copy(state = state))
    }

    fun deleteActivity(activity: Activity) = viewModelScope.launch {
        repo.runningSession()
            ?.takeIf { it.activityId == activity.id }
            ?.let { repo.stopTimer(it) }
        repo.deleteActivity(activity)
        ActivityReminders.onActivityClosed(app, activity.id)
    }

    // 6. cronometru și finalizare
    fun toggleTimer(activity: Activity) = viewModelScope.launch {
        val running = repo.runningSession()
        if (running != null && running.activityId == activity.id) {
            repo.stopTimer(running)
            ActivityReminders.onTimerStopped(app, activity.id)
        } else {
            // dacă rula altă activitate, startTimer o oprește; scoatem și notificarea ei
            running?.let { ActivityReminders.onTimerStopped(app, it.activityId) }
            repo.startTimer(activity.id)
            repo.runningSession()?.let { session ->
                ActivityReminders.onTimerStarted(
                    app, repo.getActivity(activity.id) ?: activity,
                    session.startedAt, repo.closedMillis(activity.id)
                )
            }
        }
    }

    fun requestFinish(activity: Activity) = viewModelScope.launch {
        _finishDraft.value = FinishDraft(activity, repo.trackedMinutes(activity.id))
    }

    fun confirmFinish(minutes: Int, state: String) = viewModelScope.launch {
        val draft = _finishDraft.value ?: return@launch
        repo.finishActivity(draft.activity.id, minutes, state)
        ActivityReminders.onActivityClosed(app, draft.activity.id)
        _finishDraft.value = null
    }

    fun dismissFinish() {
        _finishDraft.value = null
    }
}