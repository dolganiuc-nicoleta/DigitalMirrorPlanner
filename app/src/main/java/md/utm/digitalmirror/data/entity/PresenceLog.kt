package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * O intrare detectată de telefon într-un loc, cu ieșirea ei.
 * exitAt = null înseamnă că utilizatorul e încă în loc (sau ieșirea nu a fost detectată).
 * Datele brute se curăță la afișare, vezi PresenceRules.
 */
@Entity(
    tableName = "presence_log",
    indices = [Index("placeId"), Index("enterAt")]
)
data class PresenceLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val placeId: Long,
    val enterAt: Long,          // milisecunde, ora telefonului
    val exitAt: Long? = null
)