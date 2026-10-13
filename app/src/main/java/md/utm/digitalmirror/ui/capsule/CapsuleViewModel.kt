package md.utm.digitalmirror.ui.capsule

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.capsule.CapsuleVideo
import md.utm.digitalmirror.data.capsule.GoalEvaluator
import md.utm.digitalmirror.data.dao.PeriodStats
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.CapsuleGoal
import md.utm.digitalmirror.data.entity.Goal
import md.utm.digitalmirror.data.entity.TimeCapsule
import md.utm.digitalmirror.domain.CapsuleLogic
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CapsuleViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.capsuleDao()
    private val goalDao = db.goalDao()

    /** O capsulă cu tot ce trebuie ecranului. */
    data class CapsuleCard(
        val capsule: TimeCapsule,
        val state: CapsuleLogic.State,
        val daysLeft: Int,                       // până la data stabilită; negativ dacă a trecut
        val nudge: CapsuleLogic.Nudge,
        val goals: List<CapsuleLogic.GoalLine>,
        val openedOn: LocalDate?
    )

    /** Obiectivele active, ca să poată fi legate de o capsulă nouă. */
    val activeGoals: StateFlow<List<Goal>> =
        goalDao.observeGoals()
            .map { goals -> goals.filter { it.state == Goal.STATE_ACTIVE } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<CapsuleCard>> =
        combine(
            dao.observeCapsules(),
            dao.observeLinks(),
            goalDao.observeGoals(),
            goalDao.observeLinks(),
            goalDao.observeClosed()
        ) { capsules, capsuleLinks, goals, goalLinks, closed ->
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val goalsById = goals.associateBy { it.id }

            capsules.map { capsule ->
                val openedOn = capsule.openedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                val unlock = LocalDate.parse(capsule.unlockDate)
                val state = CapsuleLogic.state(unlock, opened = capsule.openedAt != null, today = today)

                // progresul obiectivelor la data deschiderii (sau azi, cât capsula e sigilată)
                val results = GoalEvaluator.evaluate(goals, goalLinks, closed, openedOn ?: today)
                val links = capsuleLinks.filter { it.capsuleId == capsule.id }

                CapsuleCard(
                    capsule = capsule,
                    state = state,
                    daysLeft = java.time.temporal.ChronoUnit.DAYS.between(today, unlock).toInt(),
                    nudge = CapsuleLogic.nudge(
                        state = state,
                        openWhenSlowing = capsule.openWhenSlowing,
                        createdOn = LocalDate.parse(capsule.startDate),
                        lastNudge = null,
                        momenta = GoalEvaluator.activeMomenta(links.map { it.goalId }, goals, results),
                        today = today
                    ),
                    goals = links.map { link ->
                        CapsuleLogic.GoalLine(
                            title = goalsById[link.goalId]?.title ?: "Obiectiv șters",
                            percentStart = link.percentAtStart,
                            percentNow = results[link.goalId]?.percent?.takeIf { link.goalId in goalsById }
                        )
                    },
                    openedOn = openedOn
                )
            }.sortedWith(
                compareBy<CapsuleCard> {
                    when (it.state) {
                        CapsuleLogic.State.READY -> 0
                        CapsuleLogic.State.LOCKED -> 1
                        CapsuleLogic.State.OPENED -> 2
                    }
                }.thenBy { it.capsule.unlockDate }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Sigilează o capsulă nouă. Progresul de acum al obiectivelor legate devine punctul de pornire. */
    fun create(
        title: String,
        message: String,
        expectations: String,
        unlockDate: LocalDate,
        goalIds: Set<Long>,
        videoFile: String?,
        openWhenSlowing: Boolean
    ) = viewModelScope.launch {
        val today = LocalDate.now()
        val results = GoalEvaluator.evaluate(
            goalDao.observeGoals().first(), goalDao.observeLinks().first(), goalDao.observeClosed().first(), today
        )
        val id = dao.insertCapsule(
            TimeCapsule(
                title = title.trim(),
                message = message.trim(),
                expectations = expectations.trim(),
                startDate = today.toString(),
                unlockDate = unlockDate.toString(),
                createdAt = System.currentTimeMillis(),
                openedAt = null,
                videoFile = videoFile,
                openWhenSlowing = openWhenSlowing && goalIds.isNotEmpty(),
                lastNudgeDate = null,
                readyNotified = false
            )
        )
        dao.insertLinks(goalIds.map { CapsuleGoal(id, it, results[it]?.percent ?: 0) })
    }

    fun open(capsule: TimeCapsule) = viewModelScope.launch {
        dao.markOpened(capsule.id, System.currentTimeMillis())
    }

    fun delete(capsule: TimeCapsule) = viewModelScope.launch {
        CapsuleVideo.delete(getApplication(), capsule.videoFile)
        dao.deleteLinks(capsule.id)
        dao.deleteCapsule(capsule)
    }

    /** Ce s-a lucrat între sigilare și deschidere (sau azi, dacă încă nu a fost deschisă). */
    suspend fun periodStats(card: CapsuleCard): PeriodStats {
        val end = card.openedOn ?: LocalDate.now()
        return dao.periodStats(card.capsule.startDate, end.toString())
    }
}