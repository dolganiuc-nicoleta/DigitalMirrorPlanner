package md.utm.digitalmirror.ui.prep

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.forLanguageTag("ro"))
private fun clock(minute: Int) = "%02d:%02d".format((minute / 60) % 24, minute % 60)

/** Un eveniment: cât ești de pregătit, pașii, sesiunile din zile și replanificarea. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrepDetailScreen(
    eventId: Long,
    onBack: () -> Unit,
    onOpenActivity: (Long) -> Unit,
    vm: PrepViewModel = viewModel()
) {
    val context = LocalContext.current
    LaunchedEffect(eventId) { vm.select(eventId) }
    val detail by vm.detail.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.event?.title ?: "Pregătire") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } },
                actions = {
                    if (detail != null) {
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("Șterge", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { padding ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // cât ești de pregătit
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "${LocalDate.parse(d.event.eventDate).format(prepDateFormatter)} · ${daysLeftText(d.daysLeft)}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pregătită", modifier = Modifier.weight(1f))
                            Text("${d.percent}%", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text(
                            "${formatDuration(d.doneMinutes)} lucrate din ${formatDuration(d.totalMinutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ce nu are încă loc în plan
            if (d.daysLeft > 0) {
                item {
                    Card(
                        colors = if (d.unscheduledMinutes > 0)
                            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        else CardDefaults.cardColors()
                    ) {
                        Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (d.unscheduledMinutes > 0)
                                    "${formatDuration(d.unscheduledMinutes)} din ce a rămas nu are loc în zilele tale."
                                else "Tot ce a rămas are loc în plan.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Dacă ai ratat sesiuni sau ți s-a schimbat programul, refac planul pentru zilele rămase.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = {
                                vm.replan(eventId) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                            }) { Text("Replanifică ce a rămas") }
                        }
                    }
                }
            }

            // pașii
            item { Text("Pașii", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
            items(d.steps, key = { "step-${it.step.id}" }) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(row.step.title, fontWeight = FontWeight.Medium)
                        val shown = minOf(row.doneMinutes, row.step.minutes)
                        LinearProgressIndicator(
                            progress = { if (row.step.minutes <= 0) 0f else shown / row.step.minutes.toFloat() },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${formatDuration(shown)} din ${formatDuration(row.step.minutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // sesiunile, pe zile
            item { Text("Sesiunile din plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }
            if (d.sessions.isEmpty()) {
                item { Text("Nicio sesiune încă.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val prefix = "${d.event.title}: "
                val byDay = d.sessions.groupBy { it.date }.toSortedMap()
                byDay.forEach { (date, list) ->
                    item(key = "day-$date") {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    LocalDate.parse(date).format(dayFormatter).replaceFirstChar { it.uppercase() },
                                    fontWeight = FontWeight.SemiBold
                                )
                                list.sortedBy { it.plannedStartMinute ?: -1 }.forEach { a ->
                                    val finished = a.state == "DONE" || a.state == "PARTIAL"
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onOpenActivity(a.id) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            a.plannedStartMinute?.let { s ->
                                                "${clock(s)}–${clock(s + (a.userEstimatedMinutes ?: 0))}"
                                            } ?: "fără oră",
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.width(96.dp)
                                        )
                                        Text(a.title.removePrefix(prefix), modifier = Modifier.weight(1f))
                                        if (finished) Text("✓", color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Ștergi pregătirea?") },
            text = { Text("Sesiunile neîncepute se șterg din zilele tale. Ce ai terminat deja rămâne în istoric.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(eventId) { onBack() }
                }) { Text("Șterge", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Anulează") } }
        )
    }
}
