package md.utm.digitalmirror.ui.onboarding

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs.Pattern

private val SUGGESTED_CATEGORIES = listOf(
    "Studiu", "Laboratoare", "Muncă", "Proiecte", "Sport", "Casă", "Relaxare", "Lectură"
)

/**
 * Configurarea inițială, în 4 pași: bun venit, tiparul zilei, categorii, permisiuni.
 * Durează sub un minut și se poate sări peste orice pas.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit, vm: OnboardingViewModel = viewModel()) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    // răspunsurile la cele 3 întrebări
    var fixedSchedule by rememberSaveable { mutableStateOf<String?>(null) }
    var planChanges by rememberSaveable { mutableStateOf<String?>(null) }
    var shifts by rememberSaveable { mutableStateOf<String?>(null) }
    val pattern = OnboardingPrefs.patternFrom(fixedSchedule, planChanges, shifts)

    // categoriile alese
    var selected by rememberSaveable { mutableStateOf(setOf("Studiu", "Sport", "Casă")) }
    var custom by rememberSaveable { mutableStateOf("") }

    // butonul Back al telefonului întoarce la pasul anterior, nu închide aplicația
    BackHandler(enabled = step > 0) { step-- }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // indicatorul de progres
            if (step > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { step-- },
                        contentPadding = PaddingValues(start = 0.dp, end = 12.dp)
                    ) { Text("← Înapoi") }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Pasul $step din 3",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                LinearProgressIndicator(
                    progress = { step / 3f },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            when (step) {
                // ---------- 0. bun venit ----------
                0 -> {
                    Spacer(Modifier.height(32.dp))
                    Text(
                        "Timpul tău,\nplanificat din istoricul tău",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Aplicația învață cât îți iau de fapt activitățile și când le faci cel mai bine.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Bullet("Spui cât crezi că durează ceva, iar aplicația îți arată cât a durat de obicei.")
                    Bullet("Cronometrul și notificările îți notează timpul real aproape fără efort.")
                    Bullet("Fiecare propunere vine cu explicația ei. Datele rămân pe telefon.")
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Începe") }
                }

                // ---------- 1. tiparul zilei ----------
                1 -> {
                    Text("Cum arată de obicei ziua ta?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Ne ajută doar în primele zile. După aceea aplicația învață din ce faci tu.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Question("Ai un program fix în majoritatea zilelor?", listOf("Da", "Parțial", "Nu"), fixedSchedule) {
                        fixedSchedule = it
                    }
                    Question("Cât de des ți se schimbă planurile în timpul zilei?", listOf("Rar", "Uneori", "Des"), planChanges) {
                        planChanges = it
                    }
                    Question("Lucrezi pe ture?", listOf("Da", "Nu"), shifts) { shifts = it }

                    if (pattern != Pattern.SKIPPED) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Tiparul tău de pornire", style = MaterialTheme.typography.labelMedium)
                                Text(pattern.label, style = MaterialTheme.typography.titleLarge)
                                Text(pattern.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Continuă") }
                    TextButton(
                        onClick = { fixedSchedule = null; planChanges = null; shifts = null; step = 2 },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sari peste") }
                }

                // ---------- 2. categorii ----------
                2 -> {
                    Text("Ce fel de activități ai?", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Alege 4–6 categorii. Cu puține categorii, aplicația învață mai repede, " +
                                "pentru că fiecare adună mai multe activități.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        (SUGGESTED_CATEGORIES + selected.filter { it !in SUGGESTED_CATEGORIES }).forEach { name ->
                            FilterChip(
                                selected = name in selected,
                                onClick = { selected = if (name in selected) selected - name else selected + name },
                                label = { Text(name) }
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = custom,
                            onValueChange = { custom = it },
                            label = { Text("Altă categorie") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                if (custom.isNotBlank()) selected = selected + custom.trim()
                                custom = ""
                            }
                        ) { Text("Adaugă") }
                    }

                    Button(
                        onClick = { vm.addCategories(selected); step = 3 },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (selected.isEmpty()) "Continuă fără categorii" else "Continuă (${selected.size})") }
                }

                // ---------- 3. permisiuni ----------
                3 -> {
                    PermissionsStep(
                        onDone = {
                            vm.finish(pattern)
                            onFinish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionsStep(onDone: () -> Unit) {
    val context = LocalContext.current

    fun notificationsGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    fun exactAlarmsGranted() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    var notifications by remember { mutableStateOf(notificationsGranted()) }
    var exactAlarms by remember { mutableStateOf(exactAlarmsGranted()) }

    // când utilizatorul se întoarce din setările telefonului, verificăm din nou
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notifications = notificationsGranted()
                exactAlarms = exactAlarmsGranted()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notifications = it }

    Text("Două permisiuni", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Ca să nu fie nevoie să deschizi aplicația pentru fiecare activitate.",
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    PermissionCard(
        title = "Notificări",
        description = "„Începi Laborator?” la ora planificată și cronometrul în bara de notificări, cu Start, Pauză și Gata.",
        granted = notifications,
        onRequest = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    )

    PermissionCard(
        title = "Alarme la ora exactă",
        description = "Fără ea, notificările pot întârzia câteva minute. Se activează din setările telefonului.",
        granted = exactAlarms,
        onRequest = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                )
            }
        }
    )

    Text(
        "Somnul și pașii din Health Connect le poți conecta oricând din Setări.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Gata, începe") }
}

@Composable
private fun PermissionCard(title: String, description: String, granted: Boolean, onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) Text("✓", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            else OutlinedButton(onClick = onRequest) { Text("Permite") }
        }
    }
}

@Composable
private fun Question(text: String, options: List<String>, selected: String?, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option) }
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("•", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}