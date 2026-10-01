package md.utm.digitalmirror.data.entity

import androidx.room.Entity

@Entity(tableName = "app_usage", primaryKeys = ["date", "packageName"])
data class AppUsage(
    val date: String,
    val packageName: String,
    val minutes: Int,
    val collectedAt: Long = System.currentTimeMillis()
)