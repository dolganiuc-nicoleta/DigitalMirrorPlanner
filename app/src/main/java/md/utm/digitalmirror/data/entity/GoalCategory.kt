package md.utm.digitalmirror.data.entity

import androidx.room.Entity

/** Legătura dintre un obiectiv și o categorie de activități. */
@Entity(tableName = "goal_category", primaryKeys = ["goalId", "categoryId"])
data class GoalCategory(
    val goalId: Long,
    val categoryId: Long
)