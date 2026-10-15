package md.utm.digitalmirror.ui.places

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.prefs.TravelPrefs
import md.utm.digitalmirror.domain.PlaceRules
import md.utm.digitalmirror.domain.TravelEstimator
import md.utm.digitalmirror.domain.TravelEstimator.Mode
import md.utm.digitalmirror.domain.TravelEstimator.Source

/**
 * „Drum între locuri”: alegi două locuri și cum te deplasezi, apoi vezi cât durează drumul.
 * Modul ales rămâne salvat și va fi cel folosit la propunerea drumurilor din zi.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RouteCard(
    places: List<Place>,
    state: RouteUi,
    onEstimate: (Place, Place, Mode) -> Unit
) {
    if (places.size < 2) return   // un drum are nevoie de două locuri

    val context = LocalContext.current
    var fromId by remember { mutableStateOf<Long?>(null) }
    var toId by remember { mutableStateOf<Long?>(null) }
    var mode by remember { mutableStateOf(TravelPrefs.mode(context)) }

    val from = places.find { it.id == fromId }
    val to = places.find { it.id == toId }
    val same = from != null && from.id == to?.id

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Drum între locuri", fontWeight = FontWeight.Medium)
            Text(
                "Vezi cât durează drumul și cum se corectează din drumurile tale reale.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PlaceDropdown("De la", places, fromId) { fromId = it }
            PlaceDropdown("Până la", places, toId) { toId = it }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = { Text(m.label) }
                    )
                }
            }

            Button(
                onClick = { if (from != null && to != null) onEstimate(from, to, mode) },
                enabled = from != null && to != null && !same && !state.loading
            ) {
                if (state.loading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("Estimează drumul")
            }
            if (same) {
                Text(
                    "Alege două locuri diferite.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            state.outcome?.let { o ->
                Text(
                    "${state.fromName} → ${state.toName}: ~${o.estimate.minutes} min",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    explain(o),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// de unde vine estimarea, în cuvinte
private fun explain(o: md.utm.digitalmirror.data.travel.TravelRepository.Outcome): String {
    val distance = PlaceRules.distanceLabel(o.distanceMeters)
    return when (o.estimate.source) {
        Source.ROUTE -> "Calculat de OpenRouteService · $distance pe traseu."
        Source.APPROXIMATION ->
            "Aproximare pentru transport public (distanță $distance, viteză medie și așteptare în stație). " +
                    "Se corectează din drumurile tale detectate."
        Source.LEARNED -> {
            val n = o.observedCount
            "Corectat din $n ${if (n == 1) "drum real" else "drumuri reale"} ale tale " +
                    "(calculul singur ar fi dat ${o.baseMinutes} min, $distance)."
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaceDropdown(label: String, places: List<Place>, selectedId: Long?, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = places.find { it.id == selectedId }?.name ?: "alege un loc",
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            places.forEach { place ->
                DropdownMenuItem(
                    text = { Text(place.name) },
                    onClick = {
                        onSelect(place.id)
                        expanded = false
                    }
                )
            }
        }
    }
}