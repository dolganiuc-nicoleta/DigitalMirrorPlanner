package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "daily_activity")
data class DailyActivity(
    @PrimaryKey val date: String,
    val steps: Int?,
    val activeMinutes: Int?,
    val collectedAt: Long = System.currentTimeMillis()
)