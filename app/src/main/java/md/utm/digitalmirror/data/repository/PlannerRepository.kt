package md.utm.digitalmirror.data.repository

import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.dao.ActivityDao
import md.utm.digitalmirror.data.dao.CategoryDao
import md.utm.digitalmirror.data.dao.WorkSessionDao
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.WorkSession

class PlannerRepository(
    private val activityDao: ActivityDao,
    private val categoryDao: CategoryDao,
    private val sessionDao: WorkSessionDao
) {

    fun observeCategories(): Flow<List<Category>> = categoryDao.observeAll()

    fun observeActivitiesForDate(date: String): Flow<List<Activity>> =
        activityDao.observeByDate(date)

    suspend fun addCategory(name: String): Long =
        categoryDao.insert(Category(name = name))

    suspend fun addActivity(activity: Activity): Long =
        activityDao.insert(activity)

    suspend fun updateActivity(activity: Activity) =
        activityDao.update(activity)

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

        activityDao.getById(activityId)?.let {
            activityDao.update(it.copy(actualMinutes = actualMinutes, state = state))
        }
    }

    private suspend fun recomputeActualMinutes(activityId: Long) {
        val minutes = trackedMinutes(activityId)
        activityDao.getById(activityId)?.let {
            activityDao.update(it.copy(actualMinutes = minutes))
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
}