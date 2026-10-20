package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.PrepEvent
import md.utm.digitalmirror.data.entity.PrepStep

@Dao
interface PrepDao {

    @Query("SELECT * FROM prep_event ORDER BY eventDate, id")
    fun observeEvents(): Flow<List<PrepEvent>>

    @Query("SELECT * FROM prep_event WHERE id = :id")
    fun observeEvent(id: Long): Flow<PrepEvent?>

    @Query("SELECT * FROM prep_step ORDER BY eventId, position")
    fun observeAllSteps(): Flow<List<PrepStep>>

    @Query("SELECT * FROM prep_step WHERE eventId = :eventId ORDER BY position")
    fun observeSteps(eventId: Long): Flow<List<PrepStep>>

    @Query("SELECT * FROM prep_step WHERE eventId = :eventId ORDER BY position")
    suspend fun getSteps(eventId: Long): List<PrepStep>

    @Insert
    suspend fun insertEvent(event: PrepEvent): Long

    @Insert
    suspend fun insertStep(step: PrepStep): Long

    @Update
    suspend fun updateStep(step: PrepStep)

    @Query("DELETE FROM prep_step WHERE id = :id")
    suspend fun deleteStep(id: Long)

    @Query("DELETE FROM prep_step WHERE eventId = :eventId")
    suspend fun deleteSteps(eventId: Long)

    @Query("DELETE FROM prep_event WHERE id = :id")
    suspend fun deleteEvent(id: Long)

    // --- sesiunile (activitățile) legate de pașii unui eveniment ---

    @Query("SELECT * FROM activity WHERE prepStepId IN (SELECT id FROM prep_step WHERE eventId = :eventId) ORDER BY date, plannedStartMinute")
    fun observeSessions(eventId: Long): Flow<List<Activity>>

    @Query("SELECT * FROM activity WHERE prepStepId IS NOT NULL")
    fun observeAllSessions(): Flow<List<Activity>>

    /** Sesiunile încă neîncepute, de la o dată încolo: acestea se pot muta la replanificare. */
    @Query("SELECT * FROM activity WHERE prepStepId IN (SELECT id FROM prep_step WHERE eventId = :eventId) AND state = 'PLANNED' AND date >= :from")
    suspend fun movableSessions(eventId: Long, from: String): List<Activity>

    @Query("DELETE FROM activity WHERE prepStepId IN (SELECT id FROM prep_step WHERE eventId = :eventId) AND state = 'PLANNED' AND date >= :from")
    suspend fun deleteMovable(eventId: Long, from: String)

    @Query("SELECT * FROM activity WHERE prepStepId IN (SELECT id FROM prep_step WHERE eventId = :eventId)")
    suspend fun getSessions(eventId: Long): List<Activity>

    @Query("UPDATE activity SET prepStepId = NULL WHERE prepStepId IN (SELECT id FROM prep_step WHERE eventId = :eventId)")
    suspend fun unlinkSessions(eventId: Long)
}
