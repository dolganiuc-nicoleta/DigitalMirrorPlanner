package md.utm.digitalmirror.data.repository
import md.utm.digitalmirror.domain.DayLoad
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.dao.ActivityDao
import md.utm.digitalmirror.data.dao.CategoryDao
import md.utm.digitalmirror.data.dao.WorkSessionDao
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.WorkSession
import md.utm.digitalmirror.domain.DurationEstimator
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.domain.HistoryEstimator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.Instant
import java.time.ZoneId
class PlannerRepository(
    private val activityDao: ActivityDao,
    private val categoryDao: CategoryDao,
    private val sessionDao: WorkSessionDao
) {

    fun observeCategories(): Flow<List<Category>> = categoryDao.observeAll()

    fun observeActivitiesForDate(date: String): Flow<List<Activity>> =
        activityDao.observeByDate(date)

    // dacă există deja o categorie cu același nume, o refolosim în loc să creăm un duplicat
    suspend fun addCategory(name: String): Long =
        categoryDao.findByName(name)?.id ?: categoryDao.insert(Category(name = name))

    suspend fun addActivity(activity: Activity): Long =
        activityDao.insert(activity.copy(titleKey = TitleKey.of(activity.title)))

    suspend fun updateActivity(activity: Activity) =
        activityDao.update(activity.copy(titleKey = TitleKey.of(activity.title)))

    suspend fun deleteActivity(activity: Activity) =
        activityDao.delete(activity)

    suspend fun runningSession(): WorkSession? = sessionDao.getRunning()

    suspend fun closedMillis(activityId: Long): Long =
        sessionDao.totalMillisForActivity(activityId)
    suspend fun startTimer(activityId: Long) {
        sessionDao.getRunning()?.let { stopTimer(it) }
        sessionDao.insert(
            WorkSession(
                activityId = activityId,
                startedAt = System.currentTimeMillis(),
                endedAt = null
            )
        )
        activityDao.getById(activityId)?.let {
            activityDao.update(it.copy(state = "IN_PROGRESS"))
        }
    }

    suspend fun stopTimer(session: WorkSession) {
        sessionDao.update(session.copy(endedAt = System.currentTimeMillis()))
        recomputeActualMinutes(session.activityId)
    }

    suspend fun trackedMinutes(activityId: Long): Int {
        val closed = sessionDao.totalMillisForActivity(activityId)
        val live = sessionDao.getRunning()
            ?.takeIf { it.activityId == activityId }
            ?.let { System.currentTimeMillis() - it.startedAt } ?: 0L
        return ((closed + live + 30_000L) / 60_000L).toInt()   // rotunjire la minut
    }

    suspend fun finishActivity(activityId: Long, actualMinutes: Int, state: String) {
        sessionDao.getRunning()
            ?.takeIf { it.activityId == activityId }
            ?.let { sessionDao.update(it.copy(endedAt = System.currentTimeMillis())) }

        // dacă există sesiuni de cronometru, durata vine din cronometru; altfel a fost introdusă manual
        val timed = sessionDao.totalMillisForActivity(activityId) > 0
        activityDao.getById(activityId)?.let {
            activityDao.update(
                it.copy(
                    actualMinutes = actualMinutes,
                    state = state,
                    actualSource = if (timed) Activity.SOURCE_TIMER else Activity.SOURCE_MANUAL
                )
            )
        }
    }

    private suspend fun recomputeActualMinutes(activityId: Long) {
        val minutes = trackedMinutes(activityId)
        activityDao.getById(activityId)?.let {
            activityDao.update(it.copy(actualMinutes = minutes, actualSource = Activity.SOURCE_TIMER))
        }
    }

    suspend fun getActivity(id: Long): Activity? = activityDao.getById(id)

    suspend fun postponeActivity(activityId: Long, newDate: String) {
        // dacă rulează cronometrul pe ea, îl oprim întâi (se salvează timpul lucrat)
        sessionDao.getRunning()
            ?.takeIf { it.activityId == activityId }
            ?.let { stopTimer(it) }

        val fresh = activityDao.getById(activityId) ?: return
        activityDao.update(
            fresh.copy(
                date = newDate,
                state = if ((fresh.actualMinutes ?: 0) > 0) "IN_PROGRESS" else "PLANNED",
                postponedCount = fresh.postponedCount + 1
            )
        )
    }

    suspend fun durationHistory(categoryId: Long): List<DurationEstimator.Sample> =
        activityDao.getDurationHistory(categoryId, 20).map {
            DurationEstimator.Sample(
                estimatedMinutes = it.userEstimatedMinutes ?: 0,
                actualMinutes = it.actualMinutes ?: 0
            )
        }

    fun observeActivitiesInRange(from: String, to: String): Flow<List<Activity>> =
        activityDao.observeRange(from, to)

    suspend fun slotHistory(categoryId: Long): List<SlotRecommender.Outcome> =
        activityDao.getClosedByCategory(categoryId, 60).mapNotNull { activity ->
            // ora reală de start (prima pornire a cronometrului), altfel ora planificată
            val start = sessionDao.firstStartFor(activity.id)?.let { millis ->
                val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
                time.hour * 60 + time.minute
            } ?: activity.plannedStartMinute

            start?.let { SlotRecommender.Outcome(it, success = activity.state == "DONE") }
        }
    suspend fun renameCategory(category: Category, newName: String) =
        categoryDao.update(category.copy(name = newName))

    suspend fun deleteCategory(category: Category) {
        activityDao.clearCategory(category.id)   // întâi scoatem categoria de pe activități
        categoryDao.delete(category)
    }

    suspend fun estimatorHistory(): List<HistoryEstimator.Sample> {
        val today = LocalDate.now()
        return activityDao.getEstimatorHistory(300).map {
            HistoryEstimator.Sample(
                titleKey = it.titleKey,
                categoryId = it.categoryId,
                // ce a crezut utilizatorul: ghicitul, iar la activitățile vechi durata planificată
                baseMinutes = it.userGuessMinutes ?: it.userEstimatedMinutes,
                actualMinutes = it.actualMinutes ?: 0,
                ageDays = ChronoUnit.DAYS.between(LocalDate.parse(it.date), today).toDouble()
            )
        }
    }

    suspend fun dailyTotals(before: String): List<DayLoad.DayTotal> =
        activityDao.dailyTotals(before, 90).map {
            DayLoad.DayTotal(LocalDate.parse(it.date), it.minutes)
        }

    fun observeRunningSession(): Flow<WorkSession?> = sessionDao.observeRunning()

    suspend fun upcomingPlanned(fromDate: String): List<Activity> =
        activityDao.getUpcomingPlanned(fromDate)

    suspend fun closeActivity(activityId: Long, actualMinutes: Int?, state: String, source: String?) {
        sessionDao.getRunning()
            ?.takeIf { it.activityId == activityId }
            ?.let { sessionDao.update(it.copy(endedAt = System.currentTimeMillis())) }

        activityDao.getById(activityId)?.let {
            activityDao.update(it.copy(actualMinutes = actualMinutes, state = state, actualSource = source))
        }
    }

}