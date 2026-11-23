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
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.CapsuleBet
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.repository.PlannerRepository
import md.utm.digitalmirror.domain.CapsuleBets
import md.utm.digitalmirror.domain.PeriodReport
import md.utm.digitalmirror.domain.SleepPlanner
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
        val openedOn: LocalDate?,
        val bets: List<CapsuleBets.Bet> = emptyList()
    )

    /** Obiectivele active, ca să poată fi legate de o capsulă nouă. */
    val activeGoals: StateFlow<List<Goal>> =
        goalDao.observeGoals()
            .map { goals -> goals.filter { it.state == Goal.STATE_ACTIVE } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val cards: StateFlow<List<CapsuleCard>> =
        combine(
            combine(dao.observeCapsules(), dao.observeBets()) { c, b -> c to b },
            dao.observeLinks(),
            goalDao.observeGoals(),
            goalDao.observeLinks(),
            goalDao.observeClosed()
        ) { (capsules, allBets), capsuleLinks, goals, goalLinks, closed ->
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
                    openedOn = openedOn,
                    bets = allBets.filter { it.capsuleId == capsule.id }.mapNotNull { b ->
                        runCatching { CapsuleBets.Kind.valueOf(b.kind) }.getOrNull()
                            ?.let { CapsuleBets.Bet(it, b.predicted, b.categoryId) }
                    }
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

    // ---- pariurile: ce prezici la sigilare și ce a fost la deschidere ----

    private val repo = PlannerRepository(db.activityDao(), db.categoryDao(), db.workSessionDao())

    val categories: StateFlow<List<Category>> =
        repo.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Propunerile de la sigilare, din ultima lună: categoria la care ai lucrat cel mai mult, somnul, cât termini. */
    data class Suggestions(val categoryId: Long?, val hoursMinutes: Int, val sleepMinutes: Int, val completion: Int)

    suspend fun suggestions(unlockDate: LocalDate): Suggestions {
        val today = LocalDate.now()
        val from = today.minusDays(PAST_DAYS.toLong())
        val past = repo.observeActivitiesInRange(from.toString(), today.minusDays(1).toString()).first()
        val finished = past.filter { it.state in FINISHED }
        val byCategory = finished.filter { it.categoryId != null }.groupBy { it.categoryId!! }
            .mapValues { (_, list) -> list.sumOf { it.actualMinutes ?: 0 } }
        val best = byCategory.maxByOrNull { it.value }
        val futureDays = java.time.temporal.ChronoUnit.DAYS.between(today, unlockDate).toInt().coerceAtLeast(1)
        return Suggestions(
            categoryId = best?.key,
            hoursMinutes = CapsuleBets.suggestHours(best?.value ?: 0, PAST_DAYS, futureDays),
            sleepMinutes = CapsuleBets.suggestSleep(nights(from, today.minusDays(1))),
            completion = CapsuleBets.suggestCompletion(completion(past, from, today.minusDays(1)))
        )
    }

    /** Ce s-a întâmplat cu pariurile, plus cât de bine îți estimai timpul atunci și acum. */
    data class BetReport(
        val results: List<CapsuleBets.Result>,
        val errorAtStart: Int?,
        val errorNow: Int?
    )

    suspend fun betReport(card: CapsuleCard): BetReport {
        val start = LocalDate.parse(card.capsule.startDate)
        val end = card.openedOn ?: LocalDate.now()
        val list = repo.observeActivitiesInRange(start.toString(), end.toString()).first()
        val finished = list.filter { it.state in FINISHED }
        val results = card.bets.map { bet ->
            val actual = when (bet.kind) {
                CapsuleBets.Kind.HOURS -> finished.filter { it.categoryId == bet.categoryId }.sumOf { it.actualMinutes ?: 0 }
                CapsuleBets.Kind.SLEEP -> nights(start, end).filter(SleepPlanner::plausible).mapNotNull { it.sleepMinutes }
                    .takeIf { it.size >= SleepPlanner.MIN_NIGHTS }?.average()?.toInt()
                CapsuleBets.Kind.COMPLETION -> completion(list, start, end)
            }
            CapsuleBets.result(bet, actual)
        }
        return BetReport(results, card.capsule.estimateErrorAtStart, CapsuleBets.estimateError(samples(finished)))
    }

    private suspend fun nights(from: LocalDate, to: LocalDate): List<SleepPlanner.Night> =
        db.dailyActivityDao().between(from.toString(), to.toString())
            .map { SleepPlanner.Night(it.sleepMinutes, it.bedtimeMinute, it.wakeMinute) }

    /** Cât din ce era planificat s-a terminat, ca în raportul săptămânal (parțialele pe jumătate). */
    private fun completion(list: List<Activity>, from: LocalDate, to: LocalDate): Int? {
        val items = list.map {
            PeriodReport.Item(LocalDate.parse(it.date), it.state, it.actualMinutes, it.categoryId, it.title, it.titleKey, it.postponedCount)
        }
        val s = PeriodReport.summarize(items, from, to, LocalDate.now())
        return CapsuleBets.completionRate(s.due, s.done, s.partial)
    }

    /** Activitățile măsurate (cronometru, locație, corectate), nu cele copiate din plan. */
    private fun samples(finished: List<Activity>): List<CapsuleBets.Sample> =
        finished.filter { it.actualSource != Activity.SOURCE_AS_PLANNED }
            .mapNotNull { a -> a.userEstimatedMinutes?.let { p -> a.actualMinutes?.let { CapsuleBets.Sample(p, it) } } }

    /** Sigilează o capsulă nouă. Progresul de acum al obiectivelor legate devine punctul de pornire. */
    fun create(
        title: String,
        message: String,
        expectations: String,
        unlockDate: LocalDate,
        goalIds: Set<Long>,
        videoFile: String?,
        openWhenSlowing: Boolean,
        bets: List<CapsuleBets.Bet> = emptyList()
    ) = viewModelScope.launch {
        val today = LocalDate.now()
        val results = GoalEvaluator.evaluate(
            goalDao.observeGoals().first(), goalDao.observeLinks().first(), goalDao.observeClosed().first(), today
        )
        // „fotografia” de acum: cât de bine îți estimezi timpul în ultima lună
        val recent = repo.observeActivitiesInRange(today.minusDays(PAST_DAYS.toLong()).toString(), today.toString()).first()
        val errorNow = CapsuleBets.estimateError(samples(recent.filter { it.state in FINISHED }))
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
                readyNotified = false,
                estimateErrorAtStart = errorNow
            )
        )
        dao.insertLinks(goalIds.map { CapsuleGoal(id, it, results[it]?.percent ?: 0) })
        dao.insertBets(bets.map { CapsuleBet(capsuleId = id, kind = it.kind.name, predicted = it.predicted, categoryId = it.categoryId) })
    }

    fun open(capsule: TimeCapsule) = viewModelScope.launch {
        dao.markOpened(capsule.id, System.currentTimeMillis())
    }

    fun delete(capsule: TimeCapsule) = viewModelScope.launch {
        CapsuleVideo.delete(getApplication(), capsule.videoFile)
        dao.deleteLinks(capsule.id)
        dao.deleteBets(capsule.id)
        dao.deleteCapsule(capsule)
    }

    /** Ce s-a lucrat între sigilare și deschidere (sau azi, dacă încă nu a fost deschisă). */
    suspend fun periodStats(card: CapsuleCard): PeriodStats {
        val end = card.openedOn ?: LocalDate.now()
        return dao.periodStats(card.capsule.startDate, end.toString())
    }

    private companion object {
        const val PAST_DAYS = 30
        val FINISHED = setOf("DONE", "PARTIAL")
    }
}