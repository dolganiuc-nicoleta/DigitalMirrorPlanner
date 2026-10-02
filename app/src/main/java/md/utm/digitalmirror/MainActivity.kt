package md.utm.digitalmirror

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import md.utm.digitalmirror.reminder.ReminderNotifications
import md.utm.digitalmirror.reminder.ReminderScheduler
import md.utm.digitalmirror.ui.closing.ClosingScreen
import md.utm.digitalmirror.ui.day.DayScreen
import md.utm.digitalmirror.ui.theme.DigitalMirrorPlannerTheme
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ReminderNotifications.createChannel(this)
        ReminderScheduler.schedule(this)
        ReminderScheduler.testNow(this)

        // true dacă aplicația a fost deschisă din notificare
        val openClosing = intent.getBooleanExtra(ReminderNotifications.EXTRA_OPEN_CLOSING, false)

        setContent {
            DigitalMirrorPlannerTheme {
                RequestNotificationPermission()

                var closingDate by rememberSaveable {
                    mutableStateOf(if (openClosing) LocalDate.now().toString() else null)
                }
                val date = closingDate

                if (date == null) {
                    DayScreen(onCloseDay = { closingDate = it })
                } else {
                    BackHandler { closingDate = null }
                    ClosingScreen(date = date, onBack = { closingDate = null })
                }
            }
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