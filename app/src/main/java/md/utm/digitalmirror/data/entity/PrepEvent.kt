package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un eveniment pentru care utilizatorul se pregătește (susținere, examen, interviu…).
 * Pașii pregătirii sunt în [PrepStep]; sesiunile din plan sunt activități legate de pași (Activity.prepStepId).
 */
@Entity(tableName = "prep_event")
data class PrepEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val eventDate: String,            // yyyy-MM-dd
    val createdAt: Long = System.currentTimeMillis()
)

/** Un pas din pregătire, cu timpul lui planificat în minute. */
@Entity(tableName = "prep_step", indices = [Index("eventId")])
data class PrepStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventId: Long,
    val title: String,
    val minutes: Int,
    val position: Int
)
