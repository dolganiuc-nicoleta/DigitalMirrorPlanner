package md.utm.digitalmirror.data.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.db.AppDatabase
import md.utm.digitalmirror.data.entity.PresenceLog

/** Primește de la sistem intrările și ieșirile din locuri și le salvează în presence_log. */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return

        val transition = event.geofenceTransition
        val placeIds = event.triggeringGeofences?.mapNotNull { it.requestId.toLongOrNull() } ?: return
        if (placeIds.isEmpty()) return

        // scrierea în baza de date nu are voie pe firul principal; goAsync ține receiver-ul în viață cât scriem
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.get(app).presenceLogDao()
                val now = System.currentTimeMillis()
                for (placeId in placeIds) {
                    val open = dao.openFor(placeId)
                    when (transition) {
                        Geofence.GEOFENCE_TRANSITION_ENTER ->
                            // „intrare” repetată (de ex. după reînregistrare) nu creează o a doua vizită
                            if (open == null) dao.insert(PresenceLog(placeId = placeId, enterAt = now))
                        Geofence.GEOFENCE_TRANSITION_EXIT ->
                            open?.let { dao.close(it.id, now) }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}