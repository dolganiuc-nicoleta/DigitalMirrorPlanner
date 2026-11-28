package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/**
 * O muncă de făcut într-o săptămână („Lucru la licență, 6 ore, în sesiuni de 90 min, până vineri”).
 * Aplicația o împarte în sesiuni pe zile; sesiunile confirmate devin activități legate de ea (Activity.weekTaskId).
 */
@Entity(tableName = "week_task", indices = [Index("weekStart"), Index(value = ["uid"], unique = true)])
data class WeekTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long?,
    val weekStart: String,            // yyyy-MM-dd, luni
    val totalMinutes: Int,
    val sessionMinutes: Int,
    val deadline: String?,            // yyyy-MM-dd; null = fără termen
    val pinnedDate: String?,          // yyyy-MM-dd; null = se distribuie liber
    val createdAt: Long = System.currentTimeMillis(),
    // v23 · sincronizarea între dispozitive (completate de triggerele din SyncSchema, nu de cod)
    val uid: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,      // updatedAt la ultima potrivire cu serverul; altfel e de trimis
    @ColumnInfo(defaultValue = "0") val syncVersion: Long = 0
)