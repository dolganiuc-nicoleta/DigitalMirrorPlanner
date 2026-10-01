package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import md.utm.digitalmirror.data.entity.AppUsage

@Dao
interface AppUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<AppUsage>)

    @Query("SELECT * FROM app_usage WHERE date = :date ORDER BY minutes DESC")
    suspend fun getByDate(date: String): List<AppUsage>
}