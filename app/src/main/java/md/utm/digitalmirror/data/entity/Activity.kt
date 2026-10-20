package md.utm.digitalmirror.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "activity",
    foreignKeys = [ForeignKey(
        entity = Category::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
        onDelete = ForeignKey.SET_NULL
    )],
    indices = [Index("categoryId"), Index("date"), Index("titleKey")]
)
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long?,
    val date: String,                    // "2026-10-01"
    val plannedStartMinute: Int?,        // minute de la miezul nopții, null = nefixat

    // --- durate ---
    val userGuessMinutes: Int? = null,   // v4 · cât a crezut utilizatorul ÎNAINTE să vadă propunerea (M0)
    val estimatedMinutes: Int?,          // ce a propus sistemul (estimarea centrală)
    val estimatedLowMinutes: Int? = null,   // v4 · limita de jos a intervalului de 80%
    val estimatedHighMinutes: Int? = null,  // v4 · limita de sus a intervalului de 80%
    val userEstimatedMinutes: Int?,      // durata planificată finală (ce a rămas în formular)
    val actualMinutes: Int?,             // durata reală
    val actualSource: String? = null,    // v4 · de unde vine durata reală: TIMER, MANUAL, AS_PLANNED, CALENDAR, HEALTH

    val state: String = "PLANNED",       // PLANNED, IN_PROGRESS, DONE, PARTIAL, POSTPONED, ABANDONED
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0")
    val postponedCount: Int = 0,
    val titleKey: String? = null,        // v4 · titlul normalizat, pentru a găsi activitățile „la fel”
    @ColumnInfo(defaultValue = "''")
    val tags: String = "",               // v9 · etichete separate prin virgulă, fără #
    val weekTaskId: Long? = null,        // v10 · sarcina din planul săptămânii din care vine sesiunea
    val placeId: Long? = null,           // v12 · locul unde se desfășoară (din „Locuri”), null = nespecificat
    val prepStepId: Long? = null         // v16 · pasul din pregătirea unui eveniment din care vine sesiunea
) {
    companion object {
        // sursele durate reale; estimatorul învață doar din cele sigure
        const val SOURCE_TIMER = "TIMER"
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_AS_PLANNED = "AS_PLANNED"
        const val SOURCE_CALENDAR = "CALENDAR"
        const val SOURCE_HEALTH = "HEALTH"
        const val SOURCE_LOCATION = "LOCATION"   // cât timp a fost telefonul la locul activității
    }
}
