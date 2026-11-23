package md.utm.digitalmirror.domain

import kotlin.math.abs

/**
 * Traseul unui drum, ca listă de puncte (latitudine, longitudine), păstrat pe telefon ca text.
 * Serviciul de rute trimite sute de puncte; le rărim (fără să schimbăm forma vizibilă pe hartă)
 * și le scriem cu 5 zecimale (~1 m), ca textul să rămână mic.
 */
object RouteGeometry {

    data class Point(val lat: Double, val lon: Double)

    /** Sub această distanță (în grade, ~5 m) un punct nu mai schimbă forma desenată. */
    private const val MIN_STEP = 0.00005

    fun encode(points: List<Point>): String =
        simplify(points).joinToString(";") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) }

    fun decode(text: String?): List<Point> =
        text.orEmpty().split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            Point(lat, lon)
        }

    /** Păstrează primul și ultimul punct, iar dintre celelalte doar pe cele care se depărtează destul de ultimul păstrat. */
    fun simplify(points: List<Point>): List<Point> {
        if (points.size <= 2) return points
        val kept = mutableListOf(points.first())
        for (p in points.subList(1, points.size - 1)) {
            val last = kept.last()
            if (abs(p.lat - last.lat) >= MIN_STEP || abs(p.lon - last.lon) >= MIN_STEP) kept += p
        }
        kept += points.last()
        return kept
    }
}
