package md.utm.digitalmirror.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.ui.theme.Fraunces

/**
 * Durata cu − / + și valori rapide, în loc de ore și minute scrise de mână.
 * [step] spune cu cât urcă sau coboară de la o valoare (de ex. 15 min sub o oră, 30 peste).
 */
@Composable
fun DurationStepper(
    minutes: Int?,
    onChange: (Int) -> Unit,
    quick: List<Int>,
    min: Int = 5,
    max: Int = 40 * 60,
    step: (Int) -> Int = { if (it < 60) 5 else 15 }
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = { minutes?.let { onChange((it - step(it - 1)).coerceAtLeast(min)) } },
                    enabled = (minutes ?: 0) > min
                ) { Icon(Icons.Outlined.Remove, contentDescription = "Mai puțin") }
                Text(
                    minutes?.let(::formatDuration) ?: "—",
                    fontFamily = Fraunces,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = if (minutes == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = { onChange(((minutes ?: 0) + step(minutes ?: 0)).coerceAtMost(max)) },
                    enabled = (minutes ?: 0) < max
                ) { Icon(Icons.Outlined.Add, contentDescription = "Mai mult") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            quick.forEach { value ->
                FilterChip(selected = minutes == value, onClick = { onChange(value) }, label = { Text(formatDuration(value)) })
            }
        }
    }
}
