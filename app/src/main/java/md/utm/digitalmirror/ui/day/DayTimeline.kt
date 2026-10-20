package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.domain.TimelineGaps
import md.utm.digitalmirror.ui.components.formatDuration

/** Un rând din linia de timp: o activitate cu oră sau un spațiu liber. */
sealed interface TimelineEntry {
    val startMinute: Int
    val key: String

    class Act(val activity: Activity) : TimelineEntry {
        override val startMinute: Int get() = activity.plannedStartMinute ?: 0
        override val key: String get() = "a${activity.id}"
    }

    class Gap(val gap: TimelineGaps.Gap) : TimelineEntry {
        override val startMinute: Int get() = gap.start
        override val key: String get() = "g${gap.start}"
    }
}

/**
 * Un rând cu linia verticală: ora în stânga, un semn pe linie și conținutul în dreapta.
 * Linia se prelungește puțin sub rând, ca să se unească cu rândul următor.
 */
@Composable
internal fun TimelineItem(
    time: String?,
    marker: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Text(
            time ?: "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(44.dp).padding(top = 12.dp, end = 6.dp)
        )
        Box(
            modifier = Modifier
                .width(40.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    drawLine(lineColor, Offset(x, 0f), Offset(x, size.height + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
                },
            contentAlignment = Alignment.TopCenter
        ) { marker() }
        Box(Modifier.weight(1f).padding(start = 8.dp)) { content() }
    }
}

/** Cercul de pe linie: litera categoriei, sau ✓ când activitatea e terminată. */
@Composable
internal fun TimelineMarker(letter: String, done: Boolean) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (done) "✓" else letter,
            style = MaterialTheme.typography.titleSmall,
            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

/** Un spațiu liber: „Ai 2 h liberi” și un buton care deschide formularul cu ora deja completată. */
@Composable
internal fun TimelineGapItem(gap: TimelineGaps.Gap, startLabel: String, onAdd: () -> Unit) {
    TimelineItem(
        time = startLabel,
        marker = {
            Box(
                Modifier
                    .padding(top = 12.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            )
        }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Ai ${formatDuration(gap.minutes)} liberi",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                Text("+ Adaugă", maxLines = 1)
            }
        }
    }
}
