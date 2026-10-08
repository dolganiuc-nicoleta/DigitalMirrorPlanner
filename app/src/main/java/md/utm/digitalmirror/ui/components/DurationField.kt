package md.utm.digitalmirror.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// valorile rapide, în minute
private val QUICK_VALUES = listOf(15, 30, 45, 60, 90, 120, 180)

/**
 * Câmp de durată cu ore și minute separate, plus butoane rapide (15 min, 30 min … 3 h).
 * Valoarea se transmite mereu în minute; null = necompletat.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DurationField(
    label: String,
    minutes: Int?,
    onChange: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    showQuickValues: Boolean = true
) {
    var hoursText by remember { mutableStateOf(minutes?.let { (it / 60).takeIf { h -> h > 0 }?.toString() } ?: "") }
    var minutesText by remember { mutableStateOf(minutes?.let { (it % 60).toString() } ?: "") }

    // dacă valoarea se schimbă din afară (de ex. „Folosește ~1 h 50”), actualizăm câmpurile
    LaunchedEffect(minutes) {
        if (total(hoursText, minutesText) != minutes) {
            hoursText = minutes?.let { (it / 60).takeIf { h -> h > 0 }?.toString() } ?: ""
            minutesText = minutes?.let { (it % 60).toString() } ?: ""
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = hoursText,
                onValueChange = { input ->
                    hoursText = input.filter { it.isDigit() }.take(2)
                    onChange(total(hoursText, minutesText))
                },
                label = { Text("ore") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(88.dp)
            )
            OutlinedTextField(
                value = minutesText,
                onValueChange = { input ->
                    minutesText = input.filter { it.isDigit() }.take(3)
                    onChange(total(hoursText, minutesText))
                },
                label = { Text("minute") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.width(96.dp)
            )
            minutes?.takeIf { it > 0 }?.let {
                Text(
                    "= ${formatDuration(it)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (showQuickValues) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                QUICK_VALUES.forEach { value ->
                    FilterChip(
                        selected = minutes == value,
                        onClick = {
                            hoursText = (value / 60).takeIf { it > 0 }?.toString() ?: ""
                            minutesText = (value % 60).toString()
                            onChange(value)
                        },
                        label = { Text(formatDuration(value)) }
                    )
                }
            }
        }
    }
}

// totalul în minute; „2” ore și „” minute = 120; ambele goale = null
private fun total(hoursText: String, minutesText: String): Int? {
    if (hoursText.isBlank() && minutesText.isBlank()) return null
    return (hoursText.toIntOrNull() ?: 0) * 60 + (minutesText.toIntOrNull() ?: 0)
}

/** 45 → „45 min”, 110 → „1 h 50”, 120 → „2 h”. Folosit în toată aplicația. */
fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}