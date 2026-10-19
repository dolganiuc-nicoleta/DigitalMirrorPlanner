package md.utm.digitalmirror.ui.plan

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.domain.DayPlanner
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanScreen(onBack: () -> Unit, vm: PlanViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val places by vm.places.collectAsState()
    val context = LocalContext.current

    var text by rememberSaveable { mutableStateOf("") }
    var pickingDate by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Planifică din text") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Ce vrei să faci?") },
                    placeholder = { Text("mâine laborator PAD 2h, sport seara, cumpărături 30 min") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Text(
                    "Separă activitățile prin virgulă. Poți spune durata (2h, 45 min), ora (la 14:30, de la 10 la 12) " +
                            "sau momentul zilei (dimineața, seara). Ziua poate fi azi, mâine, vineri etc.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                Button(
                    onClick = { vm.analyze(text) },
                    enabled = text.isNotBlank() && !state.working,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (state.working) "Se calculează…" else "Propune planul") }
            }

            val proposals = state.proposals
            if (proposals != null) {
                if (proposals.isEmpty()) {
                    item {
                        Text(
                            "Nu am găsit nicio activitate în text. Încearcă ceva de felul „mâine sport 1h, citit seara”.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    item { DateRow(state, onPick = { pickingDate = true }) }
                    item { Summary(state) }
                    items(proposals, key = { it.index }) { p ->
                        ProposalCard(
                            proposal = p,
                            postponeDate = state.date.plusDays(1),
                            places = places,
                            onPlace = { vm.setPlace(p.index, it) },
                            onInclude = { vm.setIncluded(p.index, it) },
                            onUseHistory = { vm.useHistoryMinutes(p.index) }
                        )
                    }
                    item {
                        val count = proposals.count { it.include }
                        Button(
                            onClick = {
                                vm.confirm { message ->
                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                    onBack()
                                }
                            },
                            enabled = count > 0,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Adaugă în plan ($count)") }
                    }
                }
            }
        }
    }

    if (pickingDate) {
        PlanDatePicker(
            onDismiss = { pickingDate = false },
            onConfirm = { vm.setDate(it); pickingDate = false }
        )
    }
}

@Composable
private fun DateRow(state: PlanViewModel.UiState, onPick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Pentru ${state.date.format(dayFormatter)}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                if (state.dateFromText) "ziua din text" else "alegerea ta, sau ziua următoare dacă n-ai spus-o",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onPick) { Text("Schimbă ziua") }
    }
}

@Composable
private fun Summary(state: PlanViewModel.UiState) {
    val placed = state.placed.size
    val unplaced = state.unplaced
    Text(
        buildString {
            append("$placed ${if (placed == 1) "activitate se așază" else "activități se așază"} pe ${state.date.format(dayFormatter)}.")
            if (unplaced.isNotEmpty()) {
                append(" ${unplaced.size} nu mai ${if (unplaced.size == 1) "încape" else "încap"}; ")
                append("propun să le amâni pe ${state.date.plusDays(1).format(dayFormatter)}: ")
                append(unplaced.joinToString(", ") { it.title })
                append(".")
            }
        }.replace("..", "."),    // luna abreviată se termină deja cu punct („oct.”)
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun ProposalCard(
    proposal: PlanViewModel.Proposal,
    postponeDate: LocalDate,
    places: List<Place>,
    onPlace: (Long?) -> Unit,
    onInclude: (Boolean) -> Unit,
    onUseHistory: () -> Unit
) {
    val placement = proposal.placement
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = proposal.include, onCheckedChange = onInclude)
            Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(proposal.title, fontWeight = FontWeight.Medium)

                Text(
                    if (placement.placed) "${clock(placement.startMinute!!)}–${clock(placement.endMinute!!)}"
                    else "Nu mai încape · propun ${postponeDate.format(dayFormatter)}, fără oră",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (placement.placed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )

                Text(
                    buildList {
                        add(proposal.category?.name ?: "fără categorie")
                        add(
                            when (proposal.source) {
                                PlanViewModel.DurationSource.STATED -> "${formatDuration(proposal.minutes)} (spus de tine)"
                                PlanViewModel.DurationSource.ESTIMATED -> "${formatDuration(proposal.minutes)} (din istoricul tău)"
                                PlanViewModel.DurationSource.DEFAULT -> "${formatDuration(proposal.minutes)} (implicit)"
                            }
                        )
                        proposal.dayPart?.let { if (placement.reason != DayPlanner.Reason.CHOSEN_BY_USER) add(it.label) }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (places.isNotEmpty()) {
                    PlacePicker(places, proposal.placeId, onPlace)
                }

                if (placement.conflict) {
                    Text(
                        "Ora cerută se suprapune cu altceva din zi.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                proposal.historyMinutes?.let { history ->
                    TextButton(onClick = onUseHistory, contentPadding = PaddingValues(0.dp)) {
                        Text(
                            "Din istoric, de obicei durează ~${formatDuration(history)}. Folosește această durată",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

/** „Loc: Acasă ▾” — se schimbă cu un tap; „fără loc” îl scoate. */
@Composable
private fun PlacePicker(places: List<Place>, selectedId: Long?, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val name = places.firstOrNull { it.id == selectedId }?.name
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(0.dp)) {
            Text("Loc: ${name ?: "fără loc"} ▾", style = MaterialTheme.typography.bodySmall)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("fără loc") }, onClick = { onSelect(null); open = false })
            places.forEach { place ->
                DropdownMenuItem(text = { Text(place.name) }, onClick = { onSelect(place.id); open = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlanDatePicker(onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val todayMillis = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = todayMillis + 24 * 60 * 60 * 1000L,
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
                        onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                }
            ) { Text("Alege") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    ) { DatePicker(state = state) }
}

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.forLanguageTag("ro"))

private fun clock(minute: Int): String = "%02d:%02d".format((minute / 60) % 24, minute % 60)