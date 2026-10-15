package md.utm.digitalmirror.ui.activity

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.domain.ActivityInsight
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Pagina unei activități: ce s-a întâmplat de data asta, istoricul aceleiași activități
 * și observația care explică de unde vin propunerile aplicației.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    activityId: Long,
    onBack: () -> Unit,
    vm: ActivityDetailViewModel = viewModel()
) {
    LaunchedEffect(activityId) { vm.load(activityId) }
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val analysisOn = remember { AnalysisPrefs.isEnabled(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                title = { Text(state?.activity?.title ?: "", maxLines = 1) }
            )
        }
    ) { padding ->
        val s = state ?: return@Scaffold

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. antet: categorie, zi, oră, stare
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    s.categoryName?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(s.activity.title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        buildList {
                            add(formatDay(s.activity.date))
                            s.activity.plannedStartMinute?.let { add("ora ${formatMinute(it)}") }
                            add(stateLabel(s.activity.state))
                        }.joinToString(" · "),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 2. de data asta: estimări și durata reală
            item { ThisTimeCard(s.activity, s.sessions.size) }

            // 3. sesiunile de cronometru
            if (s.sessions.isNotEmpty()) {
                item { SectionTitle("Sesiuni de cronometru") }
                items(s.sessions) { session ->
                    val end = session.endedAt
                    Column {
                        Text(
                            "${formatTime(session.startedAt)} – ${end?.let { formatTime(it) } ?: "în desfășurare"}" +
                                    (end?.let { " · ${formatDuration(((it - session.startedAt) / 60_000).toInt())}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // timpul pe telefon din sesiune, dacă a fost salvat
                        s.usage[session.startedAt]?.let { u ->
                            Text(
                                if (u.minutes == 0) "sub un minut pe telefon"
                                else "${formatDuration(u.minutes)} pe telefon" +
                                        (if (u.apps.isNotEmpty()) " · " + u.apps.take(3).joinToString(", ") { "${it.first} ${it.second} min" } else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                item { PhoneUsageNote(s) }
            }

            // 4. observația, din istoricul aceleiași activități
            if (analysisOn) {
                item { InsightCard(s.activity.title, s.insight, s.history.size) }
            }

            // 5. istoricul
            if (s.history.isNotEmpty()) {
                item { SectionTitle("Istoricul (${s.history.size})") }
                item { HistoryHeader() }
                items(s.history) { a -> HistoryRow(a) }
            }
        }
    }
}

@Composable
private fun ThisTimeCard(a: Activity, sessionCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("De data asta", style = MaterialTheme.typography.titleMedium)

            a.userGuessMinutes?.let { Row2("Ai estimat", formatDuration(it)) }
            a.estimatedMinutes?.let { est ->
                val range = if (a.estimatedLowMinutes != null && a.estimatedHighMinutes != null)
                    " (${formatDuration(a.estimatedLowMinutes)} – ${formatDuration(a.estimatedHighMinutes)})" else ""
                Row2("Aplicația a propus", "~${formatDuration(est)}$range")
            }
            a.userEstimatedMinutes?.let { Row2("Planificat", formatDuration(it)) }
            a.actualMinutes?.let { actual ->
                Row2(
                    "Real",
                    formatDuration(actual) + when (a.actualSource) {
                        "TIMER" -> " · din cronometru"
                        "MANUAL" -> " · corectat de tine"
                        "AS_PLANNED" -> " · copiat din plan"
                        "LOCATION" -> " · din locație"
                        else -> ""
                    }
                )
            }

            // cine a fost mai aproape, doar dacă avem toate datele și durata e sigură
            val actual = a.actualMinutes
            val guess = a.userGuessMinutes
            val est = a.estimatedMinutes
            if (actual != null && guess != null && est != null && a.actualSource in setOf("TIMER", "MANUAL")) {
                val userError = abs(actual - guess)
                val appError = abs(actual - est)
                Text(
                    when {
                        appError < userError -> "Propunerea aplicației a fost mai aproape cu ${formatDuration(userError - appError)}."
                        userError < appError -> "Estimarea ta a fost mai aproape cu ${formatDuration(appError - userError)}."
                        else -> "Ați fost la fel de aproape."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (a.actualMinutes == null && sessionCount == 0 && a.userGuessMinutes == null && a.estimatedMinutes == null) {
                Text("Încă nu sunt date pentru această activitate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PhoneUsageNote(s: ActivityDetailViewModel.UiState) {
    val closedMinutes = s.sessions
        .filter { it.endedAt != null && s.usage.containsKey(it.startedAt) }
        .sumOf { ((it.endedAt!! - it.startedAt) / 60_000).toInt() }
    val phoneMinutes = s.usage.values.sumOf { it.minutes }

    val text = when {
        !s.usageEnabled && s.usage.isEmpty() ->
            "Ca să vezi cât folosești telefonul în timpul activității, activează accesul la statistici în Setări → Timp pe telefon."
        s.usage.isEmpty() ->
            "Timpul pe telefon se salvează pentru sesiunile de acum încolo."
        closedMinutes < 5 ->
            "Sesiunile sunt prea scurte pentru un procent; după câteva sesiuni mai lungi apare aici."
        closedMinutes > 0 ->
            "În sesiunile salvate ai fost ${formatDuration(phoneMinutes)} pe telefon din ${formatDuration(closedMinutes)} " +
                    "(${(phoneMinutes * 100f / closedMinutes).toInt().coerceIn(0, 100)}%). " +
                    "Include și aplicațiile de care ai nevoie pentru activitate, nu doar distragerile."
        else -> null
    }
    text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun InsightCard(title: String, insight: ActivityInsight.Result, historyCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Observație", style = MaterialTheme.typography.labelLarge)

            if (!insight.enoughData) {
                Text(
                    if (historyCount == 0) "E prima dată când faci „$title”. După câteva sesiuni, aici vei vedea cât îți ia de obicei."
                    else "Mai sunt necesare câteva sesiuni pentru o concluzie (${insight.reliableCount} din ${ActivityInsight.MIN_SESSIONS}).",
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            insight.overrunPercent?.let { p ->
                Text(
                    when {
                        abs(p) <= 10 -> "„$title” durează de obicei cât planifici."
                        p > 0 -> "„$title” îți ia de obicei cu $p% mai mult decât planifici."
                        else -> "„$title” îți ia de obicei cu ${abs(p)}% mai puțin decât planifici."
                    },
                    style = MaterialTheme.typography.titleMedium
                )
            }
            insight.typicalMinutes?.let {
                Text("Durata tipică: ${formatDuration(it)}", style = MaterialTheme.typography.bodyMedium)
            }

            // pe ce date se bazează
            Text(
                "Pe baza a ${insight.reliableCount} sesiuni: " + buildList {
                    if (insight.timerCount > 0) add("${insight.timerCount} cronometrate")
                    if (insight.manualCount > 0) add("${insight.manualCount} corectate de tine")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun HistoryHeader() {
    Row(Modifier.fillMaxWidth()) {
        Text("Data", Modifier.weight(1.4f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Planificat", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
        Text("Real", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

@Composable
private fun HistoryRow(a: Activity) {
    val planned = a.userEstimatedMinutes ?: a.estimatedMinutes
    val reliable = a.actualSource in setOf("TIMER", "MANUAL")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            formatDay(a.date) + (a.plannedStartMinute?.let { " · ${formatMinute(it)}" } ?: ""),
            Modifier.weight(1.4f),
            style = MaterialTheme.typography.bodyMedium
        )
        Text(planned?.let { formatDuration(it) } ?: "—", Modifier.weight(1f), textAlign = TextAlign.End)
        Text(
            (a.actualMinutes?.let { formatDuration(it) } ?: "—") + if (reliable) "" else "*",
            Modifier.weight(1f),
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Medium,
            // durata reală e marcată cu culoarea de eroare dacă depășește planul cu peste 10%
            color = if (planned != null && (a.actualMinutes ?: 0) > planned * 1.1) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium)

private val ro = Locale.forLanguageTag("ro")
private val dayFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", ro)
private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatDay(iso: String): String =
    LocalDate.parse(iso).format(dayFormatter).replaceFirstChar { it.uppercase() }

private fun formatTime(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(timeFormatter)

private fun formatMinute(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)

private fun stateLabel(state: String) = when (state) {
    "PLANNED" -> "planificată"
    "IN_PROGRESS" -> "în curs"
    "DONE" -> "finalizată"
    "PARTIAL" -> "parțial"
    "POSTPONED" -> "amânată"
    "ABANDONED" -> "abandonată"
    else -> state
}