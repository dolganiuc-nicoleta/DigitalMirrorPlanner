package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/** O zi marcată ca liberă: blocurile fixe nu se aplică, iar ziua nu intră în media zilelor tipice. */
@Entity(tableName = "day_off", indices = [Index(value = ["uid"], unique = true)])
data class DayOff(
    @PrimaryKey val date: String,   // yyyy-MM-dd
    // v23 · sincronizarea între dispozitive (completate de triggerele din SyncSchema, nu de cod)
    val uid: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,      // updatedAt la ultima potrivire cu serverul; altfel e de trimis
    @ColumnInfo(defaultValue = "0") val syncVersion: Long = 0
)