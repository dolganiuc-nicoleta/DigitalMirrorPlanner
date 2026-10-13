package md.utm.digitalmirror.data.entity

import androidx.room.Entity

/** Un obiectiv legat de o capsulă, cu progresul lui din ziua sigilării (pentru comparația de la deschidere). */
@Entity(tableName = "capsule_goal", primaryKeys = ["capsuleId", "goalId"])
data class CapsuleGoal(
    val capsuleId: Long,
    val goalId: Long,
    val percentAtStart: Int
)