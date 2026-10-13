package md.utm.digitalmirror.ui.goals

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.Goal
import md.utm.digitalmirror.domain.GoalProgress
import md.utm.digitalmirror.ui.components.DurationField
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsScreen(vm: GoalsViewModel = viewModel()) {
    val cards by vm.cards.collectAsState()
    val categories by vm.categories.collectAsState()

    // null = dialogul e închis; Goal cu id 0 = obiectiv nou
    var editing by remember { mutableStateOf<GoalsViewModel.GoalCard?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Goal?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Obiective") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("+") }
        }
    ) { padding ->
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Niciun obiectiv încă. Adaugă unul, de exemplu „Teza gata până pe 15 mai”, " +
                            "și îl legi de categoriile în care lucrezi la el.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cards, key = { it.goal.id }) { card ->
                    GoalCardView(
                        card = card,
                        onEdit = { editing = card },
                        onDelete = { deleting = card.goal },
                        onSetState = { vm.setState(card.goal, it) },
                        onToggleStep = vm::toggleStep,
                        onDeleteStep = vm::deleteStep,
                        onAddStep = { vm.addStep(card.goal.id, it, card.steps.size) }
                    )
                }
            }
        }
    }

    if (creating) {
        GoalDialog(
            initial = null,
            categories = categories,
            onDismiss = { creating = false },
            onSave = { goal, ids ->
                vm.save(goal, ids)
                creating = false
            }
        )
    }

    editing?.let { card ->
        GoalDialog(
            initial = card,
            categories = categories,
            onDismiss = { editing = null },
            onSave = { goal, ids ->
                vm.save(goal, ids)
                editing = null
            }
        )
    }

    deleting?.let { goal ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Ștergi obiectivul?") },
            text = { Text("„${goal.title}” și etapele lui se șterg. Activitățile tale rămân.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(goal); deleting = null }) {
                    Text("Șterge", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anulează") } }
        )
    }
}

@Composable
private fun GoalCardView(
    card: GoalsViewModel.GoalCard,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetState: (String) -> Unit,
    onToggleStep: (md.utm.digitalmirror.data.entity.GoalStep) -> Unit,
    onDeleteStep: (md.utm.digitalmirror.data.entity.GoalStep) -> Unit,
    onAddStep: (String) -> Unit
) {
    val goal = card.goal
    val r = card.result
    val active = goal.state == Goal.STATE_ACTIVE

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {

            // titlu, termen și meniul ⋮
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(goal.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        countdown(goal, r),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                GoalMenu(goal, onEdit, onDelete, onSetState)
            }

            // progresul
            LinearProgressIndicator(
                progress = { r.percent / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${r.percent}%" + progressDetail(goal, r),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (active) {
                paceText(r)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                forecastText(goal, r)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                momentumText(r)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }

            if (card.categoryNames.isNotEmpty()) {
                Text(
                    "Categorii: " + card.categoryNames.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Steps(card, onToggleStep, onDeleteStep, onAddStep)
        }
    }
}

@Composable
private fun GoalMenu(
    goal: Goal,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetState: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Editează") }, onClick = { open = false; onEdit() })
            if (goal.state == Goal.STATE_ACTIVE) {
                DropdownMenuItem(
                    text = { Text("Marchează ca atins") },
                    onClick = { open = false; onSetState(Goal.STATE_DONE) }
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Redeschide") },
                    onClick = { open = false; onSetState(Goal.STATE_ACTIVE) }
                )
            }
            DropdownMenuItem(
                text = { Text("Șterge", color = MaterialTheme.colorScheme.error) },
                onClick = { open = false; onDelete() }
            )
        }
    }
}

@Composable
private fun Steps(
    card: GoalsViewModel.GoalCard,
    onToggle: (md.utm.digitalmirror.data.entity.GoalStep) -> Unit,
    onDelete: (md.utm.digitalmirror.data.entity.GoalStep) -> Unit,
    onAdd: (String) -> Unit
) {
    var newStep by remember { mutableStateOf("") }

    if (card.steps.isNotEmpty()) {
        Text(
            "Etape (${card.steps.count { it.done }} din ${card.steps.size})",
            style = MaterialTheme.typography.labelLarge
        )
    }
    card.steps.forEach { step ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = step.done, onCheckedChange = { onToggle(step) })
            Text(
                step.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (step.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
            TextButton(onClick = { onDelete(step) }) { Text("✕") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newStep,
            onValueChange = { newStep = it },
            placeholder = { Text("Adaugă o etapă") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            enabled = newStep.isNotBlank(),
            onClick = { onAdd(newStep); newStep = "" }
        ) { Text("Adaugă") }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun GoalDialog(
    initial: GoalsViewModel.GoalCard?,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Goal, Set<Long>) -> Unit
) {
    val base = initial?.goal
    var title by remember { mutableStateOf(base?.title ?: "") }
    var deadline by remember { mutableStateOf(base?.deadline) }
    var targetMinutes by remember { mutableStateOf(base?.targetMinutes) }
    var targetActivities by remember { mutableStateOf(base?.targetActivities?.toString() ?: "") }
    var selected by remember { mutableStateOf(initial?.categoryIds ?: emptySet()) }
    var pickingDate by remember { mutableStateOf(false) }

    val activities = targetActivities.toIntOrNull()?.takeIf { it > 0 }
    val minutes = targetMinutes?.takeIf { it > 0 }
    val valid = title.isNotBlank() && (minutes != null || activities != null) && selected.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (base == null) "Obiectiv nou" else "Editează obiectivul") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Obiectiv (de ex. Teza gata)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(deadline?.let { "Termen: ${formatDate(it)}" } ?: "Alege un termen (opțional)")
                }
                if (deadline != null) {
                    TextButton(onClick = { deadline = null }) { Text("Scoate termenul") }
                }

                Text("Cât vrei să faci? Poți alege una sau ambele.", style = MaterialTheme.typography.labelLarge)
                DurationField(
                    label = "Timp de lucru (opțional)",
                    minutes = targetMinutes,
                    onChange = { targetMinutes = it },
                    showQuickValues = false
                )
                OutlinedTextField(
                    value = targetActivities,
                    onValueChange = { targetActivities = it.filter(Char::isDigit).take(4) },
                    label = { Text("Activități terminate (opțional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Din ce categorii se adună progresul?", style = MaterialTheme.typography.labelLarge)
                if (categories.isEmpty()) {
                    Text("Nu ai încă categorii; adaugă-le din Setări.", style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = c.id in selected,
                            onClick = { selected = if (c.id in selected) selected - c.id else selected + c.id },
                            label = { Text(c.name) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val goal = base?.copy(
                        title = title.trim(),
                        deadline = deadline,
                        targetMinutes = minutes,
                        targetActivities = activities
                    ) ?: Goal(
                        title = title.trim(),
                        deadline = deadline,
                        targetMinutes = minutes,
                        targetActivities = activities,
                        startDate = LocalDate.now().toString(),
                        createdAt = System.currentTimeMillis()
                    )
                    onSave(goal, selected)
                }
            ) { Text("Salvează") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    )

    if (pickingDate) {
        DeadlinePicker(
            onDismiss = { pickingDate = false },
            onConfirm = { deadline = it; pickingDate = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlinePicker(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val todayMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
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
                    state.selectedDateMillis?.let {
                        onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                }
            ) { Text("Alege") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    ) { DatePicker(state = state) }
}

// ---- texte ----

private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ro"))

private fun formatDate(iso: String): String = LocalDate.parse(iso).format(dateFormatter)

private fun countdown(goal: Goal, r: GoalProgress.Result): String {
    if (goal.state == Goal.STATE_DONE) return "Atins"
    val days = r.daysLeft ?: return "Fără termen"
    val date = formatDate(goal.deadline!!)
    return when {
        days > 1 -> "$days zile rămase · până pe $date"
        days == 1 -> "1 zi rămasă · până pe $date"
        days == 0 -> "Astăzi e termenul · $date"
        else -> "Termen depășit cu ${-days} ${if (-days == 1) "zi" else "zile"} · $date"
    }
}

private fun progressDetail(goal: Goal, r: GoalProgress.Result): String = buildList {
    goal.targetMinutes?.takeIf { it > 0 }?.let { add("${formatDuration(r.minutesDone)} din ${formatDuration(it)}") }
    goal.targetActivities?.takeIf { it > 0 }?.let { add("${r.activitiesDone} din $it activități") }
}.let { if (it.isEmpty()) "" else " · " + it.joinToString(" · ") }

private fun paceText(r: GoalProgress.Result): String? = when (r.pace) {
    GoalProgress.Pace.AHEAD -> "Ești înaintea ritmului: acum ar fi fost ${r.expectedPercent}% la un ritm egal."
    GoalProgress.Pace.ON_TRACK -> "Ești pe drumul bun (ritm egal: ${r.expectedPercent}%)."
    GoalProgress.Pace.BEHIND -> "Ești în urma ritmului: la un ritm egal ar fi trebuit să fii la ${r.expectedPercent}%."
    GoalProgress.Pace.OVERDUE -> "Termenul a trecut."
    else -> null
}

private fun forecastText(goal: Goal, r: GoalProgress.Result): String? {
    val forecast = r.forecast ?: return null
    val text = "La ritmul din ultimele zile termini pe ${forecast.format(dateFormatter)}"
    val deadline = goal.deadline?.let { LocalDate.parse(it) } ?: return "$text."
    return if (forecast.isAfter(deadline)) "$text, după termen." else "$text, la timp."
}

private fun momentumText(r: GoalProgress.Result): String? = when (r.momentum) {
    GoalProgress.Momentum.STAGNANT ->
        r.daysSinceLast?.let { "Nicio activitate la acest obiectiv de $it zile." }
            ?: "Încă nicio activitate la acest obiectiv."
    GoalProgress.Momentum.SLOWING -> "Ritmul a scăzut față de săptămânile trecute."
    else -> null
}