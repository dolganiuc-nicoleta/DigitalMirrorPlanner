package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.domain.Availability
import java.time.LocalDate

/** Câte activități are o zi și câte sunt terminate; din asta se desenează punctele de sub zi. */
data class DayDot(val total: Int, val done: Int)

/**
 * Banda cu zilele săptămânii, sus pe ecranul Zi: un tap alege ziua, o glisare orizontală
 * (sau săgețile) schimbă săptămâna. Sub fiecare zi, câte un punct pe activitate (cel mult 3):
 * verde cât e terminat, gri cât mai e de făcut.
 */
@Composable
fun DayWeekStrip(
    selected: LocalDate,
    dots: Map<String, DayDot>,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val monday = selected.minusDays((selected.dayOfWeek.value - 1).toLong())

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .pointerInput(selected) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = {
                        if (total > SWIPE) onSelect(selected.minusDays(7))
                        else if (total < -SWIPE) onSelect(selected.plusDays(7))
                    },
                    onHorizontalDrag = { _, delta -> total += delta }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onSelect(selected.minusDays(7)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(28.dp)) {
            Text("‹")
        }
        (0..6).forEach { i ->
            val date = monday.plusDays(i.toLong())
            val isSelected = date == selected
            val dot = dots[date.toString()]
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(date) }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    Availability.shortDayName(i),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${date.dayOfMonth}",
                        style = MaterialTheme.typography.titleSmall,
                        color = when {
                            isSelected -> MaterialTheme.colorScheme.onPrimary
                            date == today -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                // punctele: terminate (verde) și rămase (gri); un loc gol păstrează înălțimea
                Row(Modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (dot != null) {
                        val shown = minOf(dot.total, 3)
                        // câte puncte verzi: proporțional, dar măcar unul dacă s-a terminat ceva
                        val green = if (dot.done == 0) 0 else maxOf(1, Math.round(shown * dot.done.toFloat() / dot.total))
                        repeat(shown) { index ->
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (index < green) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = { onSelect(selected.plusDays(7)) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.width(28.dp)) {
            Text("›")
        }
    }
}

private const val SWIPE = 80f
