package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_session",
    foreignKeys = [ForeignKey(
        entity = Activity::class,
        parentColumns = ["id"],
        childColumns = ["activityId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("activityId")]
)
data class WorkSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long?                   // null cat timp cronometrul merge
)