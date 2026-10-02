package md.utm.digitalmirror.ui.closing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Activity

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
    val tracked by vm.tracked.collectAsState()
    val reflection by vm.reflection.collectAsState()

    val open = activities.filter { it.state !in CLOSED_STATES }
    val closed = activities.filter { it.state in CLOSED_STATES }

    // câmpurile reflecției, precompletate dacă reflecția există deja
    var mood by remember(reflection) { mutableStateOf(reflection?.moodScore ?: 0) }
    var workedOn by remember(reflection) { mutableStateOf(reflection?.workedOn ?: "") }
    var blockers by remember(reflection) { mutableStateOf(reflection?.blockers ?: "") }
    var note by remember(reflection) { mutableStateOf(reflection?.note ?: "") }

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

            // 1. activități de confirmat
            item { SectionTitle("De confirmat (${open.size})") }

            if (open.isEmpty()) {
                item { Hint("Toate activitățile zilei au o stare.") }
            }

            items(open, key = { it.id }) { activity ->
                ResolveCard(
                    activity = activity,
                    initialMinutes = tracked[activity.id] ?: activity.actualMinutes ?: 0,
                    onSave = { minutes, state -> vm.resolveActivity(activity, minutes, state) }
                )
            }

            // 2. rezumatul activităților închise
            if (closed.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val est = closed.sumOf { it.userEstimatedMinutes ?: 0 }
                        val real = closed.sumOf { it.actualMinutes ?: 0 }
                        SectionTitle("Închise (${closed.size})")
                        Hint("Estimat $est min · real $real min")
                    }
                }
                items(closed, key = { it.id }) { activity ->
                    Text(
                        "• ${activity.title}: ${labelOf(activity.state)}, ${activity.actualMinutes ?: 0} min",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // 3. reflecția zilei
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.height(8.dp))
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
                        onClick = { vm.saveReflection(mood, workedOn, blockers, note, onDone = onBack) },
                        enabled = mood > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (reflection == null) "Salvează reflecția" else "Actualizează reflecția")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResolveCard(
    activity: Activity,
    initialMinutes: Int,
    onSave: (Int, String) -> Unit
) {
    var minutes by remember(activity.id, initialMinutes) { mutableStateOf(initialMinutes.toString()) }
    var state by remember(activity.id) { mutableStateOf("DONE") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(activity.title, fontWeight = FontWeight.Medium)
            activity.userEstimatedMinutes?.let { Hint("estimat $it min") }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                STATE_OPTIONS.forEach { (value, label) ->
                    FilterChip(
                        selected = state == value,
                        onClick = { state = value },
                        label = { Text(label) }
                    )
                }
            }

            OutlinedTextField(
                value = minutes,
                onValueChange = { input -> minutes = input.filter { it.isDigit() } },
                label = { Text("Durata reală (minute)") },
                singleLine = true
            )

            Button(onClick = { onSave(minutes.toIntOrNull() ?: 0, state) }) {
                Text("Confirmă")
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