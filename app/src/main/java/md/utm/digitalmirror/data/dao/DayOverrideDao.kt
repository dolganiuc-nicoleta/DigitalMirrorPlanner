package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.DayOverride

@Dao
interface DayOverrideDao {

    @Query("SELECT * FROM day_override WHERE date = :date")
    suspend fun get(date: String): DayOverride?

    @Query("SELECT * FROM day_override WHERE date >= :from AND date <= :to")
    fun observeRange(from: String, to: String): Flow<List<DayOverride>>

    @Query("SELECT * FROM day_override")
    fun observeAll(): Flow<List<DayOverride>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: DayOverride)

    @Query("DELETE FROM day_override WHERE date = :date")
    suspend fun delete(date: String)
}
