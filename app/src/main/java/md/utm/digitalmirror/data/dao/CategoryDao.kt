package md.utm.digitalmirror.data.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Category

/** Câte activități are o categorie (pentru „Categoriile mele”). */
data class CategoryUsage(val categoryId: Long, val count: Int)

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: Category): Long

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    /** Cele mai folosite întâi: în formulare apar sus categoriile pe care le alegi de obicei. */
    @Query(
        "SELECT c.* FROM category c LEFT JOIN activity a ON a.categoryId = c.id " +
                "GROUP BY c.id ORDER BY COUNT(a.id) DESC, c.name COLLATE NOCASE"
    )
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT categoryId, COUNT(*) AS count FROM activity WHERE categoryId IS NOT NULL GROUP BY categoryId")
    fun observeUsage(): Flow<List<CategoryUsage>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun getById(id: Long): Category?

    @Query("SELECT * FROM category WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) LIMIT 1")
    suspend fun findByName(name: String): Category?

    // ---- unirea a două categorii: tot ce ținea de [from] trece la [into], apoi [from] dispare ----

    @Query("UPDATE activity SET categoryId = :into WHERE categoryId = :from")
    suspend fun moveActivities(from: Long, into: Long)

    @Query("UPDATE week_task SET categoryId = :into WHERE categoryId = :from")
    suspend fun moveWeekTasks(from: Long, into: Long)

    @Query("UPDATE capsule_bet SET categoryId = :into WHERE categoryId = :from")
    suspend fun moveCapsuleBets(from: Long, into: Long)

    // un obiectiv legat de ambele categorii rămâne legat o singură dată
    @Query("INSERT OR IGNORE INTO goal_category (goalId, categoryId) SELECT goalId, :into FROM goal_category WHERE categoryId = :from")
    suspend fun copyGoalLinks(from: Long, into: Long)

    @Query("DELETE FROM goal_category WHERE categoryId = :from")
    suspend fun deleteGoalLinks(from: Long)

    @Query("DELETE FROM category WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Transaction
    suspend fun merge(from: Long, into: Long) {
        if (from == into) return
        moveActivities(from, into)
        moveWeekTasks(from, into)
        moveCapsuleBets(from, into)
        copyGoalLinks(from, into)
        deleteGoalLinks(from)
        deleteById(from)
    }
}
