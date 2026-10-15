package md.utm.digitalmirror.data.entity

import androidx.room.Entity

/**
 * Rezultatul serviciului de rute pentru o pereche de locuri, păstrat pe telefon.
 * Așa același drum nu mai consumă din cota zilnică de cereri și merge și fără internet.
 * Se șterge când locul se mută sau se șterge. Cheia e profilul (nu modul): mașina și transportul public
 * folosesc aceeași distanță rutieră.
 */
@Entity(
    tableName = "travel_route",
    primaryKeys = ["fromPlaceId", "toPlaceId", "profile"]
)
data class TravelRoute(
    val fromPlaceId: Long,
    val toPlaceId: Long,
    val profile: String,            // foot-walking, driving-car, cycling-regular
    val distanceMeters: Double,
    val durationSeconds: Double,
    val fetchedAt: Long = System.currentTimeMillis()
)