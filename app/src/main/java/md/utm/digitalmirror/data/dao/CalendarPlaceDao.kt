package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.CalendarPlace

@Dao
interface CalendarPlaceDao {

    @Query("SELECT * FROM calendar_place")
    fun observeAll(): Flow<List<CalendarPlace>>

    @Query("SELECT * FROM calendar_place")
    suspend fun getAll(): List<CalendarPlace>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: CalendarPlace)

    @Query("DELETE FROM calendar_place WHERE titleKey = :titleKey")
    suspend fun delete(titleKey: String)

    /** La ștergerea unui loc, evenimentele legate de el rămân fără loc. */
    @Query("DELETE FROM calendar_place WHERE placeId = :placeId")
    suspend fun deleteForPlace(placeId: Long)
}
