package md.utm.digitalmirror.data.geo

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.tasks.await
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.prefs.PresencePrefs

/**
 * Înregistrează locurile ca zone de urmărit (geofencing, Google Play Services).
 * Telefonul anunță aplicația când utilizatorul intră sau iese dintr-o zonă, chiar dacă aplicația e închisă.
 * Zonele se pierd la repornirea telefonului, deci se refac la pornire (GeofenceBootReceiver) și la deschiderea aplicației.
 */
object GeofenceManager {

    // Android permite cel mult 100 de zone per aplicație
    private const val MAX_GEOFENCES = 100

    fun hasForegroundPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** „Permite tot timpul”: fără ea nu primim intrările și ieșirile cu aplicația închisă. */
    fun hasBackgroundPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) hasForegroundPermission(context)
        else hasForegroundPermission(context) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    /** Detectarea funcționează doar dacă e pornită și are permisiunile. */
    fun isActive(context: Context): Boolean =
        PresencePrefs.isEnabled(context) && hasBackgroundPermission(context)

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            0,
            Intent(context.applicationContext, GeofenceReceiver::class.java),
            // pentru geofencing sistemul cere PendingIntent mutabil
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    /**
     * Aliniază zonele înregistrate cu locurile din baza de date.
     * Se apelează după orice schimbare a locurilor, la pornirea aplicației și după repornirea telefonului.
     * @return true dacă zonele sunt active, false dacă detectarea e oprită sau lipsesc permisiuni
     */
    @SuppressLint("MissingPermission")
    suspend fun sync(context: Context): Boolean {
        val app = context.applicationContext
        val client = LocationServices.getGeofencingClient(app)
        val intent = pendingIntent(app)

        // scoatem mereu zonele vechi, apoi le punem pe cele actuale
        try {
            client.removeGeofences(intent).await()
        } catch (_: Exception) {
            // nu erau zone înregistrate, sau Play Services nu răspunde; mergem mai departe
        }

        if (!isActive(app)) return false
        val places = AppDatabase.get(app).placeDao().getAll().take(MAX_GEOFENCES)
        if (places.isEmpty()) return false

        val geofences = places.map { place ->
            Geofence.Builder()
                .setRequestId(place.id.toString())
                .setCircularRegion(place.latitude, place.longitude, place.radiusMeters.toFloat())
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        val request = GeofencingRequest.Builder()
            // dacă utilizatorul e deja în zonă când înregistrăm, primim imediat „intrare”
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        return try {
            client.addGeofences(request, intent).await()
            true
        } catch (_: Exception) {
            false
        }
    }
}