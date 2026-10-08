package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_activity")
data class DailyActivity(
    @PrimaryKey val date: String,
    val steps: Int?,
    val activeMinutes: Int?,
    val collectedAt: Long = System.currentTimeMillis(),
    // adăugate în versiunea 3 a bazei de date (Health Connect)
    val sleepMinutes: Int? = null,      // cât a dormit în noaptea dinaintea acestei zile
    val bedtimeMinute: Int? = null,     // ora de culcare, în minute de la miezul nopții
    val wakeMinute: Int? = null         // ora de trezire
)