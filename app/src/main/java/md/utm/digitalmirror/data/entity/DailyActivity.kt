package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_activity")
data class DailyActivity(
    @PrimaryKey val date: String,
    val steps: Int?,
    val activeMinutes: Int?,
    val collectedAt: Long = System.currentTimeMillis(),
    // v3 · somnul din noaptea dinaintea acestei zile
    val sleepMinutes: Int? = null,
    val bedtimeMinute: Int? = null,     // ora de culcare, în minute de la miezul nopții
    val wakeMinute: Int? = null,        // ora de trezire
    // v4 · de unde vine somnul: HEALTH (Health Connect) sau MANUAL (introdus de utilizator)
    val sleepSource: String? = null
)