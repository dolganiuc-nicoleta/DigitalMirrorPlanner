package md.utm.digitalmirror.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Category

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("SELECT * FROM category ORDER BY name")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM category WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByName(name: String): Category?
}