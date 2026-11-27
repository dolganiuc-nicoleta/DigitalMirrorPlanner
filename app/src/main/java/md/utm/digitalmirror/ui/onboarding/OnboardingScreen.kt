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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.domain.TravelEstimator
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.theme.Fraunces

private val SUGGESTED_CATEGORIES = listOf(
    "Studiu", "Laboratoare", "Muncă", "Proiecte", "Sport", "Casă", "Relaxare", "Lectură"
)

/** Câți pași are configurarea, fără ecranul de bun venit. */
private const val STEPS = 6

private val WAKE_CHOICES = listOf(6 * 60, 6 * 60 + 30, 7 * 60, 7 * 60 + 30, 8 * 60, 9 * 60)
private val BED_CHOICES = listOf(22 * 60, 22 * 60 + 30, 23 * 60, 23 * 60 + 30, 0, 30)

private fun hm(minute: Int) = "%d:%02d".format((minute / 60) % 24, minute % 60)

/**
 * Configurarea inițială: bun venit, apoi 6 pași scurți (tiparul zilei, ritmul, mesele, drumurile, categoriile,
 * permisiunile). Fiecare pas are valori bune deja alese, deci se poate trece prin toți în sub un minut;
 * orice se schimbă mai târziu din Setări. Scopul: din prima zi, planul are trezire, mese, drumuri și pregătire.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onOpenPlaces: () -> Unit = {},
    vm: OnboardingViewModel = viewModel()
) {
    var step by rememberSaveable { mutableIntStateOf(0) }

    // 1. răspunsurile la cele 3 întrebări
    var fixedSchedule by rememberSaveable { mutableStateOf<String?>(null) }
    var planChanges by rememberSaveable { mutableStateOf<String?>(null) }
    var shifts by rememberSaveable { mutableStateOf<String?>(null) }
    val pattern = OnboardingPrefs.patternFrom(fixedSchedule, planChanges, shifts)

    // 2. ritmul
    var wake by rememberSaveable { mutableIntStateOf(vm.initialWake) }
    var bed by rememberSaveable { mutableIntStateOf(vm.initialBed) }
    var prep by rememberSaveable { mutableIntStateOf(vm.initialPrep) }

    // 3. mesele
    var meals by rememberSaveable { mutableStateOf(vm.initialMeals.filter { it.enabled }.map { it.kind.name }.toSet()) }

    // 4. drumurile
    var mode by rememberSaveable { mutableStateOf(vm.initialMode) }
    val places by vm.places.collectAsState()

    // 5. categoriile alese
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
            // progresul: liniuțe, câte una pe pas
            if (step > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { step-- }, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
                        Text("← Înapoi")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "$step din $STEPS",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..STEPS).forEach { i ->
                        Box(
                            Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (i <= step) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                        )
                    }
                }
            }

            when (step) {
                // ---------- 0. bun venit ----------
                0 -> {
                    Spacer(Modifier.height(40.dp))
                    Text(
                        "Timpul tău,\nplanificat din istoricul tău",
                        fontFamily = Fraunces,
                        style = MaterialTheme.typography.displaySmall
                    )
                    Text(
                        "Aplicația învață cât îți iau de fapt activitățile, drumurile și zilele, " +
                                "și planifică după tine, nu după cum ar fi ideal.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Bullet("Spui cât crezi că durează ceva, iar aplicația îți arată cât a durat de obicei.")
                    Bullet("Ziua ta are loc pentru drumuri, mese și pregătire, nu doar pentru sarcini.")
                    Bullet("Fiecare propunere vine cu explicația ei. Datele rămân pe telefon.")
                    Spacer(Modifier.height(24.dp))
                    BigButton("Începe") { step = 1 }
                    Text(
                        "6 pași scurți, cu valori deja alese. Le poți schimba oricând din Setări.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                // ---------- 1. tiparul zilei ----------
                1 -> {
                    StepTitle("Cum arată de obicei ziua ta?", "Ne ajută doar în primele zile. Apoi aplicația învață din ce faci.")
                    Question("Ai un program fix în majoritatea zilelor?", listOf("Da", "Parțial", "Nu"), fixedSchedule) {
                        fixedSchedule = it
                    }
                    Question("Cât de des ți se schimbă planurile în timpul zilei?", listOf("Rar", "Uneori", "Des"), planChanges) {
                        planChanges = it
                    }
                    Question("Lucrezi pe ture?", listOf("Da", "Nu"), shifts) { shifts = it }

                    if (pattern != Pattern.SKIPPED) {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Tiparul tău de pornire", style = MaterialTheme.typography.labelMedium)
                                Text(pattern.label, fontFamily = Fraunces, style = MaterialTheme.typography.titleLarge)
                                Text(pattern.description, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    BigButton("Continuă") { step = 2 }
                    SkipButton { fixedSchedule = null; planChanges = null; shifts = null; step = 2 }
                }

                // ---------- 2. ritmul: trezire, culcare, pregătire ----------
                2 -> {
                    StepTitle(
                        "Ritmul tău",
                        "De la trezire și până la culcare punem lucruri în zi; ultima oră de seară rămâne liberă."
                    )
                    TimeChoice("Te trezești de obicei la", WAKE_CHOICES, wake) { wake = it }
                    TimeChoice("Te culci de obicei la", BED_CHOICES, bed) { bed = it }
                    val sleep = md.utm.digitalmirror.domain.SleepPlanner.Habit.of(wake, bed).sleepMinutes
                    Text(
                        "≈ ${formatDuration(sleep)} de somn. După câteva nopți confirmate, aplicația folosește nopțile tale reale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cât îți ia să ieși din casă?", fontWeight = FontWeight.Medium)
                        Text(
                            "Îmbrăcat, geantă, încuiat ușa. Se pune înainte de fiecare plecare de acasă.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TravelPrefs.PREP_CHOICES.forEach { m ->
                                FilterChip(selected = prep == m, onClick = { prep = m }, label = { Text(formatDuration(m)) })
                            }
                        }
                    }
                    BigButton("Continuă") { vm.saveRhythm(wake, bed, prep); step = 3 }
                    SkipButton { step = 3 }
                }

                // ---------- 3. mesele ----------
                3 -> {
                    StepTitle(
                        "Mesele",
                        "Le păstrăm loc în zi, ca ferestre: prânzul e „cândva între 12 și 15”, chiar și într-o pauză scurtă."
                    )
                    vm.initialMeals.forEach { w ->
                        val on = w.kind.name in meals
                        Surface(
                            onClick = { meals = if (on) meals - w.kind.name else meals + w.kind.name },
                            shape = RoundedCornerShape(16.dp),
                            color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(w.kind.emoji, style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(w.kind.label, fontWeight = FontWeight.Medium)
                                    Text(
                                        "între ${hm(w.from)} și ${hm(w.to)} · ${formatDuration(w.minutes)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(checked = on, onCheckedChange = { meals = if (on) meals - w.kind.name else meals + w.kind.name })
                            }
                        }
                    }
                    Text(
                        "Orele ferestrelor le schimbi în Setări → Mese.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    BigButton("Continuă") {
                        vm.saveMeals(meals.mapNotNull { n -> MealPlanner.Kind.entries.firstOrNull { it.name == n } }.toSet())
                        step = 4
                    }
                    SkipButton { step = 4 }
                }

                // ---------- 4. drumurile: cum te deplasezi și locurile ----------
                4 -> {
                    StepTitle(
                        "Drumurile tale",
                        "Ca ziua să lase timp de drum între casă, facultate, sală sau serviciu."
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cum te deplasezi de obicei?", fontWeight = FontWeight.Medium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TravelEstimator.Mode.entries.forEach { m ->
                                FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label) })
                            }
                        }
                        Text(
                            "Poți alege alt mod pentru fiecare drum, mai târziu, pe hartă.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Locurile tale", fontWeight = FontWeight.Medium)
                            Text(
                                if (places.isEmpty()) "Adaugă măcar Acasă și locul unde mergi cel mai des (facultatea, serviciul)."
                                else places.joinToString(" · ") { it.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(onClick = { vm.saveMode(mode); onOpenPlaces() }) {
                                Text(if (places.isEmpty()) "Adaugă locurile" else "Adaugă sau schimbă locurile")
                            }
                        }
                    }
                    BigButton("Continuă") { vm.saveMode(mode); step = 5 }
                    SkipButton { step = 5 }
                }

                // ---------- 5. categorii ----------
                5 -> {
                    StepTitle(
                        "Ce fel de activități ai?",
                        "Alege 4–6 categorii. Cu puține categorii, aplicația învață mai repede: fiecare adună mai multe activități."
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
                            placeholder = { Text("Altă categorie") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                if (custom.isNotBlank()) selected = selected + custom.trim()
                                custom = ""
                            },
                            enabled = custom.isNotBlank()
                        ) { Text("Adaugă") }
                    }
                    BigButton(if (selected.isEmpty()) "Continuă fără categorii" else "Continuă (${selected.size})") {
                        vm.addCategories(selected); step = 6
                    }
                }

                // ---------- 6. permisiuni ----------
                6 -> {
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
private fun StepTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontFamily = Fraunces, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(text) }
}

@Composable
private fun SkipButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Sari peste") }
}

/** O oră aleasă din câteva variante obișnuite, sau oricare alta din ceas. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TimeChoice(label: String, choices: List<Int>, value: Int, onChange: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontWeight = FontWeight.Medium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { m ->
                FilterChip(selected = value == m, onClick = { onChange(m) }, label = { Text(hm(m)) })
            }
            FilterChip(
                selected = value !in choices,
                onClick = { picking = true },
                label = { Text(if (value in choices) "Altă oră" else hm(value)) }
            )
        }
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = value / 60 % 24, initialMinute = value % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = { TextButton(onClick = { onChange(state.hour * 60 + state.minute); picking = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Anulează") } }
        )
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