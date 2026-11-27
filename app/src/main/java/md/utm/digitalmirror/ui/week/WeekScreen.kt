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
import md.utm.digitalmirror.ui.theme.Fraunces
import md.utm.digitalmirror.data.ai.AiPrefs
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import java.time.LocalDate

/** Un card cu titlu care se deschide la apăsare; ține ecranul curat. */
@Composable
private fun Collapsible(
    title: String,
    subtitle: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (!expanded) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            if (expanded) content()
        }
    }
}

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
fun WeekScreen(
    onBack: (() -> Unit)?,
    onPlanTomorrow: () -> Unit,
    startAdding: Boolean = false,
    onStartAddingShown: () -> Unit = {},
    onOpenDay: (LocalDate) -> Unit = {},
    onOpenActivity: (Long) -> Unit = {},
    onEditActivity: (Long, LocalDate) -> Unit = { _, _ -> },
    vm: WeekViewModel = viewModel()
) {
    val state by vm.state.collectAsState()
    val view by vm.view.collectAsState()
    val selectedDate by vm.selectedDate.collectAsState()
    val month by vm.month.collectAsState()
    val agenda by vm.agenda.collectAsState()
    val monthDots by vm.monthDots.collectAsState()
    val board by vm.board.collectAsState()
    val ai by vm.ai.collectAsState()
    var askConsent by remember { mutableStateOf(false) }
    var showSkeleton by rememberSaveable { mutableStateOf(false) }
    var showScenario by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    var adding by remember { mutableStateOf(false) }
    // deschis din „+ › Pentru săptămână”: direct formularul de sarcină nouă
    LaunchedEffect(startAdding) {
        if (startAdding) {
            adding = true
            onStartAddingShown()
        }
    }
    var scenarioInput by rememberSaveable { mutableStateOf("") }
    var pinning by remember { mutableStateOf<WeekTask?>(null) }
    var deadlineFor by remember { mutableStateOf<WeekTask?>(null) }
    var deleting by remember { mutableStateOf<WeekTask?>(null) }
    var editingDay by remember { mutableStateOf<WeekViewModel.SkeletonDay?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Săptămâna") },
                navigationIcon = { onBack?.let { back -> TextButton(onClick = back) { Text("Înapoi") } } },
                actions = { TextButton(onClick = onPlanTomorrow) { Text("Planifică mâine") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- vederea: săptămâna (bandă + ziua ca linie de timp) sau luna (grilă + lista zilei) ---
            item {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = view == WeekViewModel.View.WEEK,
                        onClick = { vm.setView(WeekViewModel.View.WEEK) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("Săptămână") }
                    SegmentedButton(
                        selected = view == WeekViewModel.View.MONTH,
                        onClick = { vm.setView(WeekViewModel.View.MONTH) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("Lună") }
                }
            }
            if (view == WeekViewModel.View.WEEK) {
                // --- tabla: săptămâna asta, următoarea sau cea de după, zi cu zi ---
                item {
                    val week = board.filter { !it.date.isBefore(state.weekStart) && it.date.isBefore(state.weekStart.plusDays(7)) }
                    if (week.size < 7) {
                        Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        WeekDayPlanner(
                            days = week,
                            offset = state.offset,
                            selected = selectedDate,
                            onSelect = vm::select,
                            onPrev = { vm.setOffset(state.offset - 1) },
                            onNext = { vm.setOffset(state.offset + 1) },
                            onAdd = vm::addToDay,
                            onMove = vm::moveToDay,
                            onCancel = vm::cancelActivity,
                            onOpen = onOpenActivity,
                            onEdit = onEditActivity
                        )
                    }
                }
                item {
                    Text(
                        "Ține apăsat pe o sarcină și trage-o pe altă zi din bandă. Atinge-o ca s-o deschizi sau s-o anulezi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (vm.aiAvailable) {
                    item {
                        AiBalanceButton(
                            loading = ai == WeekViewModel.AiUi.Loading,
                            onBalance = { if (AiPrefs.hasConsent(context)) vm.balance() else askConsent = true }
                        )
                    }
                }
            } else {
                item {
                    MonthGrid(
                        month = month,
                        selected = selectedDate,
                        dots = monthDots,
                        onPrev = { vm.shiftMonth(-1) },
                        onNext = { vm.shiftMonth(1) },
                        onSelect = vm::select
                    )
                }
                item {
                    SelectedDayHeader(
                        date = selectedDate,
                        cell = state.days.firstOrNull { it.date == selectedDate },
                        dayOff = agenda?.dayOff == true,
                        onOpenDay = { onOpenDay(selectedDate) }
                    )
                }
                item {
                    val items = agenda?.takeIf { it.date == selectedDate }?.items.orEmpty()
                    val empty = if (agenda?.dayOff == true) "Zi liberă: nimic planificat." else "Nimic planificat în ziua asta."
                    DayAgendaList(items, empty, onOpenActivity)
                }
            }

            if (view == WeekViewModel.View.WEEK) {
            // --- planul săptămânii ---
            item {
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Planul săptămânii", fontFamily = Fraunces, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${shortDate(state.weekStart)} – ${shortDate(state.weekStart.plusDays(6))}" +
                                if (state.offset == 0) " · săptămâna asta" else " · săptămâna viitoare",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

            // --- mai multe: scheletul și simulările, strânse ca să nu încarce ecranul ---
            if (state.skeleton.isNotEmpty()) {
                item {
                    Collapsible(
                        title = "Scheletul săptămânii",
                        subtitle = "Trezirea, ce e fix și mesele; schimbi ceva doar pentru o zi.",
                        expanded = showSkeleton,
                        onToggle = { showSkeleton = !showSkeleton }
                    ) {
                        SkeletonCard(days = state.skeleton, onEdit = { editingDay = it })
                    }
                }
            }
            item {
                Collapsible(
                    title = "Simulează o schimbare",
                    subtitle = "„vreau liber joi după 14” – vezi cum se rearanjează săptămâna.",
                    expanded = showScenario || state.scenarioText.isNotBlank(),
                    onToggle = { showScenario = !showScenario }
                ) {
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
    }

    // AI: acordul (o singură dată) și propunerile
    if (askConsent) {
        AiConsentDialog(
            onAccept = { AiPrefs.setConsent(context, true); askConsent = false; vm.balance() },
            onDismiss = { askConsent = false }
        )
    }
    AiResultSheet(
        ai = ai,
        onAccept = vm::acceptMove,
        onReject = vm::dropMove,
        onAcceptAll = vm::acceptAllMoves,
        onDismiss = vm::dismissAi
    )

    if (adding) {
        NewTaskSheet(
            weekStart = state.weekStart,
            onDismiss = { adding = false },
            onSave = { title, minutes, session, deadline, onDay ->
                vm.addTask(title, minutes, session, deadline, onDay)
                onDay?.let(vm::select)      // arătăm săptămâna și ziua în care a ajuns
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

    // excepțiile unei zile; rândul se ia mereu proaspăt din stare, ca schimbările să se vadă imediat
    editingDay?.let { picked ->
        val day = state.skeleton.firstOrNull { it.date == picked.date } ?: picked
        DayExceptionsDialog(
            day = day,
            onWake = { vm.setWake(day.date, it) },
            onToggleMeal = { vm.toggleMeal(day.date, it) },
            onReset = { vm.resetDay(day.date) },
            onDismiss = { editingDay = null }
        )
    }
}

/**
 * Scheletul săptămânii: un rând pe zi, cu trezirea, ce e fix și mesele.
 * Tu corectezi doar excepțiile (apasă pe o zi); restul îl construiește aplicația.
 */
@Composable
private fun SkeletonCard(days: List<WeekViewModel.SkeletonDay>, onEdit: (WeekViewModel.SkeletonDay) -> Unit) {
    run {
        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            days.forEach { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(d) }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        dayName(d.date).take(2).replaceFirstChar { it.uppercase() } + " ${d.date.dayOfMonth}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.width(52.dp)
                    )
                    Column(Modifier.weight(1f)) {
                        val chosen = if (d.wakeReason == md.utm.digitalmirror.domain.SleepPlanner.Reason.CHOSEN) " ✎" else ""
                        val fixed = when {
                            d.dayOff -> "zi liberă"
                            d.fixedFrom != null && d.fixedTo != null -> "📅 ${clock(d.fixedFrom)}–${clock(d.fixedTo.coerceAtMost(24 * 60 - 1))}"
                            else -> "nimic fix"
                        }
                        Text(
                            "☀️ ${clock(d.wake)}$chosen · $fixed",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        val skipped = d.override?.skipped.orEmpty()
                        val meals = md.utm.digitalmirror.domain.MealPlanner.Kind.entries.mapNotNull { k ->
                            when {
                                k.name in skipped -> null
                                k in d.meals -> k.emoji
                                else -> null
                            }
                        }.joinToString(" ")
                        val warn = d.missingMeals.joinToString(", ") { "n-ai loc de ${it.label.lowercase()}" }
                        Text(
                            listOf(meals.ifBlank { "fără mese planificate" }, warn).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (warn.isNotBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (d.override != null) {
                        Text("excepție", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Excepțiile unei zile: altă oră de trezire, mese sărite, sau înapoi la obișnuit. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DayExceptionsDialog(
    day: WeekViewModel.SkeletonDay,
    onWake: (Int?) -> Unit,
    onToggleMeal: (md.utm.digitalmirror.domain.MealPlanner.Kind) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var pickingWake by remember { mutableStateOf(false) }
    val chosen = day.override?.wakeMinute
    val skipped = day.override?.skipped.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${dayName(day.date).replaceFirstChar { it.uppercase() }}, ${shortDate(day.date)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Trezire", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickingWake = true }) { Text("☀️ ${clock(day.wake)}") }
                    if (chosen != null) TextButton(onClick = { onWake(null) }) { Text("ora obișnuită") }
                }
                Text(
                    if (chosen != null) "Ai ales ora pentru ziua asta. Ora de culcare din seara dinainte se potrivește după ea."
                    else "Ora obișnuită (sau mai devreme, dacă ziua începe devreme).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Mese", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    md.utm.digitalmirror.domain.MealPlanner.Kind.entries.forEach { k ->
                        FilterChip(
                            selected = k.name !in skipped,
                            onClick = { onToggleMeal(k) },
                            label = { Text("${k.emoji} ${k.label}") }
                        )
                    }
                }
                Text(
                    "Debifează o masă ca s-o sari doar în ziua asta.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Gata") } },
        dismissButton = {
            if (day.override != null) TextButton(onClick = { onReset(); onDismiss() }) { Text("Ca de obicei") }
        }
    )

    if (pickingWake) {
        val state = rememberTimePickerState(initialHour = day.wake / 60, initialMinute = day.wake % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingWake = false },
            title = { Text("Te trezești la") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = { onWake(state.hour * 60 + state.minute); pickingWake = false }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingWake = false }) { Text("Anulează") } }
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