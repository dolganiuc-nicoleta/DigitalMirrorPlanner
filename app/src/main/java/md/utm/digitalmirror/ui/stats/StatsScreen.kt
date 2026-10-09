package md.utm.digitalmirror.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.domain.EstimateEvaluation
import md.utm.digitalmirror.domain.ProductivityMap
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(vm: StatsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val productivity by vm.productivity.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Statistici") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // alegerea perioadei
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatsPeriod.entries.forEach { period ->
                        FilterChip(
                            selected = state.period == period,
                            onClick = { vm.setPeriod(period) },
                            label = { Text(period.label) }
                        )
                    }
                }
            }

            // precizia estimărilor: întâi, pentru că e ce contează cel mai mult
            state.evaluation?.let { evaluation ->
                item { EvaluationCard(evaluation) }
            }

            // harta orelor productive
            productivity?.takeIf { it.total > 0 }?.let { map ->
                item { ProductivityCard(map) }
            }

            val total = state.total
            if (total == null) {
                item { EmptyHint() }
            } else {
                item { SummaryCard(total) }

                item {
                    Text(
                        "Estimat vs. real pe categorii",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                items(state.categories, key = { it.name }) { stat ->
                    CategoryCard(stat)
                }

                item {
                    Text(
                        "Se iau în calcul doar activitățile finalizate complet, cu durată estimată.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// grila zi × interval orar, colorată după rata de finalizare
@Composable
private fun ProductivityCard(map: ProductivityMap.Result) {
    val days = listOf("L", "Ma", "Mi", "J", "V", "S", "D")
    val filled = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Când finalizezi cel mai des", style = MaterialTheme.typography.titleMedium)

            // capul de tabel: intervalele orare
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(28.dp))
                ProductivityMap.SLOT_STARTS.forEach { start ->
                    Text(
                        "%02d".format(start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            (1..7).forEach { day ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        days[day - 1],
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.width(28.dp)
                    )
                    ProductivityMap.SLOT_STARTS.indices.forEach { slot ->
                        val cell = map.cell(day, slot)
                        val rate = cell.rate
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (rate == null) empty
                                    else filled.copy(alpha = (0.15f + 0.85f * rate.toFloat()))
                                )
                        )
                    }
                }
            }

            Text(
                "Mai închis = finalizezi mai des activitățile începute atunci · gri = fără date",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val conclusions = buildList {
                map.bestSlot?.let { add("Cel mai bun interval al tău: ${it.label} (${it.percent}% finalizate · ${activitiesLabel(it.total)})") }
                map.bestDay?.let { add("Cea mai bună zi: ${it.label} (${it.percent}% finalizate · ${activitiesLabel(it.total)})") }
            }
            if (conclusions.isEmpty()) {
                Text(
                    "Încă nu sunt destule activități pentru o concluzie (minim ${ProductivityMap.MIN_FOR_CONCLUSION} într-un interval).",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                conclusions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

// estimările utilizatorului (M0) față de propunerile aplicației (M3), pe aceleași activități
@Composable
private fun EvaluationCard(e: EstimateEvaluation.Result) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Precizia estimărilor", style = MaterialTheme.typography.titleMedium)

            if (!e.enoughData) {
                Text(
                    "Încă nu sunt destule activități de comparat (${e.comparedCount} din ${EstimateEvaluation.MIN_RECORDS}).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Se iau în calcul activitățile finalizate cu cronometrul sau cu durata corectată, " +
                            "la care ai spus cât crezi că durează și ai primit o propunere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ErrorTile(
                    label = "Estimările tale",
                    value = "±${e.userMae} min",
                    modifier = Modifier.weight(1f),
                    highlighted = false
                )
                ErrorTile(
                    label = "Estimările aplicației",
                    value = "±${e.appMae} min",
                    modifier = Modifier.weight(1f),
                    highlighted = (e.appMae ?: 0) < (e.userMae ?: 0)
                )
            }
            Text(
                "eroare medie față de durata reală · ${activitiesLabel(e.comparedCount)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val lines = buildList {
                e.improvementPercent?.let {
                    add(
                        if (it > 0) "Propunerile aplicației greșesc cu $it% mai puțin decât estimările tale"
                        else "Deocamdată estimările tale sunt la fel de bune sau mai bune"
                    )
                }
                e.appCloserPercent?.let { add("Aplicația a fost mai aproape de realitate în $it% din cazuri") }
                e.coveragePercent?.let {
                    add("Durata reală a fost în intervalul propus în $it% din cazuri (ținta e 80%)")
                }
                e.acceptedPercent?.let {
                    add("Ai ales propunerea aplicației în $it% din cazurile în care era diferită de estimarea ta")
                }
            }
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ErrorTile(label: String, value: String, modifier: Modifier, highlighted: Boolean) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun EmptyHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Încă nu sunt date pentru această perioadă", fontWeight = FontWeight.Medium)
            Text(
                "Finalizează câteva activități care au o durată estimată și statisticile vor apărea aici.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// rezumatul general, cu concluzia formulată în cuvinte
@Composable
private fun SummaryCard(total: CategoryStat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                deviationSentence(total.deviationPercent),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                "${activitiesLabel(total.count)} · estimat ${formatDuration(total.estimatedMinutes)}" +
                        " · real ${formatDuration(total.actualMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun CategoryCard(stat: CategoryStat) {
    val maxValue = maxOf(stat.estimatedMinutes, stat.actualMinutes).coerceAtLeast(1)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stat.name, fontWeight = FontWeight.Medium)
                    Text(
                        "${activitiesLabel(stat.count)} · medie reală ${formatDuration(stat.averageActualMinutes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DeviationBadge(stat.deviationPercent)
            }

            ComparisonBar(
                label = "Estimat",
                minutes = stat.estimatedMinutes,
                maxMinutes = maxValue,
                color = MaterialTheme.colorScheme.outline
            )
            ComparisonBar(
                label = "Real",
                minutes = stat.actualMinutes,
                maxMinutes = maxValue,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// eticheta „+35%” / „−10%” / „precis”
@Composable
private fun DeviationBadge(percent: Int) {
    val (text, color) = when {
        abs(percent) <= 10 -> "precis" to MaterialTheme.colorScheme.primary
        percent > 0 -> "+$percent%" to MaterialTheme.colorScheme.error
        else -> "−${abs(percent)}%" to MaterialTheme.colorScheme.tertiary
    }
    Text(text, color = color, fontWeight = FontWeight.Medium)
}

// o bară orizontală proporțională cu numărul de minute
@Composable
private fun ComparisonBar(label: String, minutes: Int, maxMinutes: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(56.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(minutes.toFloat() / maxMinutes)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color)
            )
        }
        Text(
            formatDuration(minutes),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp)
        )
    }
}

private fun deviationSentence(percent: Int): String = when {
    abs(percent) <= 10 -> "Estimările tale sunt destul de precise"
    percent > 0 -> "Activitățile îți iau în medie cu $percent% mai mult decât estimezi"
    else -> "Activitățile îți iau în medie cu ${abs(percent)}% mai puțin decât estimezi"
}

private fun activitiesLabel(count: Int) =
    if (count == 1) "1 activitate" else "$count activități"

// 45 → „45 min”, 80 → „1h 20m”
private fun formatDuration(minutes: Int): String =
    if (minutes < 60) "$minutes min"
    else if (minutes % 60 == 0) "${minutes / 60}h"
    else "${minutes / 60}h ${minutes % 60}m"