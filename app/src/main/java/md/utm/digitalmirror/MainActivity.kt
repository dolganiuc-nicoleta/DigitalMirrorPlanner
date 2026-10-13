package md.utm.digitalmirror

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.health.HealthSync
import md.utm.digitalmirror.data.usage.UsageCollector
import md.utm.digitalmirror.reminder.ActivityReminders
import md.utm.digitalmirror.reminder.ReminderNotifications
import md.utm.digitalmirror.reminder.ReminderScheduler
import md.utm.digitalmirror.ui.navigation.AppNavigation
import md.utm.digitalmirror.ui.theme.DigitalMirrorPlannerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ReminderNotifications.createChannel(this)
        ReminderScheduler.schedule(this)
        ActivityReminders.createChannels(this)

        // alarmele activităților se pierd la repornirea telefonului, așa că le refacem la fiecare pornire
        lifecycleScope.launch { ActivityReminders.rescheduleAll(applicationContext) }

        // true dacă aplicația a fost deschisă din notificare
        val openClosing = intent.getBooleanExtra(ReminderNotifications.EXTRA_OPEN_CLOSING, false)

        setContent {
            DigitalMirrorPlannerTheme {
                AppNavigation(openClosingToday = openClosing)
            }
        }
    }

    // Health Connect permite citirea datelor doar cât aplicația e deschisă,
    // așa că sincronizăm de fiecare dată când utilizatorul revine în aplicație
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            HealthSync(applicationContext).syncIfPermitted()
            // timpul pe telefon pentru sesiunile terminate de la ultima deschidere
            UsageCollector.collectMissing(applicationContext)
        }
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* rezultatul nu ne trebuie acum */ }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}