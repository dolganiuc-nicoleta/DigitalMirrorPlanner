package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reflection")
data class Reflection(
    @PrimaryKey val date: String,      // "2026-09-08"
    val moodScore: Int,                 // 1..5
    val workedOn: String,               // la ce a lucrat
    val blockers: String,               // ce l-a blocat
    val note: String,                   // text liber
    val createdAt: Long = System.currentTimeMillis()
)