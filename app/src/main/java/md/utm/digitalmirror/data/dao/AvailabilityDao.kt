package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.AvailabilityRule
import md.utm.digitalmirror.data.entity.DayOff

@Dao
interface AvailabilityDao {

    @Query("SELECT * FROM availability_rule ORDER BY startMinute, id")
    fun observeRules(): Flow<List<AvailabilityRule>>

    @Insert
    suspend fun insertRule(rule: AvailabilityRule): Long

    @Update
    suspend fun updateRule(rule: AvailabilityRule)

    @Delete
    suspend fun deleteRule(rule: AvailabilityRule)

    @Query("SELECT * FROM day_off ORDER BY date")
    fun observeDaysOff(): Flow<List<DayOff>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDayOff(dayOff: DayOff)

    @Query("DELETE FROM day_off WHERE date = :date")
    suspend fun deleteDayOff(date: String)
}