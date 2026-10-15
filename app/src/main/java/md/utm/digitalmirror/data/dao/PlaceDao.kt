package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Place

@Dao
interface PlaceDao {

    @Query("SELECT * FROM place ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT COUNT(*) FROM place")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM place ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<Place>

    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Query("DELETE FROM place WHERE id = :id")
    suspend fun delete(id: Long)
}