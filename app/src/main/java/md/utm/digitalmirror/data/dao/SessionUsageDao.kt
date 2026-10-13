package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import md.utm.digitalmirror.data.entity.SessionUsage

@Dao
interface SessionUsageDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(usage: SessionUsage)

    @Query("SELECT startedAt FROM session_usage")
    suspend fun collectedStarts(): List<Long>

    @Query("SELECT * FROM session_usage WHERE activityId = :activityId ORDER BY startedAt")
    suspend fun getForActivity(activityId: Long): List<SessionUsage>

    @Query("SELECT * FROM session_usage")
    suspend fun getAll(): List<SessionUsage>
}