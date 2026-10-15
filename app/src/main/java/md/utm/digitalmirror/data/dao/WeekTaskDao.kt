package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.WeekTask

@Dao
interface WeekTaskDao {

    @Query("SELECT * FROM week_task WHERE weekStart = :weekStart ORDER BY id")
    fun observeForWeek(weekStart: String): Flow<List<WeekTask>>

    @Insert
    suspend fun insert(task: WeekTask): Long

    @Update
    suspend fun update(task: WeekTask)

    @Query("DELETE FROM week_task WHERE id = :id")
    suspend fun delete(id: Long)

    // --- sesiunile (activitățile) legate de sarcini ---

    /** Sesiunile încă neîncepute, de la o dată încolo: acestea se pot muta la redistribuire. */
    @Query("SELECT * FROM activity WHERE weekTaskId = :taskId AND state = 'PLANNED' AND date >= :from")
    suspend fun movableSessions(taskId: Long, from: String): List<Activity>

    @Query("DELETE FROM activity WHERE weekTaskId = :taskId AND state = 'PLANNED' AND date >= :from")
    suspend fun deleteMovable(taskId: Long, from: String)

    @Query("SELECT * FROM activity WHERE weekTaskId = :taskId AND state = 'PLANNED' AND date = :date")
    suspend fun movableOn(taskId: Long, date: String): List<Activity>

    @Query("DELETE FROM activity WHERE weekTaskId = :taskId AND state = 'PLANNED' AND date = :date")
    suspend fun deleteMovableOn(taskId: Long, date: String)

    @Query("UPDATE activity SET weekTaskId = NULL WHERE weekTaskId = :taskId")
    suspend fun unlink(taskId: Long)
}