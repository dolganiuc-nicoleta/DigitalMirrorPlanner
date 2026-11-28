package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "category", indices = [Index(value = ["uid"], unique = true)])
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorHex: String = "#378ADD",
    val createdAt: Long = System.currentTimeMillis(),
    val icon: String? = null,            // v22 · numele iconiței (vezi CategoryIcons); null = ghicită din nume
    // v23 · sincronizarea între dispozitive (completate de triggerele din SyncSchema, nu de cod)
    val uid: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,      // updatedAt la ultima potrivire cu serverul; altfel e de trimis
    @ColumnInfo(defaultValue = "0") val syncVersion: Long = 0
)