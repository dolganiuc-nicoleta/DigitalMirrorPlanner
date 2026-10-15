package md.utm.digitalmirror.data.geo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Căutare de adrese cu Nominatim (OpenStreetMap), fără cheie API.
 * Regulile serviciului: cerere doar când utilizatorul apasă „Caută” (nu la fiecare literă)
 * și un User-Agent care identifică aplicația.
 */
object PlaceSearch {

    data class Hit(val label: String, val latitude: Double, val longitude: Double)

    private const val USER_AGENT = "DigitalMirrorPlanner/1.0 (lucrare de licență, UTM)"

    suspend fun search(query: String): Result<List<Hit>> = withContext(Dispatchers.IO) {
        val text = query.trim()
        if (text.length < 3) return@withContext Result.success(emptyList())
        try {
            val url = URL(
                "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=5" +
                        "&accept-language=ro&q=" + URLEncoder.encode(text, "UTF-8")
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 8_000
                readTimeout = 8_000
            }
            try {
                if (connection.responseCode != 200) {
                    return@withContext Result.failure(IOException("HTTP ${connection.responseCode}"))
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(body)
                val hits = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    Hit(
                        label = item.optString("display_name"),
                        latitude = item.getString("lat").toDouble(),
                        longitude = item.getString("lon").toDouble()
                    )
                }
                Result.success(hits)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}