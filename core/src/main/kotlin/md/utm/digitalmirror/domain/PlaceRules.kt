package md.utm.digitalmirror.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Regulile pentru „locuri” (Facultate, Sală, Birou): validare și distanțe.
 * Cod pur Kotlin, fără Android, ca să poată fi testat local.
 */
object PlaceRules {

    const val MIN_RADIUS = 50
    const val MAX_RADIUS = 500
    const val DEFAULT_RADIUS = 100
    const val MAX_NAME = 40

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Raza se ține între 50 și 500 m. Sub 50 m GPS-ul greșește prea des, peste 500 m locul nu mai e precis. */
    fun clampRadius(radius: Int): Int = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)

    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Ce lipsește ca să se poată salva locul, sau null dacă e gata. */
    fun problem(name: String, hasPoint: Boolean): String? = when {
        name.isBlank() -> "Dă un nume locului."
        name.trim().length > MAX_NAME -> "Numele poate avea cel mult $MAX_NAME de caractere."
        !hasPoint -> "Atinge harta sau caută o adresă ca să alegi locul."
        else -> null
    }

    /** Distanța în metri între două puncte (formula haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }

    /** Punctul (lat, lon) e în interiorul locului cu centrul și raza dată? */
    fun contains(centerLat: Double, centerLon: Double, radiusMeters: Int, lat: Double, lon: Double): Boolean =
        distanceMeters(centerLat, centerLon, lat, lon) <= radiusMeters

    /** „100 m”, „1,2 km”. */
    fun distanceLabel(meters: Double): String =
        if (meters < 1000) "${meters.toInt()} m"
        else String.format(java.util.Locale.forLanguageTag("ro"), "%.1f km", meters / 1000)
}