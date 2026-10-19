package md.utm.digitalmirror.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.entity.AvailabilityRule
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.ui.components.DurationField
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.data.usage.UsageReader
import md.utm.digitalmirror.data.health.HealthSync

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenPlaces: () -> Unit = {},
    vm: SettingsViewModel = viewModel()
) {
    val context = LocalContext.current

    val reminderEnabled by vm.reminderEnabled.collectAsState()
    val reminderMinute by vm.reminderMinute.collectAsState()
    val categories by vm.categories.collectAsState()
    val rules by vm.rules.collectAsState()
    val daysOff by vm.daysOff.collectAsState()
    val places by vm.places.collectAsState()

    // null = dialogul e închis; AvailabilityRule cu id 0 = bloc nou
    var editingRule by remember { mutableStateOf<AvailabilityRule?>(null) }
    var showDayOffPicker by remember { mutableStateOf(false) }

    var showTimePicker by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    // exportul: utilizatorul alege unde salvează fișierul (Descărcări, Drive etc.)
    var exportFull by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            vm.export(it, exportFull) { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        uri?.let {
            vm.exportCsv(it, exportFull) { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }
    val analysisEnabled by vm.analysisEnabled.collectAsState()
    var newCategory by remember { mutableStateOf("") }

    // copia de rezervă: utilizatorul alege unde o salvează / ce fișier restaurează
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            vm.createBackup(it) { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            vm.readBackup(it) { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        }
    }
    val restorePreview by vm.restorePreview.collectAsState()

    val healthStatus by vm.healthStatus.collectAsState()
    val healthMessage by vm.healthMessage.collectAsState()

    // ecranul de permisiuni Health Connect
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.onHealthPermissionsResult() }

    // calendarul telefonului: permisiune obișnuită de citire
    var calendarConnected by remember { mutableStateOf(CalendarReader.hasPermission(context)) }
    var calendarDenied by remember { mutableStateOf(false) }
    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        calendarConnected = granted
        calendarDenied = !granted
    }
    // accesul la statistici de utilizare se dă din setările telefonului, deci recitim starea la revenire
    var usageConnected by remember { mutableStateOf(UsageReader.hasPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) usageConnected = UsageReader.hasPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val openAppSettings = {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Setări") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // 1. reminder de seară
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Reminder de seară")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Notificare pentru închiderea zilei")
                                Hint("Nu apare dacă reflecția zilei e deja completată")
                            }
                            Switch(
                                checked = reminderEnabled,
                                onCheckedChange = { vm.setReminderEnabled(it) }
                            )
                        }
                        if (reminderEnabled) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = { showTimePicker = true }) {
                                Text("Ora: ${formatMinute(reminderMinute)}")
                            }
                        }
                    }
                }
            }

            // 2. categorii
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Categorii")
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text("Categorie nouă") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            vm.addCategory(newCategory)
                            newCategory = ""
                        },
                        enabled = newCategory.isNotBlank()
                    ) { Text("Adaugă") }
                }
            }
            if (categories.isEmpty()) {
                item { Hint("Încă nu ai categorii") }
            }
            items(categories, key = { it.id }) { category ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(category.name, modifier = Modifier.weight(1f))
                        TextButton(onClick = { renaming = category }) { Text("Redenumește") }
                        TextButton(onClick = { deleting = category }) {
                            Text("Șterge", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            // 3. Health Connect
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Somn și pași")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        when (healthStatus) {
                            HealthSync.Status.CONNECTED -> {
                                Text("✓ Conectat la Health Connect")
                                Hint("Somnul și pașii se actualizează la fiecare deschidere a aplicației")
                                OutlinedButton(onClick = { vm.syncHealth() }) { Text("Sincronizează acum") }
                            }
                            HealthSync.Status.NOT_CONNECTED -> {
                                Text("Citește somnul și pașii din Health Connect")
                                Hint("Datele sunt folosite doar local, pentru recomandări")
                                Button(onClick = {
                                    healthPermissionLauncher.launch(HealthSync.PERMISSIONS)
                                }) { Text("Conectează") }
                            }
                            HealthSync.Status.UPDATE_REQUIRED -> {
                                Text("Health Connect trebuie actualizat")
                                OutlinedButton(onClick = {
                                    val uri = Uri.parse(
                                        "market://details?id=com.google.android.apps.healthdata"
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }) { Text("Deschide Magazin Play") }
                            }
                            HealthSync.Status.NOT_AVAILABLE -> {
                                Text("Health Connect nu este disponibil pe acest telefon")
                            }
                        }
                        healthMessage?.let { Hint(it) }
                    }
                }
            }

            // 3a. locurile (Facultate, Sală…) alese pe hartă
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Locuri")
            }
            item {
                val placeCount by vm.placeCount.collectAsState()
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            if (placeCount == 0) "Nu ai încă niciun loc"
                            else if (placeCount == 1) "1 loc salvat" else "$placeCount locuri salvate"
                        )
                        Hint("Locurile rămân pe telefon. Cu ele aplicația va ști unde se desfășoară activitățile.")
                        OutlinedButton(onClick = onOpenPlaces) { Text("Gestionează locurile") }
                    }
                }
            }

            // 3b. programul fix: cursuri, masă, somn, cu drumul inclus
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Program fix")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (rules.isEmpty()) {
                            Text("Adaugă orele tale fixe: cursuri, masă, somn.")
                            Hint("Recomandarea orei le ocolește, inclusiv drumul până la ele și de la ele.")
                        }
                        rules.forEach { rule ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rule.title, fontWeight = FontWeight.Medium)
                                    Hint(
                                        "${Availability.daysLabel(rule.daysMask)} · " +
                                                "${formatClock(rule.startMinute)}–${formatClock(rule.endMinute)}"
                                    )
                                    places.find { it.id == rule.placeId }?.let { Hint("la ${it.name}") }
                                    if (rule.travelBeforeMinutes > 0 || rule.travelAfterMinutes > 0) {
                                        Hint(
                                            "drum: " + listOfNotNull(
                                                rule.travelBeforeMinutes.takeIf { it > 0 }?.let { "${formatDuration(it)} înainte" },
                                                rule.travelAfterMinutes.takeIf { it > 0 }?.let { "${formatDuration(it)} după" }
                                            ).joinToString(" · ")
                                        )
                                    }
                                }
                                TextButton(onClick = { editingRule = rule }) { Text("Editează") }
                                TextButton(onClick = { vm.deleteRule(rule) }) {
                                    Text("Șterge", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            editingRule = AvailabilityRule(
                                title = "", daysMask = Availability.WEEKDAYS,
                                startMinute = 8 * 60, endMinute = 14 * 60,
                                travelBeforeMinutes = 0, travelAfterMinutes = 0
                            )
                        }) { Text("Adaugă un bloc") }
                    }
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Zile libere", fontWeight = FontWeight.Medium)
                        Hint("În zilele libere blocurile fixe nu se aplică, iar ziua nu intră în media zilelor tipice. Poți marca și direct din ecranul zilei.")
                        val today = java.time.LocalDate.now().toString()
                        daysOff.filter { it >= today }.forEach { date ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    java.time.LocalDate.parse(date).format(
                                        java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy", java.util.Locale.forLanguageTag("ro"))
                                    ).replaceFirstChar { it.uppercase() },
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { vm.removeDayOff(date) }) { Text("Scoate") }
                            }
                        }
                        OutlinedButton(onClick = { showDayOffPicker = true }) { Text("Marchează o zi liberă") }
                    }
                }
            }

            // 4. calendarul
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Calendar")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (calendarConnected) {
                            Text("✓ Calendarul telefonului este conectat")
                            Hint("Evenimentele apar în ecranul zilei, iar orele ocupate sunt evitate în recomandări. Aplicația doar le citește.")
                            OutlinedButton(onClick = openAppSettings) { Text("Gestionează permisiunea") }
                        } else {
                            Text("Vezi evenimentele din calendar lângă activitățile tale")
                            Hint("Toate calendarele de pe telefon: Samsung, Google, Outlook. Doar citire, datele rămân pe telefon.")
                            Button(onClick = {
                                calendarPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                            }) { Text("Conectează") }
                            if (calendarDenied) {
                                Hint("Permisiunea a fost refuzată. O poți activa din setările aplicației → Permisiuni → Calendar.")
                                OutlinedButton(onClick = openAppSettings) { Text("Deschide setările aplicației") }
                            }
                        }
                    }
                }
            }

            // 5. timpul pe telefon
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Timp pe telefon")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (usageConnected) {
                            Text("✓ Accesul la statistici este activ")
                            Hint("Pentru fiecare sesiune de cronometru se salvează câte minute ai folosit telefonul și primele 5 aplicații. Nu se salvează conținut.")
                            OutlinedButton(onClick = { UsageReader.openSettings(context) }) { Text("Gestionează accesul") }
                        } else {
                            Text("Vezi cât ai folosit telefonul în timpul unei activități")
                            Hint("Android cere ca accesul să fie activat manual, o singură dată: cauți „Digital Mirror” în listă și îl permiți. Datele rămân pe telefon.")
                            Button(onClick = { UsageReader.openSettings(context) }) { Text("Deschide setările") }
                        }
                    }
                }
            }

            // 6. copie de rezervă
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Copie de rezervă")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Salvează tot într-un fișier și restaurează-l după o reinstalare sau pe alt telefon.")
                        Hint("Fișierul conține titlurile și reflecțiile tale. Păstrează-l într-un loc sigur, de exemplu în Drive.")
                        Button(onClick = { backupLauncher.launch(vm.backupFileName()) }) {
                            Text("Salvează copia")
                        }
                        OutlinedButton(onClick = { restoreLauncher.launch(arrayOf("*/*")) }) {
                            Text("Restaurează din copie")
                        }
                    }
                }
            }

            // 6b. analiza și recomandările
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Analiză și recomandări")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Propuneri și observații")
                                Hint(
                                    if (analysisEnabled)
                                        "Aplicația propune durate și ore, avertizează la supraîncărcare și arată observații din istoricul tău."
                                    else
                                        "Oprit: nu mai primești propuneri, avertizări sau observații. Activitățile se salvează în continuare, " +
                                                "ca la repornire să existe istoric."
                                )
                            }
                            Switch(checked = analysisEnabled, onCheckedChange = { vm.setAnalysisEnabled(it) })
                        }
                    }
                }
            }

            // 7. date
            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Date")
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Toate datele sunt stocate doar pe acest telefon.")

                        // export JSON
                        Hint("ID participant: ${vm.participantId}")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Include titlurile și textele")
                                Hint(
                                    if (exportFull) "Complet, cu titluri și texte. Nu se poate restaura; pentru asta folosește copia de rezervă"
                                    else "Anonimizat: fără titluri și fără textul reflecțiilor"
                                )
                            }
                            Switch(checked = exportFull, onCheckedChange = { exportFull = it })
                        }
                        Button(onClick = { exportLauncher.launch(vm.exportFileName(exportFull)) }) {
                            Text("Exportă datele (JSON)")
                        }
                        OutlinedButton(onClick = { csvLauncher.launch(vm.csvFileName(exportFull)) }) {
                            Text("Exportă datele (CSV)")
                        }
                        Hint("CSV: un rând pe activitate, separat prin „;”, ca să se deschidă direct în Excel.")

                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { confirmReset = true },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) { Text("Șterge toate datele") }
                    }
                }
            }
        }
    }

    // dialoguri
    if (showTimePicker) {
        ReminderTimeDialog(
            initialMinute = reminderMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = {
                vm.setReminderMinute(it)
                showTimePicker = false
            }
        )
    }

    renaming?.let { category ->
        RenameDialog(
            category = category,
            onDismiss = { renaming = null },
            onConfirm = {
                vm.renameCategory(category, it)
                renaming = null
            }
        )
    }

    deleting?.let { category ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Ștergi categoria „${category.name}”?") },
            text = { Text("Activitățile din această categorie rămân, dar vor fi fără categorie.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteCategory(category)
                    deleting = null
                }) { Text("Șterge", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Anulează") }
            }
        )
    }

    editingRule?.let { rule ->
        RuleDialog(
            initial = rule,
            places = places,
            homePlaceId = vm.homePlaceId(),
            modeLabel = vm.travelModeLabel(),
            onEstimate = { fromId, placeId -> vm.estimateBlockTravel(fromId, placeId) },
            onDismiss = { editingRule = null },
            onSave = {
                vm.saveRule(it)
                editingRule = null
            }
        )
    }

    if (showDayOffPicker) {
        DayOffDatePicker(
            onDismiss = { showDayOffPicker = false },
            onConfirm = {
                vm.addDayOff(it)
                showDayOffPicker = false
            }
        )
    }

    restorePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("Restaurezi această copie?") },
            text = {
                Text(
                    "Copia conține: ${preview.describe()}.\n\n" +
                            "Datele de acum din aplicație vor fi înlocuite. Acțiunea nu poate fi anulată."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmRestore { message ->
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }) { Text("Înlocuiește", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { vm.cancelRestore() }) { Text("Anulează") }
            }
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Ștergi toate datele?") },
            text = {
                Text(
                    "Se șterg toate activitățile, categoriile, sesiunile de lucru și reflecțiile. " +
                            "Acțiunea nu poate fi anulată."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.deleteAllData {
                        Toast.makeText(context, "Datele au fost șterse", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Șterge tot", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text("Anulează") }
            }
        )
    }
}

@Composable
private fun RenameDialog(
    category: Category,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(category.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Redenumește categoria") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Denumire") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) {
                Text("Salvează")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    title: String = "Ora reminder-ului"
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceChoice(
    label: String,
    none: String,
    places: List<Place>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
    allowNone: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = places.find { it.id == selectedId }?.name ?: none,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text(none) }, onClick = { onSelect(null); expanded = false })
            }
            places.forEach { place ->
                DropdownMenuItem(text = { Text(place.name) }, onClick = { onSelect(place.id); expanded = false })
            }
        }
    }
}

private fun formatClock(minute: Int): String =
    if (minute >= 24 * 60) "24:00" else "%02d:%02d".format(minute / 60, minute % 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleDialog(
    initial: AvailabilityRule,
    places: List<Place>,
    homePlaceId: Long?,
    modeLabel: String,
    onEstimate: suspend (fromId: Long, placeId: Long) -> Result<Pair<Int, Int>>,
    onDismiss: () -> Unit,
    onSave: (AvailabilityRule) -> Unit
) {
    var title by remember { mutableStateOf(initial.title) }
    var daysMask by remember { mutableStateOf(initial.daysMask) }
    var start by remember { mutableStateOf(initial.startMinute) }
    var end by remember { mutableStateOf(initial.endMinute) }
    var travelBefore by remember { mutableStateOf(initial.travelBeforeMinutes.takeIf { it > 0 }) }
    var travelAfter by remember { mutableStateOf(initial.travelAfterMinutes.takeIf { it > 0 }) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    // locul blocului și de unde pleci, pentru calculul automat al drumului
    var placeId by remember { mutableStateOf(initial.placeId) }
    var fromId by remember { mutableStateOf(homePlaceId?.takeIf { id -> places.any { it.id == id } }) }
    var calculating by remember { mutableStateOf(false) }
    var travelMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val valid = title.isNotBlank() && daysMask != 0 && end > start

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "Bloc nou" else "Editează blocul") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Denumire (de ex. Cursuri, Masă, Somn)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("În zilele", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0..6) {
                        val bit = 1 shl i
                        FilterChip(
                            selected = daysMask and bit != 0,
                            onClick = { daysMask = daysMask xor bit },
                            label = { Text(Availability.shortDayName(i)) }
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingStart = true }, modifier = Modifier.weight(1f)) {
                        Text("De la ${formatClock(start)}")
                    }
                    OutlinedButton(onClick = { pickingEnd = true }, modifier = Modifier.weight(1f)) {
                        Text("Până la ${formatClock(end)}")
                    }
                }
                if (end <= start) Hint("Ora de sfârșit trebuie să fie după cea de început.")

                if (places.isNotEmpty()) {
                    PlaceChoice(
                        label = "Unde se ține? (opțional)",
                        none = "fără loc",
                        places = places,
                        selectedId = placeId,
                        onSelect = { placeId = it; travelMessage = null }
                    )
                }
                if (places.size >= 2 && placeId != null) {
                    PlaceChoice(
                        label = "Pleci de la",
                        none = "alege un loc",
                        places = places.filter { it.id != placeId },
                        selectedId = fromId,
                        onSelect = { fromId = it; travelMessage = null },
                        allowNone = false
                    )
                    val origin = fromId
                    val target = placeId
                    OutlinedButton(
                        enabled = origin != null && target != null && origin != target && !calculating,
                        onClick = {
                            if (origin != null && target != null) {
                                calculating = true
                                travelMessage = null
                                scope.launch {
                                    onEstimate(origin, target)
                                        .onSuccess { (before, after) ->
                                            travelBefore = before
                                            travelAfter = after
                                            travelMessage = "Calculat ($modeLabel). Poți modifica minutele."
                                        }
                                        .onFailure { travelMessage = it.message ?: "Ceva nu a mers." }
                                    calculating = false
                                }
                            }
                        }
                    ) {
                        if (calculating) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Calculează drumul")
                    }
                    travelMessage?.let { Hint(it) }
                }

                DurationField(
                    label = "Drum până acolo (opțional)",
                    minutes = travelBefore,
                    onChange = { travelBefore = it },
                    showQuickValues = false
                )
                DurationField(
                    label = "Drum de la ea înapoi (opțional)",
                    minutes = travelAfter,
                    onChange = { travelAfter = it },
                    showQuickValues = false
                )
                Hint("Drumul se adaugă la interval: cursuri 08:00–14:00 cu 40 min drum la dus și la întors blochează 07:20–14:40.")
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            title = title.trim(),
                            daysMask = daysMask,
                            startMinute = start,
                            endMinute = end,
                            travelBeforeMinutes = travelBefore ?: 0,
                            travelAfterMinutes = travelAfter ?: 0,
                            placeId = placeId
                        )
                    )
                }
            ) { Text("Salvează") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    )

    if (pickingStart) {
        ReminderTimeDialog(
            initialMinute = start,
            title = "Începe la",
            onDismiss = { pickingStart = false },
            onConfirm = { start = it; pickingStart = false }
        )
    }
    if (pickingEnd) {
        ReminderTimeDialog(
            initialMinute = end.coerceAtMost(23 * 60 + 59),
            title = "Se termină la",
            onDismiss = { pickingEnd = false },
            onConfirm = { end = it; pickingEnd = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayOffDatePicker(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val todayMillis = java.time.LocalDate.now()
        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = todayMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= todayMillis
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = java.time.Instant.ofEpochMilli(millis)
                            .atZone(java.time.ZoneOffset.UTC).toLocalDate()
                        onConfirm(picked.toString())
                    }
                }
            ) { Text("Marchează") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    ) {
        DatePicker(state = state)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)

@Composable
private fun Hint(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

private fun formatMinute(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
