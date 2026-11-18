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

    @Query("SELECT * FROM daily_activity WHERE date = :date")
    fun observeByDate(date: String): Flow<DailyActivity?>

    @Query("SELECT * FROM daily_activity ORDER BY date DESC")
    fun observeAll(): Flow<List<DailyActivity>>

    /** Zilele dintre [from] și [to] inclusiv (somnul fiecăreia e din noaptea dinainte). */
    @Query("SELECT * FROM daily_activity WHERE date >= :from AND date <= :to ORDER BY date")
    suspend fun between(from: String, to: String): List<DailyActivity>
}