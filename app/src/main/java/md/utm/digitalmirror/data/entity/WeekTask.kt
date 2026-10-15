package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * O muncă de făcut într-o săptămână („Lucru la licență, 6 ore, în sesiuni de 90 min, până vineri”).
 * Aplicația o împarte în sesiuni pe zile; sesiunile confirmate devin activități legate de ea (Activity.weekTaskId).
 */
@Entity(tableName = "week_task", indices = [Index("weekStart")])
data class WeekTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long?,
    val weekStart: String,            // yyyy-MM-dd, luni
    val totalMinutes: Int,
    val sessionMinutes: Int,
    val deadline: String?,            // yyyy-MM-dd; null = fără termen
    val pinnedDate: String?,          // yyyy-MM-dd; null = se distribuie liber
    val createdAt: Long = System.currentTimeMillis()
)