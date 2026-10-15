package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un bloc fix care se repetă: cursuri, masă, somn, ședință săptămânală.
 * Zilele se păstrează într-un număr cu un bit pe zi (luni = 1, marți = 2, … duminică = 64),
 * iar drumul dus și întors se adaugă la interval.
 */
@Entity(tableName = "availability_rule")
data class AvailabilityRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val daysMask: Int,
    val startMinute: Int,            // minute de la miezul nopții
    val endMinute: Int,
    val travelBeforeMinutes: Int,
    val travelAfterMinutes: Int,
    val placeId: Long? = null        // v15 · unde se ține blocul (din „Locuri”), pentru calculul drumului
)