package md.utm.digitalmirror.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un loc din viața utilizatorului (Facultate, Sală, Birou), ales pe hartă.
 * Rămâne doar pe telefon. Raza e zona în care contează că „ești acolo”.
 */
@Entity(tableName = "place")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Int,
    val address: String,                 // textul adresei găsite la căutare, poate fi gol
    val createdAt: Long = System.currentTimeMillis()
)