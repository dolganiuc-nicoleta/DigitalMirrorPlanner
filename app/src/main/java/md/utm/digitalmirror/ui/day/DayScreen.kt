package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(onCloseDay: (String) -> Unit = {}, vm: DayViewModel = viewModel()) {
    val date by vm.date.collectAsState()
    val activities by vm.activities.collectAsState()
    val categories by vm.categories.collectAsState()
    val running by vm.runningSession.collectAsState()
    val finishDraft by vm.finishDraft.collectAsState()
    val runningOffset by vm.runningOffset.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.refreshRunning() }

    val isToday = date == LocalDate.now().toString()   // NOU

    Scaffold(
        // NOU: bara de sus cu navigare între zile
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = { vm.previousDay() }) { Text("←") }
                },
                title = {
                    val d = LocalDate.parse(date)
                    Column {
                        Text(
                            d.format(dayFormatter).replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(d.format(dateFormatter), style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    if (!isToday) {
                        TextButton(onClick = { vm.goToToday() }) { Text("Azi") }
                    }
                    TextButton(onClick = { vm.nextDay() }) { Text("→") }
                }
            )
        },

        bottomBar = {
            if (date <= LocalDate.now().toString()) {   // doar azi și zilele trecute
                Surface(tonalElevation = 3.dp) {
                    Button(
                        onClick = { onCloseDay(date) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp)
                    ) { Text("Închide ziua") }
                }
            }
        },


        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) { Text("+") }
        }
    ) { padding ->

        if (activities.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                // NOU: mesaj diferit pentru azi și pentru alte zile
                Text(
                    if (isToday) "Nicio activitate pentru astăzi"
                    else "Nicio activitate în această zi"
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(activities, key = { it.id }) { activity ->
                    ActivityRow(
                        activity = activity,
                        categoryName = categories.find { it.id == activity.categoryId }?.name,
                        runningSince = running?.takeIf { it.activityId == activity.id }?.startedAt,
                        offsetMillis = runningOffset,
                        onToggleTimer = { vm.toggleTimer(activity) },
                        onFinish = { vm.requestFinish(activity) },
                        onDelete = { vm.deleteActivity(activity) }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddActivityDialog(
            categories = categories,
            onDismiss = { showAddDialog = false },
            onAddCategory = { vm.addCategory(it) },
            onConfirm = { title, categoryId, minutes ->
                vm.addActivity(title, categoryId, minutes)
                showAddDialog = false
            }
        )
    }

    finishDraft?.let { draft ->
        FinishDialog(
            draft = draft,
            onDismiss = { vm.dismissFinish() },
            onConfirm = { minutes, state -> vm.confirmFinish(minutes, state) }
        )
    }
}

@Composable
private fun ActivityRow(
    activity: Activity,
    categoryName: String?,
    runningSince: Long?,
    onToggleTimer: () -> Unit,
    onFinish: () -> Unit,
    onDelete: () -> Unit,
    offsetMillis: Long
) {
    val isClosed = activity.state in setOf("DONE", "PARTIAL", "ABANDONED")
    val isRunning = runningSince != null

    // cronometru live, se actualizează în fiecare secundă
    val elapsed by produceState(0L, runningSince, offsetMillis) {
        while (runningSince != null) {
            value = (offsetMillis + System.currentTimeMillis() - runningSince) / 1000
            delay(1000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {

            Text(activity.title, fontWeight = FontWeight.Medium)

            val details = buildList {
                categoryName?.let { add(it) }
                activity.userEstimatedMinutes?.let { add("estimat ${it}m") }
                if (!isRunning) activity.actualMinutes?.let { add("real ${it}m") }
                add(stateLabel(activity.state))
            }.joinToString(" · ")

            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isRunning) {
                val h = elapsed / 3600
                val m = (elapsed % 3600) / 60
                val s = elapsed % 60
                Text(
                    if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isClosed) {
                    OutlinedButton(onClick = onToggleTimer) {
                        Text(
                            when {
                                isRunning -> "Pauză"
                                activity.state == "IN_PROGRESS" -> "Continuă"
                                else -> "Pornește"
                            }
                        )
                    }
                    OutlinedButton(onClick = onFinish) { Text("Finalizează") }
                }
                OutlinedButton(onClick = onDelete) { Text("Șterge") }
            }
        }
    }
}

@Composable
private fun FinishDialog(
    draft: DayViewModel.FinishDraft,
    onDismiss: () -> Unit,
    onConfirm: (Int, String) -> Unit
) {
    var minutes by remember { mutableStateOf(draft.trackedMinutes.toString()) }
    var state by remember { mutableStateOf("DONE") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finalizare activitate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(draft.activity.title, fontWeight = FontWeight.Medium)

                draft.activity.userEstimatedMinutes?.let {
                    Text("Estimat ${it} min · cronometrat ${draft.trackedMinutes} min")
                }

                OutlinedTextField(
                    value = minutes,
                    onValueChange = { input -> minutes = input.filter { it.isDigit() } },
                    label = { Text("Durata reală (minute)") },
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state == "DONE",
                        onClick = { state = "DONE" },
                        label = { Text("Finalizată") }
                    )
                    FilterChip(
                        selected = state == "PARTIAL",
                        onClick = { state = "PARTIAL" },
                        label = { Text("Parțial") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(minutes.toIntOrNull() ?: 0, state) },
                enabled = minutes.isNotEmpty()
            ) { Text("Salvează") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

private fun stateLabel(state: String) = when (state) {
    "PLANNED" -> "planificată"
    "IN_PROGRESS" -> "în curs"
    "DONE" -> "finalizată"
    "PARTIAL" -> "parțial"
    "POSTPONED" -> "amânată"
    "ABANDONED" -> "abandonată"
    else -> state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddActivityDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onAddCategory: (String) -> Unit,
    onConfirm: (String, Long?, Int?) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var newCategory by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Activitate nouă") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Denumire") },
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory?.name ?: "fără categorie",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categorie") },
                        modifier = Modifier.menuAnchor(
                            MenuAnchorType.PrimaryNotEditable
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategory = category
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text("Categorie nouă") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onAddCategory(newCategory)
                            newCategory = ""
                        }
                    ) { Text("Adaugă") }
                }

                OutlinedTextField(
                    value = minutes,
                    onValueChange = { input -> minutes = input.filter { it.isDigit() } },
                    label = { Text("Durată estimată (minute)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(title, selectedCategory?.id, minutes.toIntOrNull())
                }
            ) { Text("Salvează") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

// NOU: afișează data ca „Vineri, 2 octombrie”
private val roFormatter =
    DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ro"))

private fun formatDate(iso: String): String =
    LocalDate.parse(iso).format(roFormatter).replaceFirstChar { it.uppercase() }

private val ro = Locale.forLanguageTag("ro")
private val dayFormatter = DateTimeFormatter.ofPattern("EEEE", ro)       // „sâmbătă”
private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", ro)    // „3 octombrie”