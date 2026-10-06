package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Reflection

@Dao
interface ReflectionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reflection: Reflection)

    @Query("SELECT * FROM reflection WHERE date = :date")
    suspend fun getByDate(date: String): Reflection?

    @Query("SELECT * FROM reflection ORDER BY date DESC")
    fun observeAll(): Flow<List<Reflection>>

    @Query("SELECT * FROM reflection WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun getRange(from: String, to: String): List<Reflection>

    @Query("SELECT * FROM reflection WHERE date = :date")
    fun observeByDate(date: String): Flow<Reflection?>
}