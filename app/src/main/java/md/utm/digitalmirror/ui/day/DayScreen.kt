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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
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
    var editing by remember { mutableStateOf<Activity?>(null) }      // activitatea în editare
    var postponing by remember { mutableStateOf<Activity?>(null) }   // activitatea pentru care alegem o zi

    LaunchedEffect(Unit) { vm.refreshRunning() }

    val isToday = date == LocalDate.now().toString()

    Scaffold(
        // bara de sus cu navigare între zile
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
        // butonul de închidere, doar pentru azi și zilele trecute
        bottomBar = {
            if (date <= LocalDate.now().toString()) {
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
                // sortate după ora de start, cele fără oră la final
                items(
                    activities.sortedWith(compareBy(nullsLast()) { it.plannedStartMinute }),
                    key = { it.id }
                ) { activity ->
                    ActivityRow(
                        activity = activity,
                        categoryName = categories.find { it.id == activity.categoryId }?.name,
                        runningSince = running?.takeIf { it.activityId == activity.id }?.startedAt,
                        offsetMillis = runningOffset,
                        onToggleTimer = { vm.toggleTimer(activity) },
                        onFinish = { vm.requestFinish(activity) },
                        onEdit = { editing = activity },
                        onPostponeTomorrow = {
                            val tomorrow = LocalDate.parse(activity.date).plusDays(1).toString()
                            vm.postponeActivity(activity, tomorrow)
                        },
                        onPostponePick = { postponing = activity },
                        onDelete = { vm.deleteActivity(activity) }
                    )
                }
            }
        }
    }

    // adăugare
    if (showAddDialog) {
        ActivityDialog(
            initial = null,
            categories = categories,
            onDismiss = { showAddDialog = false },
            onAddCategory = { vm.addCategory(it) },
            onConfirm = { title, categoryId, minutes, start ->
                vm.addActivity(title, categoryId, minutes, start)
                showAddDialog = false
            }
        )
    }

    // editare
    editing?.let { activity ->
        ActivityDialog(
            initial = activity,
            categories = categories,
            onDismiss = { editing = null },
            onAddCategory = { vm.addCategory(it) },
            onConfirm = { title, categoryId, minutes, start ->
                vm.editActivity(activity.id, title, categoryId, minutes, start)
                editing = null
            }
        )
    }

    // amânare pe o zi aleasă
    postponing?.let { activity ->
        PostponeDatePicker(
            fromDate = activity.date,
            onDismiss = { postponing = null },
            onConfirm = { newDate ->
                vm.postponeActivity(activity, newDate)
                postponing = null
            }
        )
    }

    // finalizare
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
    offsetMillis: Long,
    onToggleTimer: () -> Unit,
    onFinish: () -> Unit,
    onEdit: () -> Unit,
    onPostponeTomorrow: () -> Unit,
    onPostponePick: () -> Unit,
    onDelete: () -> Unit
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
                activity.plannedStartMinute?.let { start ->
                    val end = activity.userEstimatedMinutes?.let { start + it }
                    add(if (end != null) "${formatMinute(start)}–${formatMinute(end)}" else formatMinute(start))
                }
                categoryName?.let { add(it) }
                activity.userEstimatedMinutes?.let { add("estimat ${it}m") }
                if (!isRunning) activity.actualMinutes?.let { add("real ${it}m") }
                add(stateLabel(activity.state))
                postponedLabel(activity.postponedCount)?.let { add(it) }
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

            // acțiunile principale la stânga, meniul ⋮ la dreapta
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val compact = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

                if (!isClosed) {
                    OutlinedButton(onClick = onToggleTimer, contentPadding = compact) {
                        Text(
                            when {
                                isRunning -> "Pauză"
                                activity.state == "IN_PROGRESS" -> "Continuă"
                                else -> "Pornește"
                            },
                            maxLines = 1
                        )
                    }
                    OutlinedButton(onClick = onFinish, contentPadding = compact) {
                        Text("Finalizează", maxLines = 1)
                    }
                }

                Spacer(Modifier.weight(1f))

                Box {
                    var menuOpen by remember { mutableStateOf(false) }

                    TextButton(onClick = { menuOpen = true }, contentPadding = compact) {
                        Text("⋮", style = MaterialTheme.typography.titleMedium)
                    }

                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Editează") },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        if (!isClosed) {
                            DropdownMenuItem(
                                text = { Text("Amână pe mâine") },
                                onClick = { menuOpen = false; onPostponeTomorrow() }
                            )
                            DropdownMenuItem(
                                text = { Text("Amână pe altă zi…") },
                                onClick = { menuOpen = false; onPostponePick() }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Șterge", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
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
                    Text("Estimat $it min · cronometrat ${draft.trackedMinutes} min")
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

// formular comun pentru adăugare (initial = null) și editare (initial = activitatea)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDialog(
    initial: Activity?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onAddCategory: (String) -> Unit,
    onConfirm: (String, Long?, Int?, Int?) -> Unit
) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var minutes by remember { mutableStateOf(initial?.userEstimatedMinutes?.toString() ?: "") }
    var selectedCategory by remember {
        mutableStateOf(categories.find { it.id == initial?.categoryId })
    }
    var newCategory by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var startMinute by remember { mutableStateOf(initial?.plannedStartMinute) }
    var showTimePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Activitate nouă" else "Editează activitatea") },
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
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("fără categorie") },
                            onClick = {
                                selectedCategory = null
                                expanded = false
                            }
                        )
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

                // ora de start (opțională)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Text(startMinute?.let { "Ora: ${formatMinute(it)}" } ?: "Alege ora de start")
                    }
                    if (startMinute != null) {
                        TextButton(onClick = { startMinute = null }) { Text("Fără oră") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(title, selectedCategory?.id, minutes.toIntOrNull(), startMinute)
                },
                enabled = title.isNotBlank()
            ) { Text("Salvează") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )

    if (showTimePicker) {
        TimePickerDialog(
            initialMinute = startMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = {
                startMinute = it
                showTimePicker = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinute: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val now = LocalTime.now()
    val state = rememberTimePickerState(
        initialHour = initialMinute?.div(60) ?: now.hour,
        initialMinute = initialMinute?.rem(60) ?: 0,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ora de start") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

// calendar pentru amânare: se pot alege doar zile de după data activității
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostponeDatePicker(
    fromDate: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val minDate = LocalDate.parse(fromDate).plusDays(1)
    val minMillis = minDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    val state = rememberDatePickerState(
        initialSelectedDateMillis = minMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= minMillis
            override fun isSelectableYear(year: Int) = year >= minDate.year
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(picked.toString())
                    }
                },
                enabled = state.selectedDateMillis != null
            ) { Text("Amână") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    ) {
        DatePicker(state = state)
    }
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

private fun postponedLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "amânată o dată"
    else -> "amânată de $count ori"
}

// formatare dată și oră
private val ro = Locale.forLanguageTag("ro")
private val dayFormatter = DateTimeFormatter.ofPattern("EEEE", ro)       // „sâmbătă”
private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", ro)    // „3 octombrie”

private fun formatMinute(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)