package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.DailyActivity

@Dao
interface DailyActivityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(activity: DailyActivity)

    @Query("SELECT * FROM daily_activity WHERE date = :date")
    suspend fun getByDate(date: String): DailyActivity?

    @Query("SELECT * FROM daily_activity ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyActivity>>
}