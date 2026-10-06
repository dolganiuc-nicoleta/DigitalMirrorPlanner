package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
@Entity(
    tableName = "activity",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("categoryId"), Index("date")]
)
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long?,
    val date: String,                    // "2026-10-01"
    val plannedStartMinute: Int?,        // minute de la miezul nopții, null = nefixat
    val estimatedMinutes: Int?,          // ce a propus sistemul
    val userEstimatedMinutes: Int?,      // ce a pus utilizatorul, dacă a modificat
    val actualMinutes: Int?,             // completat la închiderea zilei
    val state: String = "PLANNED",       // PLANNED, IN_PROGRESS, DONE, PARTIAL, POSTPONED, ABANDONED
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
val postponedCount: Int = 0
)