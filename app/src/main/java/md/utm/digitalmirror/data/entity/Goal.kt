package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un obiectiv cu termen opțional și țintă de timp, de activități sau ambele.
 * Progresul nu se salvează: se calculează din activitățile închise din categoriile legate de obiectiv.
 */
@Entity(tableName = "goal")
data class Goal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val deadline: String?,               // yyyy-MM-dd; null = fără termen
    val targetMinutes: Int?,
    val targetActivities: Int?,
    val startDate: String,               // de când se numără activitățile
    val state: String = STATE_ACTIVE,
    val createdAt: Long,
    val note: String = ""
) {
    companion object {
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_DONE = "DONE"
        const val STATE_ARCHIVED = "ARCHIVED"
    }
}