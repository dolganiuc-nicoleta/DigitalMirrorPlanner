package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** O etapă dintr-un obiectiv, bifată de utilizator. */
@Entity(tableName = "goal_step")
data class GoalStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val title: String,
    val done: Boolean,
    val position: Int
)