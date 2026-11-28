package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un bloc fix care se repetă: cursuri, masă, somn, ședință săptămânală.
 * Zilele se păstrează într-un număr cu un bit pe zi (luni = 1, marți = 2, … duminică = 64),
 * iar drumul dus și întors se adaugă la interval.
 */
@Entity(tableName = "availability_rule", indices = [Index(value = ["uid"], unique = true)])
data class AvailabilityRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val daysMask: Int,
    val startMinute: Int,            // minute de la miezul nopții
    val endMinute: Int,
    val travelBeforeMinutes: Int,
    val travelAfterMinutes: Int,
    val placeId: Long? = null,       // v15 · unde se ține blocul (din „Locuri”), pentru calculul drumului
    // v23 · sincronizarea între dispozitive (completate de triggerele din SyncSchema, nu de cod)
    val uid: String? = null,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val syncedAt: Long = 0,      // updatedAt la ultima potrivire cu serverul; altfel e de trimis
    @ColumnInfo(defaultValue = "0") val syncVersion: Long = 0
)