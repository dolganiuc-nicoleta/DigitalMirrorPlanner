package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Excepțiile unei zile față de scheletul obișnuit: „vineri mă trezesc la 10:00”, „joi sar prânzul”.
 * Restul zilei urmează regulile obișnuite (ritmul de somn, ferestrele meselor).
 */
@Entity(tableName = "day_override")
data class DayOverride(
    @PrimaryKey val date: String,            // yyyy-MM-dd
    val wakeMinute: Int? = null,             // ora de trezire aleasă pentru ziua asta; null = cea obișnuită
    val skippedMeals: String = ""            // mesele sărite, separate prin virgulă (BREAKFAST, LUNCH, DINNER)
) {
    val skipped: Set<String> get() = skippedMeals.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val isEmpty: Boolean get() = wakeMinute == null && skipped.isEmpty()
}
