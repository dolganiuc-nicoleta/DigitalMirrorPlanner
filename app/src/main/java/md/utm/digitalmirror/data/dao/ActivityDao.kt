package md.utm.digitalmirror.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Activity

@Dao
interface ActivityDao {

    @Insert
    suspend fun insert(activity: Activity): Long

    @Update
    suspend fun update(activity: Activity)

    @Delete
    suspend fun delete(activity: Activity)

    @Query("SELECT * FROM activity WHERE id = :id")
    suspend fun getById(id: Long): Activity?

    @Query("SELECT * FROM activity WHERE date = :date ORDER BY plannedStartMinute IS NULL, plannedStartMinute")
    fun observeByDate(date: String): Flow<List<Activity>>

    @Query("SELECT * FROM activity WHERE date BETWEEN :from AND :to ORDER BY date, plannedStartMinute")
    suspend fun getRange(from: String, to: String): List<Activity>

    @Query("""
        SELECT COUNT(*) FROM activity
        WHERE categoryId = :categoryId AND actualMinutes IS NOT NULL
    """)
    suspend fun observationCount(categoryId: Long): Int

    @Query("""
        SELECT actualMinutes FROM activity
        WHERE categoryId = :categoryId AND actualMinutes IS NOT NULL
        ORDER BY date DESC
        LIMIT :limit
    """)
    suspend fun recentDurations(categoryId: Long, limit: Int = 30): List<Int>

    @Query("""
        SELECT COUNT(*) FROM activity
        WHERE date BETWEEN :from AND :to AND state = 'DONE'
    """)
    suspend fun completedCount(from: String, to: String): Int

    @Query("""
        SELECT COUNT(*) FROM activity
        WHERE date BETWEEN :from AND :to AND state != 'PLANNED'
    """)
    suspend fun attemptedCount(from: String, to: String): Int

    @Query("SELECT * FROM activity WHERE date BETWEEN :from AND :to")
    fun observeRange(from: String, to: String): Flow<List<Activity>>

    @Query("""
    SELECT * FROM activity
    WHERE categoryId = :categoryId
      AND state = 'DONE'
      AND userEstimatedMinutes > 0
      AND actualMinutes IS NOT NULL
    ORDER BY date DESC
    LIMIT :limit
""")
    suspend fun getDurationHistory(categoryId: Long, limit: Int): List<Activity>

    @Query("""
    SELECT * FROM activity
    WHERE categoryId = :categoryId AND state IN ('DONE', 'PARTIAL', 'ABANDONED')
    ORDER BY date DESC
    LIMIT :limit
""")
    suspend fun getClosedByCategory(categoryId: Long, limit: Int): List<Activity>

    @Query("UPDATE activity SET categoryId = NULL WHERE categoryId = :categoryId")
    suspend fun clearCategory(categoryId: Long)
}