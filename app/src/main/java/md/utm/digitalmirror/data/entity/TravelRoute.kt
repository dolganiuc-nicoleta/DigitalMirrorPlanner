package md.utm.digitalmirror.data.entity

import androidx.room.Entity

/**
 * Rezultatul serviciului de rute pentru o pereche de locuri, păstrat pe telefon.
 * Așa același drum nu mai consumă din cota zilnică de cereri și merge și fără internet.
 * Se șterge când locul se mută sau se șterge. Cheia e profilul (nu modul): pe jos și transportul public au același traseu
 * (transportul public folosește traseul pe jos, ca aproximare a traseului autobuzului).
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
    val fetchedAt: Long = System.currentTimeMillis(),
    val geometry: String? = null     // v21 · traseul, pentru hartă: „lat,lon;lat,lon;…” (vezi RouteGeometry)
)