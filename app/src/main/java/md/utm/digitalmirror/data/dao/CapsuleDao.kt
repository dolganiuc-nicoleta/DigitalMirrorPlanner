package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.CapsuleGoal
import md.utm.digitalmirror.data.entity.TimeCapsule

/** Ce s-a lucrat într-o perioadă: activități închise, zile cu activitate și minute. */
data class PeriodStats(val activities: Int, val activeDays: Int, val minutes: Int)

@Dao
interface CapsuleDao {

    @Query("SELECT * FROM time_capsule ORDER BY unlockDate, id")
    fun observeCapsules(): Flow<List<TimeCapsule>>

    @Query("SELECT * FROM time_capsule")
    suspend fun getCapsules(): List<TimeCapsule>

    @Query("SELECT * FROM capsule_goal")
    fun observeLinks(): Flow<List<CapsuleGoal>>

    @Query("SELECT * FROM capsule_goal WHERE capsuleId = :capsuleId")
    suspend fun getLinks(capsuleId: Long): List<CapsuleGoal>

    @Insert
    suspend fun insertCapsule(capsule: TimeCapsule): Long

    @Insert
    suspend fun insertLinks(links: List<CapsuleGoal>)

    @Delete
    suspend fun deleteCapsule(capsule: TimeCapsule)

    @Query("DELETE FROM capsule_goal WHERE capsuleId = :capsuleId")
    suspend fun deleteLinks(capsuleId: Long)

    // actualizări țintite, ca să nu suprascriem alte câmpuri
    @Query("UPDATE time_capsule SET openedAt = :openedAt WHERE id = :id")
    suspend fun markOpened(id: Long, openedAt: Long)

    @Query("UPDATE time_capsule SET readyNotified = 1 WHERE id = :id")
    suspend fun markReadyNotified(id: Long)

    @Query("UPDATE time_capsule SET lastNudgeDate = :date WHERE id = :id")
    suspend fun markNudged(id: Long, date: String)

    @Query(
        """
        SELECT COUNT(*) AS activities, COUNT(DISTINCT date) AS activeDays,
               COALESCE(SUM(actualMinutes), 0) AS minutes
        FROM activity
        WHERE state IN ('DONE', 'PARTIAL') AND actualMinutes IS NOT NULL AND actualMinutes > 0
          AND date >= :from AND date <= :to
        """
    )
    suspend fun periodStats(from: String, to: String): PeriodStats
}