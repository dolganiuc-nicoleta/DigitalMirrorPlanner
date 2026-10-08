package md.utm.digitalmirror.ui.closing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.ui.components.DurationField
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate

private val CLOSED_STATES = setOf("DONE", "PARTIAL", "POSTPONED", "ABANDONED")

private val STATE_OPTIONS = listOf(
    "DONE" to "Finalizată",
    "PARTIAL" to "Parțial",
    "POSTPONED" to "Amânată",
    "ABANDONED" to "Abandonată"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosingScreen(
    date: String,
    onBack: () -> Unit,
    vm: ClosingViewModel = viewModel()
) {
    LaunchedEffect(date) { vm.load(date) }

    val activities by vm.activities.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val reflection by vm.reflection.collectAsState()
    val daily by vm.daily.collectAsState()

    val closed = activities.filter { it.state in CLOSED_STATES }

    // câmpurile reflecției, precompletate dacă reflecția există deja
    var mood by remember(reflection) { mutableStateOf(reflection?.moodScore ?: 0) }
    var workedOn by remember(reflection) { mutableStateOf(reflection?.workedOn ?: "") }
    var blockers by remember(reflection) { mutableStateOf(reflection?.blockers ?: "") }
    var note by remember(reflection) { mutableStateOf(reflection?.note ?: "") }

    // somnul, precompletat din Health Connect sau din ce a introdus utilizatorul anterior
    var bedtime by remember(daily) { mutableStateOf(daily?.bedtimeMinute) }
    var wake by remember(daily) { mutableStateOf(daily?.wakeMinute) }
    var sleepEdited by remember(daily) { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }   // "bed" sau "wake"

    val isToday = date == LocalDate.now().toString()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                title = { Text("Închiderea zilei") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // 1. activități de confirmat, precompletate
            item { SectionTitle("De confirmat (${drafts.size})") }

            if (drafts.isEmpty()) {
                item { Hint("Toate activitățile zilei au o stare.") }
            } else {
                item { Hint("Am completat ce știu din cronometru și din plan. Corectează doar ce nu e bine.") }
            }

            items(drafts, key = { it.activity.id }) { draft ->
                DraftCard(
                    draft = draft,
                    onState = { vm.changeState(draft, it) },
                    onMinutes = { vm.changeMinutes(draft, it) }
                )
            }

            if (drafts.isNotEmpty()) {
                item {
                    // toate activitățile finalizate sau parțiale trebuie să aibă o durată
                    val ready = drafts.all { it.state !in setOf("DONE", "PARTIAL") || it.minutes != null }
                    Button(
                        onClick = { vm.confirmAll() },
                        enabled = ready,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (drafts.size == 1) "Totul corect · confirmă" else "Totul corect · confirmă ${drafts.size}")
                    }
                }
            }

            // 2. rezumatul activităților închise
            if (closed.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val est = closed.sumOf { it.userEstimatedMinutes ?: 0 }
                        val real = closed.sumOf { it.actualMinutes ?: 0 }
                        SectionTitle("Închise (${closed.size})")
                        Hint("Planificat ${formatDuration(est)} · real ${formatDuration(real)}")
                    }
                }
                items(closed, key = { it.id }) { activity ->
                    Text(
                        "• ${activity.title}: ${labelOf(activity.state)}, ${formatDuration(activity.actualMinutes ?: 0)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 3. somnul
            item {
                SleepCard(
                    title = if (isToday) "Somnul de azi-noapte" else "Somnul din noaptea dinainte",
                    bedtime = bedtime,
                    wake = wake,
                    sourceHint = when {
                        sleepEdited -> null
                        daily?.sleepSource == "HEALTH" -> "din Health Connect · poți corecta"
                        daily?.sleepSource == "MANUAL" -> "introdus de tine"
                        else -> null
                    },
                    onPickBedtime = { picking = "bed" },
                    onPickWake = { picking = "wake" }
                )
            }

            // 4. reflecția zilei
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(4.dp))
                    SectionTitle("Reflecția zilei")
                    Hint("Cum a fost ziua? 1 = foarte grea, 5 = foarte bună")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { value ->
                            FilterChip(
                                selected = mood == value,
                                onClick = { mood = value },
                                label = { Text("$value") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = workedOn,
                        onValueChange = { workedOn = it },
                        label = { Text("La ce am lucrat") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = blockers,
                        onValueChange = { blockers = it },
                        label = { Text("Ce m-a blocat") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Notă liberă") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = {
                            vm.save(
                                mood, workedOn, blockers, note,
                                bedtime, wake, sleepEdited,
                                onDone = onBack
                            )
                        },
                        enabled = mood > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (reflection == null) "Salvează reflecția" else "Actualizează reflecția")
                    }
                }
            }
        }
    }

    // alegerea orei de culcare / trezire
    picking?.let { which ->
        SleepTimeDialog(
            title = if (which == "bed") "Ora de culcare" else "Ora de trezire",
            initialMinute = if (which == "bed") bedtime ?: 23 * 60 else wake ?: 7 * 60,
            onDismiss = { picking = null },
            onConfirm = { minute ->
                if (which == "bed") bedtime = minute else wake = minute
                sleepEdited = true
                picking = null
            }
        )
    }
}

@Composable
private fun SleepCard(
    title: String,
    bedtime: Int?,
    wake: Int?,
    sourceHint: String?,
    onPickBedtime: () -> Unit,
    onPickWake: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, fontWeight = FontWeight.Medium)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onPickBedtime) {
                    Text("😴 ${bedtime?.let { formatMinute(it) } ?: "culcare"}")
                }
                Text("→")
                OutlinedButton(onClick = onPickWake) {
                    Text("☀️ ${wake?.let { formatMinute(it) } ?: "trezire"}")
                }
            }

            if (bedtime != null && wake != null) {
                val minutes = ClosingViewModel.sleepDuration(bedtime, wake)
                Text(
                    "Ai dormit ${formatDuration(minutes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Hint("Opțional · ajută la recomandări")
            }

            sourceHint?.let { Hint(it) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimeDialog(
    title: String,
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

// o activitate de confirmat: starea și durata propuse, cu posibilitatea de a le corecta
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DraftCard(
    draft: ClosingViewModel.Draft,
    onState: (String) -> Unit,
    onMinutes: (Int?) -> Unit
) {
    var editing by remember(draft.activity.id) { mutableStateOf(false) }
    val needsDuration = draft.state == "DONE" || draft.state == "PARTIAL"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(draft.activity.title, fontWeight = FontWeight.Medium)

            val planned = draft.activity.userEstimatedMinutes ?: draft.activity.estimatedMinutes
            Hint(
                buildList {
                    planned?.let { add("planificat ${formatDuration(it)}") }
                    add(
                        if (draft.trackedMinutes > 0) "cronometrat ${formatDuration(draft.trackedMinutes)}"
                        else "fără cronometru"
                    )
                }.joinToString(" · ")
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STATE_OPTIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = draft.state == value,
                        onClick = { onState(value) },
                        label = { Text(label) }
                    )
                }
            }

            if (needsDuration) {
                if (editing || draft.minutes == null) {
                    DurationField(
                        label = "Durata reală",
                        minutes = draft.minutes,
                        onChange = onMinutes,
                        showQuickValues = false
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Durata reală: ${formatDuration(draft.minutes)}")
                            when (draft.source) {
                                "AS_PLANNED" -> Hint("copiată din plan · corectează dacă a durat altfel")
                                "TIMER" -> Hint("din cronometru")
                                "MANUAL" -> Hint("corectată de tine")
                            }
                        }
                        TextButton(onClick = { editing = true }) { Text("Modifică") }
                    }
                }
            } else if (draft.state == "POSTPONED") {
                Hint("se mută pe mâine")
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Hint(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

private fun labelOf(state: String) =
    STATE_OPTIONS.firstOrNull { it.first == state }?.second ?: state

private fun formatMinute(m: Int): String = "%02d:%02d".format(m / 60, m % 60)