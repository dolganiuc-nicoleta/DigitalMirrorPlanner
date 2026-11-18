package md.utm.digitalmirror.ui.closing

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.domain.EveningReview
import md.utm.digitalmirror.domain.SleepPlanner
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

private val MOODS = listOf(1 to "😞", 2 to "😕", 3 to "😐", 4 to "🙂", 5 to "😄")

/**
 * Ritualul de seară, sub 30 de secunde:
 *  1. Azi: cum a fost ziua + activitățile, deja completate → „Închide ziua”.
 *  2. Mâine: trezire, primul lucru, plecarea, mesele, culcarea → „Arată bine”, cu întrebări doar la excepții.
 * Detaliile (corectarea duratelor, notițele, somnul) rămân la un tap distanță, nu în fața ta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosingScreen(
    date: String,
    onBack: () -> Unit,
    onOpenDay: (String) -> Unit = { onBack() },
    vm: ClosingViewModel = viewModel()
) {
    LaunchedEffect(date) { vm.load(date) }

    val isToday = date == LocalDate.now().toString()
    var step by rememberSaveable { mutableIntStateOf(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("←") }
                },
                title = { Text(if (step == 1) "Închide ziua" else "Mâine") },
                actions = {
                    if (isToday) {
                        Text(
                            "$step / 2",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (step == 1) {
            TodayStep(
                vm = vm,
                date = date,
                isToday = isToday,
                padding = padding,
                onClosed = {
                    if (isToday) {
                        vm.loadTomorrow()
                        step = 2
                    } else onBack()
                }
            )
        } else {
            TomorrowStep(
                vm = vm,
                padding = padding,
                onDone = onBack,
                onOpenDay = onOpenDay
            )
        }
    }
}

// ---------- pasul 1: azi ----------

@Composable
private fun TodayStep(
    vm: ClosingViewModel,
    date: String,
    isToday: Boolean,
    padding: PaddingValues,
    onClosed: () -> Unit
) {
    val activities by vm.activities.collectAsState()
    val drafts by vm.drafts.collectAsState()
    val presenceViews by vm.presenceViews.collectAsState()
    val reflection by vm.reflection.collectAsState()
    val daily by vm.daily.collectAsState()

    val closed = activities.filter { it.state in CLOSED_STATES }

    var mood by remember(reflection) { mutableIntStateOf(reflection?.moodScore ?: 0) }
    var workedOn by remember(reflection) { mutableStateOf(reflection?.workedOn ?: "") }
    var blockers by remember(reflection) { mutableStateOf(reflection?.blockers ?: "") }
    var note by remember(reflection) { mutableStateOf(reflection?.note ?: "") }
    // detaliile sunt ascunse; se deschid singure dacă ai scris deja ceva
    var showMore by remember(reflection) {
        mutableStateOf(!reflection?.workedOn.isNullOrBlank() || !reflection?.blockers.isNullOrBlank() || !reflection?.note.isNullOrBlank())
    }

    var bedtime by remember(daily) { mutableStateOf(daily?.bedtimeMinute) }
    var wake by remember(daily) { mutableStateOf(daily?.wakeMinute) }
    var sleepEdited by remember(daily) { mutableStateOf(false) }
    var picking by remember { mutableStateOf<String?>(null) }

    // activitățile finalizate sau parțiale trebuie să aibă o durată
    val ready = drafts.all { it.state !in setOf("DONE", "PARTIAL") || it.minutes != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. cum a fost ziua: un singur tap
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Cum a fost ziua?")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MOODS.forEach { (value, emoji) ->
                        FilterChip(
                            selected = mood == value,
                            onClick = { mood = value },
                            label = { Text(emoji, style = MaterialTheme.typography.titleLarge) }
                        )
                    }
                }
            }
        }

        // 2. activitățile, deja completate: o linie fiecare, cu „Modifică” pentru excepții
        if (drafts.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionTitle("Activitățile zilei")
                    Hint("Am completat din cronometru, locație și plan. Atinge doar ce nu e bine.")
                }
            }
            items(drafts, key = { it.activity.id }) { draft ->
                CompactDraft(
                    draft = draft,
                    onState = { vm.changeState(draft, it) },
                    onMinutes = { vm.changeMinutes(draft, it) }
                )
            }
        }
        if (closed.isNotEmpty()) {
            item {
                val real = closed.sumOf { it.actualMinutes ?: 0 }
                Hint("Deja închise: ${closed.size} · ${formatDuration(real)} lucrate")
            }
        }

        // 3. butonul principal
        item {
            Button(
                onClick = {
                    vm.closeDay(mood, workedOn, blockers, note, bedtime, wake, sleepEdited, onDone = onClosed)
                },
                enabled = mood > 0 && ready,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isToday) "Închide ziua →" else "Salvează")
            }
            if (mood == 0) {
                Hint("Alege cum a fost ziua, apoi închide.", Modifier.padding(top = 4.dp))
            }
        }

        // 4. mai multe, opțional: notițe, unde ai fost, somnul
        item {
            TextButton(onClick = { showMore = !showMore }) {
                Text(if (showMore) "Mai puțin" else "+ Adaugă o notă (opțional)")
            }
        }
        if (showMore) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (presenceViews.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Unde ai fost", fontWeight = FontWeight.Medium)
                            presenceViews.forEach { v ->
                                Text(
                                    "${v.placeName} · ${clock(v.start)}–${clock(v.end)} (${formatDuration(v.minutes)})",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            }
            // somnul se confirmă dimineața; aici rămâne pentru zilele trecute sau corecturi
            item {
                SleepCard(
                    title = if (isToday) "Somnul de azi-noapte" else "Somnul din noaptea dinainte",
                    bedtime = bedtime,
                    wake = wake,
                    sourceHint = when {
                        sleepEdited -> null
                        daily?.sleepSource == "HEALTH" -> "din aplicația de somn · poți corecta"
                        daily?.sleepSource == "MANUAL" -> "confirmat de tine"
                        else -> null
                    },
                    onPickBedtime = { picking = "bed" },
                    onPickWake = { picking = "wake" }
                )
            }
        }
    }

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

// o activitate pe o linie: „✓ Licență · 1h 30 · din cronometru”; la „Modifică” se deschide cardul complet
@Composable
private fun CompactDraft(
    draft: ClosingViewModel.Draft,
    onState: (String) -> Unit,
    onMinutes: (Int?) -> Unit
) {
    var open by remember(draft.activity.id) { mutableStateOf(false) }
    if (open || (draft.state in setOf("DONE", "PARTIAL") && draft.minutes == null)) {
        DraftCard(draft = draft, onState = onState, onMinutes = onMinutes)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            val mark = when (draft.state) {
                "DONE" -> "✓"
                "PARTIAL" -> "◐"
                "POSTPONED" -> "→"
                else -> "✕"
            }
            Text("$mark ${draft.activity.title}", style = MaterialTheme.typography.bodyLarge)
            Hint(
                when (draft.state) {
                    "POSTPONED" -> "se mută pe mâine"
                    "ABANDONED" -> "abandonată"
                    else -> listOfNotNull(
                        draft.minutes?.let { formatDuration(it) },
                        when (draft.source) {
                            "TIMER" -> "din cronometru"
                            "LOCATION" -> "din locație"
                            "AS_PLANNED" -> "cât ai planificat"
                            "MANUAL" -> "corectată de tine"
                            else -> null
                        },
                        if (draft.state == "PARTIAL") "parțial" else null
                    ).joinToString(" · ")
                }
            )
        }
        TextButton(onClick = { open = true }) { Text("Modifică") }
    }
}

// ---------- pasul 2: mâine ----------

@Composable
private fun TomorrowStep(
    vm: ClosingViewModel,
    padding: PaddingValues,
    onDone: () -> Unit,
    onOpenDay: (String) -> Unit
) {
    val tomorrow by vm.tomorrow.collectAsState()
    val places by vm.places.collectAsState()

    val t = tomorrow
    if (t == null) {
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // rezumatul: ziua de mâine în câteva rânduri
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SummaryRow("☀️", "Trezire ${SleepPlanner.clock(t.sleep.wake)}",
                        if (t.sleep.reason == SleepPlanner.Reason.EARLY_START) "mai devreme decât de obicei" else null)
                    t.first?.let { f ->
                        SummaryRow("📅", "${SleepPlanner.clock(f.start)} · ${f.title}", null)
                    }
                    t.firstLeg?.let { leg ->
                        val prep = if (leg.prepMinutes > 0) "te pregătești la ${SleepPlanner.clock(leg.prepStart)} · " else ""
                        SummaryRow("🚶", "${prep}pleci la ${SleepPlanner.clock(leg.start)}", null)
                    }
                    if (t.meals.isNotEmpty()) {
                        SummaryRow(
                            "🍽",
                            t.meals.joinToString(" · ") { "${it.kind.label} ${SleepPlanner.clock(it.start)}" },
                            null
                        )
                    }
                    if (t.first == null) SummaryRow("🌤", "Nimic planificat cu oră", "o zi liberă")
                    val usual = t.sleep.usualBedtime
                    SummaryRow(
                        "🌙", "Diseară la ${SleepPlanner.clock(t.bedtimeTonight)}",
                        if (usual != null && usual - t.bedtimeTonight >= 30)
                            "cu ${formatDuration(usual - t.bedtimeTonight)} mai devreme decât de obicei"
                        else "ca să dormi ~${formatDuration(t.sleep.sleepMinutes)}"
                    )
                }
            }
        }

        // întrebările, doar la excepții
        if (t.issues.isNotEmpty()) {
            item { SectionTitle("De văzut") }
            items(t.issues) { issue ->
                IssueCard(
                    issue = issue,
                    places = places,
                    onMove = { id, start -> vm.moveTomorrow(id, start) },
                    onPlace = { title, placeId -> vm.setEventPlace(title, placeId) }
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                    Text("Arată bine ✓")
                }
                TextButton(onClick = { onOpenDay(t.date.toString()) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Deschide ziua de mâine")
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(emoji: String, text: String, hint: String?) {
    Row(verticalAlignment = Alignment.Top) {
        Text(emoji, modifier = Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium)
        Column {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            hint?.let { Hint(it) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueCard(
    issue: EveningReview.Issue,
    places: List<Place>,
    onMove: (Long, Int) -> Unit,
    onPlace: (String, Long) -> Unit
) {
    var answered by remember(issue) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (issue) {
                is EveningReview.Issue.EarlyBecauseFlexible -> {
                    Text(
                        "⏰ ${issue.item.title} la ${SleepPlanner.clock(issue.item.start)} te-ar scula la " +
                                "${SleepPlanner.clock(issue.wake)}, cu ${formatDuration(issue.usualWake - issue.wake)} " +
                                "mai devreme decât de obicei."
                    )
                    val id = issue.item.activityId
                    val to = issue.moveTo
                    if (id != null && to != null && !answered) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { answered = true; onMove(id, to) }) {
                                Text("Mut-o la ${SleepPlanner.clock(to)}")
                            }
                            TextButton(onClick = { answered = true }) { Text("Las-o") }
                        }
                    }
                }
                is EveningReview.Issue.NoPlace -> {
                    Text("📍 Unde e ${issue.title}? Fără loc nu pot calcula drumul.")
                    if (!answered) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            places.forEach { p ->
                                AssistChip(onClick = { answered = true; onPlace(issue.title, p.id) }, label = { Text(p.name) })
                            }
                        }
                    }
                }
                is EveningReview.Issue.NoMeal -> {
                    Text(
                        "${issue.window.kind.emoji} Mâine n-ai pauză de ${issue.window.kind.label.lowercase()} " +
                                "între ${SleepPlanner.clock(issue.window.from)} și ${SleepPlanner.clock(issue.window.to)}."
                    )
                    Hint("Ia ceva la pachet sau mănâncă între activități.")
                }
                is EveningReview.Issue.Overloaded -> {
                    Text(
                        "📦 Mâine ai planificat ${formatDuration(issue.planned)}, " +
                                "de obicei reușești ~${formatDuration(issue.typical)}."
                    )
                    Hint("Poți muta ceva din „Deschide ziua de mâine”.")
                }
            }
        }
    }
}

// ---------- piesele folosite în ambii pași ----------

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
                Text(
                    "Ai dormit ${formatDuration(ClosingViewModel.sleepDuration(bedtime, wake))}",
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

// o activitate de confirmat, completă: starea și durata, cu posibilitatea de a le corecta
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
                                "LOCATION" -> Hint("din locație: cât ai fost la loc · corectează dacă a durat altfel")
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
private fun Hint(text: String, modifier: Modifier = Modifier) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )

private fun formatMinute(m: Int): String = "%02d:%02d".format(m / 60, m % 60)

// ora locală dintr-un moment în milisecunde
private fun clock(millis: Long): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .toLocalTime().let { "%02d:%02d".format(it.hour, it.minute) }
