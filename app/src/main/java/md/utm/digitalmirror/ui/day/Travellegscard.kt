package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.domain.TravelEstimator.Mode
import md.utm.digitalmirror.ui.components.formatDuration

/**
 * „Drumuri între locuri”: pentru activitățile zilei care au loc și oră și se țin la locuri diferite,
 * arată cât timp liber e între ele, estimează drumul la cerere și îl poate adăuga în zi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TravelLegsCard(
    legs: List<DayViewModel.LegUi>,
    mode: Mode,
    onModeChange: (Mode) -> Unit,
    onEstimate: (DayViewModel.LegUi) -> Unit,
    onAdd: (DayViewModel.LegUi) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "Drumuri între locuri",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { onModeChange(m) },
                        label = { Text(m.label) }
                    )
                }
            }
            legs.forEach { item -> LegRow(item, onEstimate, onAdd) }
        }
    }
}

@Composable
private fun LegRow(
    item: DayViewModel.LegUi,
    onEstimate: (DayViewModel.LegUi) -> Unit,
    onAdd: (DayViewModel.LegUi) -> Unit
) {
    val leg = item.leg
    val minutes = item.estimate.minutes
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${item.fromName} → ${item.toName}", fontWeight = FontWeight.Medium)
        Text(
            "${leg.from.title} se termină la ${clock(leg.from.endMinute!!)}, " +
                    "${leg.to.title} începe la ${clock(leg.to.startMinute!!)} · " +
                    if (leg.availableMinutes >= 0) "${formatDuration(leg.availableMinutes)} liber"
                    else "activitățile se suprapun",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        item.estimate.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (minutes == null) {
            if (!item.added) {
                OutlinedButton(onClick = { onEstimate(item) }, enabled = !item.estimate.loading) {
                    if (item.estimate.loading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else Text("Estimează drumul")
                }
            }
        } else {
            val fits = leg.fits(minutes)
            Text(
                "~${formatDuration(minutes)} · " +
                        if (fits) "încape, pleci la ${clock(leg.departMinute(minutes))}"
                        else "nu încape: ai nevoie de încă ${formatDuration(leg.shortfall(minutes))}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (fits) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            if (!item.added) {
                Button(onClick = { onAdd(item) }) {
                    Text(if (fits) "Adaugă drumul" else "Adaugă oricum")
                }
            }
        }
        if (item.added) {
            Text(
                "Drumul e adăugat în zi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun clock(minute: Int): String {
    val m = ((minute % (24 * 60)) + 24 * 60) % (24 * 60)
    return "%02d:%02d".format(m / 60, m % 60)
}