package md.utm.digitalmirror.ui.report

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import md.utm.digitalmirror.domain.PeriodReport
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(onBack: () -> Unit, vm: ReportViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raport") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } },
                actions = {
                    state?.let { s ->
                        TextButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Raportul săptămânii ${weekLabel(s.weekStart)}")
                                putExtra(Intent.EXTRA_TEXT, shareText(s))
                            }
                            context.startActivity(Intent.createChooser(send, "Trimite raportul"))
                        }) { Text("Trimite") }
                    }
                }
            )
        }
    ) { padding ->
        val s = state
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                WeekSelector(
                    s,
                    onPrevious = vm::previousWeek,
                    onNext = vm::nextWeek,
                    onCurrent = vm::currentWeek
                )
            }
            if (!s.hasAnyData) {
                item {
                    Text(
                        "Încă nu ai activități. Raportul apare după ce planifici și închizi câteva.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                item { WeekCard(s) }
                item { EvolutionCard(s) }
                item { ComparisonCard(s) }
                item { RepeatedCard(s.repeated) }
            }
        }
    }
}

@Composable
private fun WeekSelector(
    s: ReportState,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPrevious) { Text("‹") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                weekLabel(s.weekStart),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (s.isCurrentWeek) {
                Text("săptămâna aceasta", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = onCurrent) { Text("Înapoi la săptămâna aceasta") }
            }
        }
        TextButton(onClick = onNext, enabled = !s.isCurrentWeek) { Text("›") }
    }
}

@Composable
private fun WeekCard(s: ReportState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile("Finalizare", s.week.completionPercent?.let { "$it%" } ?: "–", Modifier.weight(1f))
                Tile("Timp lucrat", if (s.week.minutes > 0) formatDuration(s.week.minutes) else "–", Modifier.weight(1f))
                Tile("Zile active", "${s.week.activeDays}", Modifier.weight(1f))
            }
            s.observations.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (s.week.due > 0) {
                Text(
                    "Finalizate ${s.week.done} · parțiale ${s.week.partial} · abandonate ${s.week.abandoned} · " +
                            "neînchise sau amânate ${s.week.notClosed}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---- evoluția în timp ----

@Composable
private fun EvolutionCard(s: ReportState) {
    var byTime by rememberSaveable { mutableStateOf(false) }
    val values: List<Int?> = s.series.map { if (byTime) it.minutes.takeIf { m -> m > 0 } else it.completionPercent }
    val max = if (byTime) (values.filterNotNull().maxOrNull() ?: 1).coerceAtLeast(1) else 100

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Evoluția pe ultimele 8 săptămâni", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !byTime, onClick = { byTime = false }, label = { Text("Finalizare") })
                FilterChip(selected = byTime, onClick = { byTime = true }, label = { Text("Timp lucrat") })
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(150.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                s.series.forEachIndexed { index, week ->
                    val value = values[index]
                    val selected = week.from == s.weekStart
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        Text(
                            when {
                                value == null -> "–"
                                byTime -> shortDuration(value)
                                else -> "$value%"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(2.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((90 * ((value ?: 0).toFloat() / max)).coerceAtLeast(3f).dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    when {
                                        value == null -> MaterialTheme.colorScheme.surfaceVariant
                                        selected -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                    }
                                )
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            week.from.format(shortDate),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                "Barele arată luni din fiecare săptămână. „–” înseamnă că nu era nimic scadent sau nimic lucrat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---- comparația între perioade ----

@Composable
private fun ComparisonCard(s: ReportState) {
    var days by rememberSaveable { mutableStateOf(7) }
    val c = if (days == 7) s.compare7 else s.compare30

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Comparație între perioade", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = days == 7, onClick = { days = 7 }, label = { Text("7 zile") })
                FilterChip(selected = days == 30, onClick = { days = 30 }, label = { Text("30 de zile") })
            }
            Text(
                "Ultimele $days zile față de cele $days dinaintea lor (până azi).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompareRow("", "acum", "înainte", "", header = true)
            CompareRow(
                "Finalizate", "${c.current.done}", "${c.previous.done}",
                signed(c.doneDelta)
            )
            CompareRow(
                "Rata de finalizare",
                c.current.completionPercent?.let { "$it%" } ?: "–",
                c.previous.completionPercent?.let { "$it%" } ?: "–",
                c.completionDelta?.let { signed(it) + " p." } ?: ""
            )
            CompareRow(
                "Timp lucrat",
                if (c.current.minutes > 0) formatDuration(c.current.minutes) else "–",
                if (c.previous.minutes > 0) formatDuration(c.previous.minutes) else "–",
                c.minutesChangePercent?.let { signed(it) + "%" } ?: ""
            )
            CompareRow("Zile active", "${c.current.activeDays}", "${c.previous.activeDays}", signed(c.current.activeDays - c.previous.activeDays))
        }
    }
}

@Composable
private fun CompareRow(label: String, now: String, before: String, change: String, header: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium
    val color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.6f), style = style, color = color)
        Text(now, Modifier.weight(1f), style = style, color = color, textAlign = TextAlign.End)
        Text(before, Modifier.weight(1f), style = style, color = color, textAlign = TextAlign.End)
        Text(change, Modifier.weight(0.9f), style = style, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End)
    }
}

// ---- amânate repetat ----

@Composable
private fun RepeatedCard(repeated: List<PeriodReport.Postponed>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Amânate de mai multe ori", style = MaterialTheme.typography.titleMedium)
            if (repeated.isEmpty()) {
                Text(
                    "Nicio activitate nefăcută nu a fost amânată de mai multe ori.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                repeated.forEach {
                    Text("${it.title} · amânată de ${it.times} ori", style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "Poate merită împărțite în pași mai mici sau mutate la o oră în care ai terminat de obicei activități.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- texte ----

private val shortDate = DateTimeFormatter.ofPattern("d.MM", Locale.forLanguageTag("ro"))
private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("ro"))
private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ro"))

private fun weekLabel(start: LocalDate): String =
    "${start.format(dayMonth)} – ${start.plusDays(6).format(dayMonthYear)}"

private fun signed(n: Int): String = when {
    n > 0 -> "+$n"
    n < 0 -> "−${abs(n)}"
    else -> "0"
}

// 90 → „1h30”, 45 → „45m”; scurt, ca să încapă deasupra barei
private fun shortDuration(minutes: Int): String =
    if (minutes < 60) "${minutes}m" else "${minutes / 60}h${if (minutes % 60 == 0) "" else (minutes % 60).toString()}"

/** Raportul ca text simplu, pentru trimis prin e-mail sau mesagerie. */
private fun shareText(s: ReportState): String = buildString {
    appendLine("Raportul săptămânii ${weekLabel(s.weekStart)}")
    appendLine()
    s.observations.forEach { appendLine("• $it") }
    if (s.repeated.isNotEmpty()) {
        appendLine()
        appendLine("Amânate de mai multe ori:")
        s.repeated.forEach { appendLine("• ${it.title} (${it.times} ori)") }
    }
    appendLine()
    append("Generat cu DigitalMirrorPlanner")
}