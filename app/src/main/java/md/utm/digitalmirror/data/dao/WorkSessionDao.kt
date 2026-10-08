package md.utm.digitalmirror.data.dao

import androidx.room.*
import md.utm.digitalmirror.data.entity.WorkSession
import kotlinx.coroutines.flow.Flow
@Dao
interface WorkSessionDao {

    @Insert
    suspend fun insert(session: WorkSession): Long

    @Update
    suspend fun update(session: WorkSession)

    @Query("SELECT * FROM work_session WHERE endedAt IS NULL LIMIT 1")
    suspend fun getRunning(): WorkSession?

    @Query("SELECT * FROM work_session WHERE activityId = :activityId ORDER BY startedAt")
    suspend fun getForActivity(activityId: Long): List<WorkSession>

    @Query("""
        SELECT COALESCE(SUM(endedAt - startedAt), 0)
        FROM work_session
        WHERE activityId = :activityId AND endedAt IS NOT NULL
    """)
    suspend fun totalMillisForActivity(activityId: Long): Long

    @Query("SELECT MIN(startedAt) FROM work_session WHERE activityId = :activityId")
    suspend fun firstStartFor(activityId: Long): Long?

    @Query("SELECT * FROM work_session WHERE endedAt IS NULL LIMIT 1")
    fun observeRunning(): Flow<WorkSession?>
}