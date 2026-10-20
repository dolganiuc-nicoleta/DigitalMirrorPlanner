package md.utm.digitalmirror.ui.prep

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val prepDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ro"))

internal fun daysLeftText(daysLeft: Int): String = when {
    daysLeft > 1 -> "peste $daysLeft zile"
    daysLeft == 1 -> "mâine"
    daysLeft == 0 -> "azi"
    else -> "a trecut"
}

/** Lista evenimentelor pentru care te pregătești. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepScreen(
    onBack: () -> Unit,
    onNew: () -> Unit,
    onOpen: (Long) -> Unit,
    vm: PrepViewModel = viewModel()
) {
    val events by vm.events.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pregătire") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onNew) { Text("+") } }
    ) { padding ->
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Nicio pregătire încă", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Spune pentru ce te pregătești (o susținere, un examen, un interviu) și când. " +
                                "Împart eu munca pe zile, până în ajunul evenimentului.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onNew) { Text("Pregătește un eveniment") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(events, key = { it.event.id }) { row -> EventCard(row, onClick = { onOpen(row.event.id) }) }
            }
        }
    }
}

@Composable
private fun EventCard(row: PrepViewModel.EventRow, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.event.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(
                "${LocalDate.parse(row.event.eventDate).format(prepDateFormatter)} · ${daysLeftText(row.daysLeft)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LinearProgressIndicator(progress = { row.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Text(
                "Pregătită ${row.percent}% · ${formatDuration(row.doneMinutes)} din ${formatDuration(row.totalMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
