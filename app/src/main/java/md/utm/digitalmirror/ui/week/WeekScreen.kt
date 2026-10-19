package md.utm.digitalmirror.ui.week

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.WeekTask
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.WeekPlanner
import md.utm.digitalmirror.domain.WeekTaskParser
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate

private val MONTHS = listOf("ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")
private val DAY_NAMES = listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică")

private fun dayName(date: LocalDate) = DAY_NAMES[date.dayOfWeek.value - 1]
private fun shortDate(date: LocalDate) = "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"
private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

private fun blockLabel(b: md.utm.digitalmirror.domain.WeekScenario.Block): String {
    val day = dayName(b.date).replaceFirstChar { it.uppercase() }
    return when {
        b.wholeDay -> "$day: toată ziua ocupată"
        b.fromMinute <= 0 -> "$day: ocupat până la ${clock(b.toMinute)}"
        b.toMinute >= 24 * 60 -> "$day: ocupat după ${clock(b.fromMinute)}"
        else -> "$day: ocupat ${clock(b.fromMinute)}–${clock(b.toMinute)}"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(onBack: () -> Unit, onPlanTomorrow: () -> Unit, vm: WeekViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    var adding by remember { mutableStateOf(false) }
    var scenarioInput by rememberSaveable { mutableStateOf("") }
    var pinning by remember { mutableStateOf<WeekTask?>(null) }
    var deadlineFor by remember { mutableStateOf<WeekTask?>(null) }
    var deleting by remember { mutableStateOf<WeekTask?>(null) }
    var selectedDay by rememberSaveable { mutableIntStateOf(-1) }      // -1 = toate zilele

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Săptămâna") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } },
                actions = { TextButton(onClick = onPlanTomorrow) { Text("Planifică mâine") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- săptămâna și domeniul planului ---
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.setOffset(0) }, enabled = state.offset > 0) { Text("‹") }
                    Text(
                        "${shortDate(state.weekStart)} – ${shortDate(state.weekStart.plusDays(6))}" +
                                if (state.offset == 0) "  ·  săptămâna asta" else "  ·  săptămâna viitoare",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                    TextButton(onClick = { vm.setOffset(1) }, enabled = state.offset < 1) { Text("›") }
                }
            }
            if (state.offset == 0) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.scope == WeekViewModel.Scope.WEEK,
                            onClick = { vm.setScope(WeekViewModel.Scope.WEEK) },
                            label = { Text("Toată săptămâna") }
                        )
                        FilterChip(
                            selected = state.scope == WeekViewModel.Scope.TODAY,
                            onClick = { vm.setScope(WeekViewModel.Scope.TODAY) },
                            label = { Text("Doar azi") }
                        )
                    }
                }
                item {
                    Text(
                        if (state.scope == WeekViewModel.Scope.WEEK)
                            "Refac ce nu e început din azi până duminică; ce ai terminat rămâne."
                        else
                            "Refac doar ziua de azi; ce nu mai încape trece pe mâine, fără oră.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- șansele ---
            item { ChanceCard(state) }

            // --- grila zilelor ---
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayGrid(
                        days = state.days,
                        selected = selectedDay,
                        onSelect = { selectedDay = if (selectedDay == it) -1 else it }
                    )
                    Text(
                        "Bara arată cât din zi e plin: verde = e loc, galben = aproape plin, roșu = prea mult. " +
                                "Apasă o zi ca să vezi ce e planificat în ea.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // --- scenariu ---
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Simulează", style = MaterialTheme.typography.titleSmall)
                        OutlinedTextField(
                            value = scenarioInput,
                            onValueChange = { scenarioInput = it },
                            placeholder = { Text("vreau liber joi după 14") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.setScenario(scenarioInput) }, enabled = scenarioInput.isNotBlank()) {
                                Text("Simulează")
                            }
                            if (state.scenarioText.isNotBlank()) {
                                OutlinedButton(onClick = { scenarioInput = ""; vm.setScenario("") }) { Text("Șterge scenariul") }
                            }
                        }
                        state.scenarioBlocks.forEach { b ->
                            Text("• ${blockLabel(b)}", style = MaterialTheme.typography.bodySmall)
                        }
                        state.scenarioIgnored.forEach {
                            Text(
                                "Nu am înțeles: „$it”. Încearcă „liber joi după 14” sau „ocupat marți 10-12”.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (state.scenarioText.isNotBlank()) {
                            Text(
                                "Scenariul nu schimbă nimic până apeși „Redistribuie”.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // --- sarcini ---
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("De făcut săptămâna asta", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    FilledTonalButton(onClick = { adding = true }) { Text("+ Sarcină") }
                }
            }
            if (state.tasks.isEmpty()) {
                item {
                    Text(
                        "Adaugă ce ai de făcut, de exemplu „Lucru la licență, 6 ore” sau „Pregătire examen, 4 ore, până vineri”. " +
                                "Le împart eu pe zile, în sesiuni.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.tasks, key = { it.task.id }) { row ->
                TaskCard(
                    row = row,
                    onPin = { pinning = row.task },
                    onUnpin = { vm.setPinned(row.task, null) },
                    onDeadline = { deadlineFor = row.task },
                    onDelete = { deleting = row.task }
                )
            }

            // --- ce nu încape ---
            if (state.unplaced.isNotEmpty()) {
                item {
                    val titles = state.unplaced.groupBy { it.taskId }.map { (id, list) ->
                        val title = state.tasks.firstOrNull { it.task.id == id }?.task?.title ?: "?"
                        "$title (${formatDuration(list.sumOf { it.minutes })})"
                    }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("Nu încape în săptămână", fontWeight = FontWeight.SemiBold)
                            Text(titles.joinToString("; "), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Poți muta termenul, scurta sarcina sau elibera o zi.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // --- sesiunile propuse: toate zilele, sau doar ziua apăsată în grilă ---
            item {
                val cell = state.days.getOrNull(selectedDay)
                Text(
                    if (cell == null) "Sesiunile propuse"
                    else "${dayName(cell.date).replaceFirstChar { it.uppercase() }}, ${shortDate(cell.date)}",
                    style = MaterialTheme.typography.titleMedium
                )
                if (cell != null) {
                    Text(
                        when {
                            cell.dayOff -> "Zi liberă: nu planific nimic în ea."
                            cell.budget <= 0 -> "Planificat ${formatDuration(cell.minutes)}."
                            else -> "Planificat ${formatDuration(cell.minutes)} din cât reușești de obicei (${formatDuration(cell.budget)})."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val shown = state.sessions.filter { selectedDay < 0 || it.date == state.days.getOrNull(selectedDay)?.date }
            if (shown.isEmpty()) {
                item {
                    Text(
                        if (selectedDay < 0) "Nicio sesiune propusă încă. Adaugă o sarcină mai sus."
                        else "Nicio sesiune de la sarcinile săptămânii în ziua asta.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val byDay = shown.groupBy { it.date }.toSortedMap()
                byDay.forEach { (date, list) ->
                    item(key = "day-$date") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (selectedDay < 0) {
                                    Text(
                                        "${dayName(date).replaceFirstChar { it.uppercase() }}, ${shortDate(date)}",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                list.forEach { s ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            if (s.startMinute >= 0) "${clock(s.startMinute)}–${clock(s.endMinute)}" else "fără oră",
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(96.dp)
                                        )
                                        Text(s.title + if (s.pinned) "  📌" else "", modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // --- aplicare ---
            item {
                Button(
                    onClick = {
                        vm.apply { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                    },
                    enabled = state.notApplied,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Redistribuie automat pe zile") }
                Text(
                    when {
                        state.tasks.isEmpty() -> "Butonul se activează după ce adaugi o sarcină."
                        state.notApplied -> "Propunerea de mai sus nu e încă scrisă în zilele tale."
                        else -> "Planul de mai sus este deja în zilele tale."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }

    if (adding) {
        NewTaskDialog(
            weekStart = state.weekStart,
            onDismiss = { adding = false },
            onSave = { title, minutes, session, deadline ->
                vm.addTask(title, minutes, session, deadline)
                adding = false
            }
        )
    }

    pinning?.let { task ->
        DayPickerDialog(
            title = "Fixează „${task.title}” pe o zi",
            weekStart = state.weekStart,
            allowNone = false,
            onPick = { vm.setPinned(task, it); pinning = null },
            onDismiss = { pinning = null }
        )
    }

    deadlineFor?.let { task ->
        DayPickerDialog(
            title = "Termen pentru „${task.title}”",
            weekStart = state.weekStart,
            allowNone = true,
            onPick = { vm.setDeadline(task, it); deadlineFor = null },
            onDismiss = { deadlineFor = null }
        )
    }

    deleting?.let { task ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Ștergi „${task.title}”?") },
            text = { Text("Sesiunile neîncepute din plan se șterg. Cele deja făcute rămân în istoric.") },
            confirmButton = { TextButton(onClick = { vm.deleteTask(task); deleting = null }) { Text("Șterge") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anulează") } }
        )
    }
}

@Composable
private fun ChanceCard(state: WeekViewModel.UiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val chance = state.chancePercent
            if (chance == null) {
                Text("Șanse să termini săptămâna", style = MaterialTheme.typography.titleSmall)
                Text("Apar după ce adaugi o sarcină.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Șanse să termini săptămâna", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text("$chance%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                LinearProgressIndicator(progress = { chance / 100f }, modifier = Modifier.fillMaxWidth())
                state.riskyDay?.let {
                    Text("${dayName(it).replaceFirstChar { c -> c.uppercase() }} e ziua riscantă: e mai încărcată decât reușești de obicei.")
                }
                Text(
                    if (state.analysisOn)
                        "Estimare simplă: ia în calcul cât ai reușit în ultimele săptămâni și cât ai de obicei într-o zi. Nu e o promisiune."
                    else
                        "Analiza e oprită, deci folosesc valori generale (75% reușită, 5 h pe zi lucrătoare).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayGrid(days: List<WeekViewModel.DayCell>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        days.forEachIndexed { index, cell ->
            val ratio = if (cell.budget <= 0) 1f else (cell.minutes.toFloat() / cell.budget)
            val color = when {
                cell.dayOff -> MaterialTheme.colorScheme.outlineVariant
                ratio > 1.15f -> MaterialTheme.colorScheme.error
                ratio > 0.85f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.primary
            }
            Column(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (index == selected) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(Availability.shortDayName(index), style = MaterialTheme.typography.labelMedium)
                Text("${cell.date.dayOfMonth}", style = MaterialTheme.typography.bodySmall)
                Box(
                    Modifier.fillMaxWidth().height(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        Modifier.fillMaxWidth().fillMaxHeight(ratio.coerceIn(0.04f, 1f))
                            .background(color)
                    )
                }
                Text(
                    if (cell.dayOff) "liberă" else if (cell.minutes == 0) "–" else formatDuration(cell.minutes),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    row: WeekViewModel.TaskRow,
    onPin: () -> Unit,
    onUnpin: () -> Unit,
    onDeadline: () -> Unit,
    onDelete: () -> Unit
) {
    val task = row.task
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Box {
                    TextButton(onClick = { menu = true }) { Text("⋮") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (task.pinnedDate != null) "Nu mai fixa" else "Fixează pe o zi") },
                            onClick = { menu = false; if (task.pinnedDate != null) onUnpin() else onPin() }
                        )
                        DropdownMenuItem(text = { Text("Schimbă termenul") }, onClick = { menu = false; onDeadline() })
                        DropdownMenuItem(text = { Text("Șterge") }, onClick = { menu = false; onDelete() })
                    }
                }
            }

            val result = row.result
            val dates = result?.dates.orEmpty()
            Text(
                buildString {
                    append(formatDuration(task.totalMinutes))
                    append(" · sesiuni de ").append(formatDuration(task.sessionMinutes))
                    if (row.doneMinutes > 0) append(" · făcut ").append(formatDuration(row.doneMinutes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (result == null) {
                Text("Terminată ✓", color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    "Distribuite automat: " + (dates.joinToString(", ") { Availability.shortDayName(it.dayOfWeek.value - 1) }.ifEmpty { "nicio zi" }) +
                            " · ${formatDuration(result.plannedMinutes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (result.unplacedMinutes > 0) {
                    Text(
                        "${formatDuration(result.unplacedMinutes)} nu încap",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                task.pinnedDate?.let {
                    AssistChip(onClick = onUnpin, label = { Text("fixată ${dayName(LocalDate.parse(it))}, nu se mută") })
                }
                task.deadline?.let {
                    AssistChip(onClick = onDeadline, label = { Text("termen ${dayName(LocalDate.parse(it))}") })
                }
                if (result != null && task.deadline != null) {
                    val (label, color) = when (result.risk) {
                        WeekPlanner.Risk.HIGH -> "risc mare" to MaterialTheme.colorScheme.error
                        WeekPlanner.Risk.MEDIUM -> "risc mediu" to MaterialTheme.colorScheme.tertiary
                        WeekPlanner.Risk.LOW -> "risc mic" to MaterialTheme.colorScheme.primary
                    }
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewTaskDialog(
    weekStart: LocalDate,
    onDismiss: () -> Unit,
    onSave: (String, Int, Int, LocalDate?) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    var sessionOverride by rememberSaveable { mutableIntStateOf(0) }        // 0 = din text sau 90 min
    var deadlineOverride by rememberSaveable { mutableIntStateOf(-2) }      // -2 = din text, -1 = fără termen

    val parsed = remember(text) { WeekTaskParser.parse(text) }
    val title = parsed.title
    val minutes = parsed.totalMinutes ?: 0
    val session = if (sessionOverride > 0) sessionOverride else parsed.sessionMinutes ?: 90
    val deadlineIndex = if (deadlineOverride != -2) deadlineOverride else parsed.deadlineDay ?: -1
    val valid = title != null && minutes >= 15

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sarcină nouă") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Ce ai de făcut?") },
                    placeholder = { Text("Lucru la licență 6h, până vineri") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Scrie liber: titlul, cât durează în total și, dacă vrei, „sesiuni de 90 min” și „până vineri”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // ce am înțeles, ca să se vadă înainte de salvare
                if (text.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(Modifier.padding(10.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title ?: "Titlul lipsește", fontWeight = FontWeight.SemiBold)
                            Text(
                                if (minutes > 0) "${formatDuration(minutes)} în total" else "Cât durează în total? Scrie de exemplu 6h",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (minutes > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.error
                            )
                            Text("Sesiuni de ${formatDuration(session)}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (deadlineIndex >= 0) "Termen: ${DAY_NAMES[deadlineIndex]}" else "Fără termen",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Text("Schimbă sesiunea", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(30, 60, 90, 120) + session).distinct().sorted().forEach { m ->
                        FilterChip(selected = session == m, onClick = { sessionOverride = m }, label = { Text(formatDuration(m)) })
                    }
                }
                Text("Schimbă termenul", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = deadlineIndex == -1, onClick = { deadlineOverride = -1 }, label = { Text("Fără") })
                    (0..6).forEach { i ->
                        FilterChip(
                            selected = deadlineIndex == i, onClick = { deadlineOverride = i },
                            label = { Text(Availability.shortDayName(i)) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(title!!, minutes, session, if (deadlineIndex >= 0) weekStart.plusDays(deadlineIndex.toLong()) else null)
                }
            ) { Text("Adaugă") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    )
}

@Composable
private fun DayPickerDialog(
    title: String,
    weekStart: LocalDate,
    allowNone: Boolean,
    onPick: (LocalDate?) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (0..6).forEach { i ->
                    val date = weekStart.plusDays(i.toLong())
                    TextButton(onClick = { onPick(date) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${dayName(date).replaceFirstChar { it.uppercase() }}, ${shortDate(date)}")
                    }
                }
                if (allowNone) {
                    TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("Fără termen") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    )
}
