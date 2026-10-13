package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cât timp a folosit utilizatorul telefonul în timpul unei sesiuni de cronometru.
 * Cheia e momentul de start al sesiunii, care e unic.
 *
 * Datele din Android dispar după câteva zile, deci le salvăm noi imediat ce sesiunea s-a terminat.
 */
@Entity(tableName = "session_usage")
data class SessionUsage(
    @PrimaryKey val startedAt: Long,
    val activityId: Long,
    val phoneMillis: Long,        // total, fără aplicația noastră
    val topApps: String,          // JSON: [{"p":"com.instagram.android","ms":480000}, …], primele 5
    val collectedAt: Long
)