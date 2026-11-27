package md.utm.digitalmirror.ui.week

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.PeriodReport
import md.utm.digitalmirror.domain.WeekTaskParser
import md.utm.digitalmirror.ui.components.DurationStepper
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.theme.Fraunces
import java.time.LocalDate

private val MONTHS = listOf("ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")
private val DAY_NAMES = listOf("luni", "marți", "miercuri", "joi", "vineri", "sâmbătă", "duminică")

/** Când se face sarcina: oricând în săptămână, până la o zi (termen), sau într-o zi anume. */
enum class TaskWhen(val label: String) { ANYTIME("Oricând"), BY_DAY("Până la o zi"), ON_DAY("Într-o zi anume") }

/**
 * Sarcina nouă, ca panou: titlul mare, cât durează în total (− / +), în ce bucăți se lucrează,
 * și când (oricând, până la o zi sau într-o zi anume), cu zilele din următoarele 3 săptămâni la vedere.
 * Textul liber merge în continuare („licență 6h până vineri”) și completează singur câmpurile.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewTaskSheet(
    weekStart: LocalDate,                     // săptămâna arătată (sarcinile „oricând” sunt ale ei)
    onDismiss: () -> Unit,
    onSave: (title: String, totalMinutes: Int, sessionMinutes: Int, deadline: LocalDate?, onDay: LocalDate?) -> Unit
) {
    val today = LocalDate.now()
    val firstDay = PeriodReport.weekStart(today)
    var text by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf<Int?>(null) }
    var session by rememberSaveable { mutableStateOf<Int?>(null) }
    var mode by rememberSaveable { mutableStateOf(TaskWhen.ANYTIME) }
    var dayText by rememberSaveable { mutableStateOf<String?>(null) }    // ziua aleasă, ca text (se poate salva)
    val day = dayText?.let(LocalDate::parse)

    // textul liber completează ce n-ai ales încă: „licență 6h, sesiuni de 90 min, până vineri”
    val parsed = remember(text) { WeekTaskParser.parse(text) }
    LaunchedEffect(parsed) {
        if (parsed.totalMinutes != null) total = parsed.totalMinutes
        if (parsed.sessionMinutes != null) session = parsed.sessionMinutes
        parsed.deadlineDay?.let { i ->
            val d = weekStart.plusDays(i.toLong())
            if (!d.isBefore(today)) { mode = TaskWhen.BY_DAY; dayText = d.toString() }
        }
    }

    val title = parsed.title ?: text.trim()
    val sessionMinutes = (session ?: 90).coerceAtMost(total ?: Int.MAX_VALUE)
    val valid = title.isNotBlank() && (total ?: 0) >= 15 && (mode == TaskWhen.ANYTIME || day != null)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column {
                Text("Sarcină nouă", fontFamily = Fraunces, style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Ceva mai mare, pe care îl împart eu în sesiuni pe zile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Ce ai de făcut?", style = MaterialTheme.typography.titleLarge) },
                supportingText = { Text("Poți scrie și „licență 6h, până vineri”.") },
                textStyle = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Section("Cât durează în total?") {
                DurationStepper(
                    minutes = total,
                    onChange = { total = it },
                    quick = listOf(60, 120, 180, 240, 360, 480, 600),
                    min = 15,
                    step = { if (it < 60) 15 else 30 }
                )
            }

            Section("În bucăți de câte?") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 45, 60, 90, 120).forEach { m ->
                        FilterChip(
                            selected = sessionMinutes == m && (total ?: 0) > m,
                            onClick = { session = m },
                            label = { Text(formatDuration(m)) }
                        )
                    }
                    FilterChip(
                        selected = total != null && sessionMinutes >= total!!,
                        onClick = { session = total },
                        enabled = total != null,
                        label = { Text("Dintr-o bucată") }
                    )
                }
                total?.let { t ->
                    val n = (t + sessionMinutes - 1) / sessionMinutes
                    Text(
                        if (n <= 1) "O singură sesiune de ${formatDuration(t)}."
                        else "$n sesiuni de câte ${formatDuration(sessionMinutes)}, puse pe zilele cu loc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Section("Când?") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TaskWhen.entries.forEach { w ->
                        FilterChip(selected = mode == w, onClick = { mode = w }, label = { Text(w.label) })
                    }
                }
                when (mode) {
                    TaskWhen.ANYTIME -> Text(
                        "Le pun în săptămâna ${weekLabel(weekStart, today)}, în zilele cu cel mai mult loc.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        ThreeWeekPicker(firstDay = firstDay, today = today, selected = day, onPick = { dayText = it.toString() })
                        Text(
                            when {
                                day == null -> "Alege ziua."
                                mode == TaskWhen.BY_DAY -> "Gata cel târziu ${DAY_NAMES[day.dayOfWeek.value - 1]}, ${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}."
                                else -> "Toate sesiunile ${DAY_NAMES[day.dayOfWeek.value - 1]}, ${day.dayOfMonth} ${MONTHS[day.monthValue - 1]}."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Button(
                onClick = {
                    onSave(
                        title, total!!, sessionMinutes,
                        day.takeIf { mode == TaskWhen.BY_DAY },
                        day.takeIf { mode == TaskWhen.ON_DAY }
                    )
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("Adaugă sarcina") }
            if (!valid) {
                Text(
                    when {
                        title.isBlank() -> "Scrie ce ai de făcut."
                        (total ?: 0) < 15 -> "Alege cât durează în total."
                        else -> "Alege ziua."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }
}

private fun weekLabel(weekStart: LocalDate, today: LocalDate): String =
    when (java.time.temporal.ChronoUnit.WEEKS.between(PeriodReport.weekStart(today), weekStart)) {
        0L -> "asta"
        1L -> "viitoare"
        else -> "de peste două săptămâni"
    }

@Composable
private fun Section(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

/** Zilele din săptămâna asta și următoarele două, ca un mic calendar; cele trecute nu se pot alege. */
@Composable
private fun ThreeWeekPicker(firstDay: LocalDate, today: LocalDate, selected: LocalDate?, onPick: (LocalDate) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { i ->
                    Text(
                        Availability.shortDayName(i),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            (0..2).forEach { w ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (0..6).forEach { i ->
                        val d = firstDay.plusDays(w * 7L + i)
                        val past = d.isBefore(today)
                        val isSelected = d == selected
                        Surface(
                            onClick = { onPick(d) },
                            enabled = !past,
                            shape = CircleShape,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.primary
                                d == today -> MaterialTheme.colorScheme.primaryContainer
                                else -> Color.Transparent
                            },
                            modifier = Modifier.weight(1f).aspectRatio(1f)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    "${d.dayOfMonth}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected || d == today) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.onPrimary
                                        past -> MaterialTheme.colorScheme.outlineVariant
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
