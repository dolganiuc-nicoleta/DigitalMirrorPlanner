package md.utm.digitalmirror.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import md.utm.digitalmirror.data.entity.Goal
import md.utm.digitalmirror.data.entity.GoalCategory
import md.utm.digitalmirror.data.entity.GoalStep

/** O activitate închisă cu categorie, din care se calculează progresul obiectivelor. */
data class ClosedActivity(val date: String, val categoryId: Long, val minutes: Int)

@Dao
interface GoalDao {

    @Query("SELECT * FROM goal ORDER BY createdAt DESC")
    fun observeGoals(): Flow<List<Goal>>

    @Insert
    suspend fun insertGoal(goal: Goal): Long

    @Update
    suspend fun updateGoal(goal: Goal)

    @Delete
    suspend fun deleteGoal(goal: Goal)

    // categoriile legate
    @Query("SELECT * FROM goal_category")
    fun observeLinks(): Flow<List<GoalCategory>>

    @Insert
    suspend fun insertLinks(links: List<GoalCategory>)

    @Query("DELETE FROM goal_category WHERE goalId = :goalId")
    suspend fun deleteLinks(goalId: Long)

    // etapele
    @Query("SELECT * FROM goal_step ORDER BY goalId, position, id")
    fun observeSteps(): Flow<List<GoalStep>>

    @Insert
    suspend fun insertStep(step: GoalStep)

    @Update
    suspend fun updateStep(step: GoalStep)

    @Delete
    suspend fun deleteStep(step: GoalStep)

    @Query("DELETE FROM goal_step WHERE goalId = :goalId")
    suspend fun deleteSteps(goalId: Long)

    // activitățile finalizate sau parțiale cu durată reală
    @Query(
        """
        SELECT date, categoryId, actualMinutes AS minutes FROM activity
        WHERE state IN ('DONE', 'PARTIAL') AND actualMinutes IS NOT NULL AND actualMinutes > 0
          AND categoryId IS NOT NULL
        """
    )
    fun observeClosed(): Flow<List<ClosedActivity>>
}