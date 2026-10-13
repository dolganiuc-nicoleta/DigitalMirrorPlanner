package md.utm.digitalmirror.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.compose.foundation.layout.*
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
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.usage.UsageReader
import md.utm.digitalmirror.data.health.HealthSync

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel = viewModel()) {
    val context = LocalContext.current

    val reminderEnabled by vm.reminderEnabled.collectAsState()
    val reminderMinute by vm.reminderMinute.collectAsState()
    val categories by vm.categories.collectAsState()

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
    var newCategory by remember { mutableStateOf("") }

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
        topBar = { TopAppBar(title = { Text("Setări") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // 1. reminder de seară
            item { SectionTitle("Reminder de seară") }
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

            // 6. date
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
                                    if (exportFull) "Backup complet, pentru tine"
                                    else "Anonimizat: fără titluri și fără textul reflecțiilor"
                                )
                            }
                            Switch(checked = exportFull, onCheckedChange = { exportFull = it })
                        }
                        Button(onClick = { exportLauncher.launch(vm.exportFileName(exportFull)) }) {
                            Text("Exportă datele (JSON)")
                        }

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
    onConfirm: (Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ora reminder-ului") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
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