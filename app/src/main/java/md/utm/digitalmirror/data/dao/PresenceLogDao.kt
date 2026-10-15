package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.PresenceLog

/** Rând pentru lista „Prezențe recente” (prezența împreună cu numele locului). */
data class RecentVisit(
    val placeName: String,
    val enterAt: Long,
    val exitAt: Long?
)

@Dao
interface PresenceLogDao {

    @Insert
    suspend fun insert(log: PresenceLog): Long

    /** Intrarea încă deschisă (fără ieșire) pentru un loc, dacă există. */
    @Query("SELECT * FROM presence_log WHERE placeId = :placeId AND exitAt IS NULL ORDER BY enterAt DESC LIMIT 1")
    suspend fun openFor(placeId: Long): PresenceLog?

    @Query("UPDATE presence_log SET exitAt = :exitAt WHERE id = :id")
    suspend fun close(id: Long, exitAt: Long)

    /** Toate prezențele care ating intervalul [from, to), inclusiv cele încă deschise. */
    @Query("SELECT * FROM presence_log WHERE enterAt < :to AND (exitAt IS NULL OR exitAt > :from) ORDER BY enterAt")
    suspend fun overlapping(from: Long, to: Long): List<PresenceLog>

    @Query(
        "SELECT p.name AS placeName, l.enterAt AS enterAt, l.exitAt AS exitAt " +
                "FROM presence_log l JOIN place p ON p.id = l.placeId ORDER BY l.enterAt DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<RecentVisit>>

    @Query("DELETE FROM presence_log WHERE placeId = :placeId")
    suspend fun deleteForPlace(placeId: Long)
}