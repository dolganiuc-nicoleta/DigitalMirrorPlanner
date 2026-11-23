package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un pariu al capsulei: ce ai prezis la sigilare („40h la licență”, „7h 30 de somn”, „80% terminat”).
 * La deschidere îl comparăm cu ce a fost. [kind] e unul din CapsuleBets.Kind; [predicted] e în minute
 * (ore, somn) sau procente (terminat).
 */
@Entity(tableName = "capsule_bet", indices = [Index("capsuleId")])
data class CapsuleBet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capsuleId: Long,
    val kind: String,
    val predicted: Int,
    val categoryId: Long? = null
)
