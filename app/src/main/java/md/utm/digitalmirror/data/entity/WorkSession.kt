package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

@Entity(
    tableName = "work_session",
    foreignKeys = [ForeignKey(
        entity = Activity::class,
        parentColumns = ["id"],
        childColumns = ["activityId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("activityId"), Index(value = ["uid"], unique = true)]
)
data class WorkSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val startedAt: Long,
    val endedAt: Long?,                  // null cat timp cronometrul merge
    // v23 · sincronizarea între dispozitive (completate de triggerele din SyncSchema, nu de cod)
    val uid: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,      // updatedAt la ultima potrivire cu serverul; altfel e de trimis
    @ColumnInfo(defaultValue = "0") val syncVersion: Long = 0
)