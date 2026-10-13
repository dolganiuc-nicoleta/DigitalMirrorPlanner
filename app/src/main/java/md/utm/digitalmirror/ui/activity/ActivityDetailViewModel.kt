package md.utm.digitalmirror.ui.activity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.WorkSession
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.data.usage.UsageCollector
import md.utm.digitalmirror.data.usage.UsageReader
import md.utm.digitalmirror.domain.ActivityInsight

class ActivityDetailViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())

    data class UiState(
        val activity: Activity,
        val categoryName: String?,
        val sessions: List<WorkSession>,           // sesiunile de cronometru ale acestei activități
        val history: List<Activity>,               // celelalte activități cu același titlu, închise
        val insight: ActivityInsight.Result,
        val usageEnabled: Boolean,                      // accesul la statistici e acordat
        val usage: Map<Long, SessionPhoneUsage>         // cheia = startedAt al sesiunii
    )

    /** Timpul pe telefon dintr-o sesiune, cu numele aplicațiilor deja rezolvate. */
    data class SessionPhoneUsage(val minutes: Int, val apps: List<Pair<String, Int>>)

    private val _state = MutableStateFlow<UiState?>(null)
    val state: StateFlow<UiState?> = _state

    fun load(activityId: Long) = viewModelScope.launch {
        val activity = repo.getActivity(activityId) ?: return@launch
        val categoryName = activity.categoryId?.let { id ->
            db.categoryDao().getById(id)?.name
        }

        // aceeași activitate în alte zile, doar cele închise cu durată
        val history = activity.titleKey?.let { key ->
            repo.activitiesWithTitleKey(key)
                .filter { it.id != activity.id && it.state in setOf("DONE", "PARTIAL") && it.actualMinutes != null }
        }.orEmpty()

        // salvăm și sesiunile terminate de curând, ca pagina să fie la zi
        UsageCollector.collectMissing(getApplication())
        val usage = db.sessionUsageDao().getForActivity(activityId).associate { u ->
            u.startedAt to SessionPhoneUsage(
                minutes = (u.phoneMillis / 60_000).toInt(),
                apps = UsageCollector.parseTopApps(u.topApps)
                    .map { UsageReader.label(getApplication(), it.pkg) to (it.millis / 60_000).toInt() }
                    .filter { it.second > 0 }
            )
        }

        val insight = ActivityInsight.compute(
            history.map {
                ActivityInsight.Session(
                    plannedMinutes = it.userEstimatedMinutes ?: it.estimatedMinutes,
                    actualMinutes = it.actualMinutes ?: 0,
                    source = it.actualSource
                )
            }
        )

        _state.value = UiState(
            activity = activity,
            categoryName = categoryName,
            sessions = repo.sessionsFor(activityId),
            history = history,
            insight = insight,
            usageEnabled = UsageReader.hasPermission(getApplication()),
            usage = usage
        )
    }
}