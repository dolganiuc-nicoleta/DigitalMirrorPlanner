package md.utm.digitalmirror.data.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import md.utm.digitalmirror.BuildConfig
import md.utm.digitalmirror.data.entity.Place
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cere distanța și durata unui drum de la OpenRouteService.
 * Singurul loc din aplicație care vorbește cu serviciul de rute. Se trimit doar coordonatele celor două locuri.
 */
object RouteService {

    // adresa veche (api.openrouteservice.org) se închide în noiembrie 2026
    private const val BASE_URL = "https://api.heigit.org/openrouteservice/v2/directions"

    /** [points] = traseul (lat, lon), pentru hartă; gol dacă serviciul nu l-a trimis. */
    data class Raw(
        val distanceMeters: Double,
        val durationSeconds: Double,
        val points: List<md.utm.digitalmirror.domain.RouteGeometry.Point> = emptyList()
    )

    class RouteException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun fetch(profile: String, from: Place, to: Place): Result<Raw> = withContext(Dispatchers.IO) {
        val key = BuildConfig.ORS_API_KEY
        if (key.isBlank()) {
            return@withContext fail("Lipsește cheia OpenRouteService. Adaug-o în local.properties.")
        }
        try {
            // ordinea în OpenRouteService e longitudine, latitudine
            val url = URL(
                "$BASE_URL/$profile?start=${from.longitude},${from.latitude}&end=${to.longitude},${to.latitude}"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                // cheia merge în antet, nu în adresă, ca să nu apară în jurnale
                setRequestProperty("Authorization", key)
                setRequestProperty("Accept", "application/geo+json, application/json")
                setRequestProperty("User-Agent", "DigitalMirrorPlanner/1.0 (lucrare de licență, UTM)")
                connectTimeout = 8_000
                readTimeout = 10_000
            }
            try {
                when (val code = connection.responseCode) {
                    200 -> {
                        val body = connection.inputStream.bufferedReader().use { it.readText() }
                        val feature = JSONObject(body).getJSONArray("features").getJSONObject(0)
                        val summary = feature.getJSONObject("properties").getJSONObject("summary")
                        // traseul: o linie de puncte [longitudine, latitudine]
                        val coords = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                        val points = (0 until (coords?.length() ?: 0)).mapNotNull { i ->
                            coords!!.optJSONArray(i)?.let { md.utm.digitalmirror.domain.RouteGeometry.Point(it.getDouble(1), it.getDouble(0)) }
                        }
                        Result.success(Raw(summary.getDouble("distance"), summary.getDouble("duration"), points))
                    }
                    401, 403 -> fail("Cheia OpenRouteService nu e acceptată. Verifică local.properties.")
                    404 -> fail("Nu am găsit un drum între cele două locuri pentru acest mod de deplasare.")
                    429 -> fail("Prea multe cereri la serviciul de rute. Încearcă mai târziu.")
                    else -> fail("Serviciul de rute nu a răspuns (cod $code).")
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: IOException) {
            fail("Fără conexiune la internet.", e)
        } catch (e: Exception) {
            fail("Ceva nu a mers la calcularea drumului.", e)
        }
    }

    private fun fail(message: String, cause: Throwable? = null): Result<Raw> =
        Result.failure(RouteException(message, cause))
}