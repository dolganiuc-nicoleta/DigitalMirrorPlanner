package md.utm.digitalmirror.data.geo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Zonele de urmărit se pierd la repornirea telefonului sau după actualizarea aplicației.
 * Le refacem imediat, ca detectarea să meargă și dacă utilizatorul nu deschide aplicația.
 */
class GeofenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                GeofenceManager.sync(app)
            } finally {
                pending.finish()
            }
        }
    }
}