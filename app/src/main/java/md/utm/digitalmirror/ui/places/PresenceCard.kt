package md.utm.digitalmirror.ui.places

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.data.dao.RecentVisit
import md.utm.digitalmirror.data.geo.GeofenceManager
import md.utm.digitalmirror.data.prefs.PresencePrefs
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * „Detectare automată a locului”: comutator, permisiuni și ultimele prezențe.
 * Permisiunile se cer în doi pași, cum cere Android: întâi locația, apoi „Permite tot timpul”.
 */
@Composable
fun PresenceCard(
    placeCount: Int,
    recentVisits: List<RecentVisit>,
    onChanged: () -> Unit
) {
    val context = LocalContext.current

    var enabled by remember { mutableStateOf(PresencePrefs.isEnabled(context)) }
    var foreground by remember { mutableStateOf(GeofenceManager.hasForegroundPermission(context)) }
    var background by remember { mutableStateOf(GeofenceManager.hasBackgroundPermission(context)) }
    var explainBackground by remember { mutableStateOf(false) }

    fun refresh() {
        foreground = GeofenceManager.hasForegroundPermission(context)
        background = GeofenceManager.hasBackgroundPermission(context)
        onChanged()
    }

    // pasul 2: „Permite tot timpul” (pe Android 11+ sistemul deschide direct setările aplicației)
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    // pasul 1: locația cât aplicația e deschisă
    val foregroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refresh()
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !GeofenceManager.hasBackgroundPermission(context)
        ) explainBackground = true
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Detectare automată", fontWeight = FontWeight.Medium)
                    Text(
                        "Telefonul observă când ajungi la un loc și când pleci, ca să știm cât timp ai petrecut acolo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        enabled = on
                        PresencePrefs.setEnabled(context, on)
                        if (on && !GeofenceManager.hasForegroundPermission(context)) {
                            foregroundLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                        } else if (on && !GeofenceManager.hasBackgroundPermission(context)) {
                            explainBackground = true
                        }
                        refresh()
                    }
                )
            }

            // starea, în cuvinte
            val status = when {
                !enabled -> "Oprită."
                placeCount == 0 -> "Pornită, dar nu ai niciun loc. Adaugă un loc mai jos."
                !foreground -> "Lipsește permisiunea de locație."
                !background -> "Lipsește „Permite tot timpul”. Fără ea, nu detectăm cu aplicația închisă."
                else -> "Activă pentru $placeCount ${if (placeCount == 1) "loc" else "locuri"}."
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled && (!foreground || !background)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (enabled && (!foreground || !background)) {
                OutlinedButton(onClick = { openAppSettings(context) }) { Text("Deschide setările aplicației") }
            }

            Text(
                "Locația ta rămâne doar pe telefon. Nu se trimite nicăieri.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (recentVisits.isNotEmpty()) {
                HorizontalDivider()
                Text("Prezențe recente", style = MaterialTheme.typography.labelLarge)
                recentVisits.forEach { visit ->
                    Text(
                        "${visit.placeName} · ${formatVisit(visit)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    if (explainBackground) {
        AlertDialog(
            onDismissRequest = { explainBackground = false },
            title = { Text("Încă un pas") },
            text = {
                Text(
                    "Ca să observăm sosirea chiar dacă aplicația e închisă, Android cere să alegi " +
                            "„Permite tot timpul” pentru locație. Datele rămân pe telefon."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainBackground = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Continuă") }
            },
            dismissButton = { TextButton(onClick = { explainBackground = false }) { Text("Nu acum") } }
        )
    }
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

private val dayFormat = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault())
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatVisit(visit: RecentVisit): String {
    val start = Instant.ofEpochMilli(visit.enterAt)
    val exit = visit.exitAt
    return if (exit == null) "${dayFormat.format(start)}, de la ${timeFormat.format(start)} (încă acolo)"
    else "${dayFormat.format(start)}, ${timeFormat.format(start)}–${timeFormat.format(Instant.ofEpochMilli(exit))}"
}