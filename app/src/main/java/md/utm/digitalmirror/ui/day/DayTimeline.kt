package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.data.calendar.CalendarReader
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.domain.SleepPlanner
import md.utm.digitalmirror.domain.TimelineGaps
import md.utm.digitalmirror.domain.TimelineTravel
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

    /** Un eveniment din calendarul telefonului (doar de citit; locul se poate alege). */
    class Cal(val event: CalendarReader.Event) : TimelineEntry {
        override val startMinute: Int get() = event.startMinute
        override val key: String get() = "c${event.id}-${event.startMinute}"
    }

    class Travel(val leg: TimelineTravel.Leg, val toName: String) : TimelineEntry {
        override val startMinute: Int get() = leg.start
        override val key: String get() = "t${leg.start}-${leg.fromPlaceId}-${leg.toPlaceId}"
    }

    /** Trezirea propusă, primul rând al zilei. */
    class Wake(val sleep: DayViewModel.SleepUi) : TimelineEntry {
        override val startMinute: Int get() = sleep.today.wake
        override val key: String get() = "wake"
    }

    /** Culcarea propusă, ultimul rând al zilei (poate fi după miezul nopții). */
    class Bed(val sleep: DayViewModel.SleepUi) : TimelineEntry {
        override val startMinute: Int get() = sleep.bedtime
        override val key: String get() = "bed"
    }

    /** O masă, în locul liber ales din fereastra ei. */
    class Meal(val meal: MealPlanner.Slot) : TimelineEntry {
        override val startMinute: Int get() = meal.start
        override val key: String get() = "m${meal.kind.name}"
    }

    /** Pregătirea de plecare de acasă, chiar înaintea drumului. */
    class Prep(val leg: TimelineTravel.Leg) : TimelineEntry {
        override val startMinute: Int get() = leg.prepStart
        override val key: String get() = "p${leg.start}-${leg.toPlaceId}"
    }
}

/** „☀️ Trezire” cu explicația: ora ta obișnuită sau mai devreme, pentru că ziua începe devreme. */
@Composable
internal fun TimelineWakeItem(sleep: DayViewModel.SleepUi) {
    val plan = sleep.today
    val why = when (plan.reason) {
        SleepPlanner.Reason.USUAL -> "ora ta obișnuită"
        SleepPlanner.Reason.EARLY_START ->
            "mai devreme decât de obicei, ca să ai timp de dimineață înainte de ${SleepPlanner.clock(sleep.firstStart ?: plan.wake)}"
        SleepPlanner.Reason.DEFAULT -> "propunere de pornire; o potrivesc după ce aflu cum dormi"
    }
    SleepRow(emoji = "☀️", time = SleepPlanner.clock(plan.wake), title = "Trezire", subtitle = why)
}

/** „🌙 Culcare” cu explicația: cât dormi de obicei, până la trezirea de mâine. */
@Composable
internal fun TimelineBedItem(sleep: DayViewModel.SleepUi) {
    val plan = sleep.tomorrow
    val parts = mutableListOf(
        "ca să dormi ~${formatDuration(plan.sleepMinutes)} până la trezirea de mâine, ${SleepPlanner.clock(plan.wake)}"
    )
    // mai devreme decât de obicei: spunem cu cât, ca să nu pară o oră oarecare
    val usual = plan.usualBedtime
    if (usual != null && usual - sleep.bedtime >= 30) {
        parts += "cu ${formatDuration(usual - sleep.bedtime)} mai devreme decât de obicei (${SleepPlanner.clock(usual)})"
    }
    SleepRow(emoji = "🌙", time = SleepPlanner.clock(sleep.bedtime), title = "Culcare", subtitle = parts.joinToString(", "))
}

@Composable
private fun SleepRow(emoji: String, time: String, title: String, subtitle: String) {
    TimelineItem(
        time = time,
        marker = {
            Text(emoji, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** „🍽 Prânz · ~30 min”: locul păstrat pentru masă, ales din fereastra ei. */
@Composable
internal fun TimelineMealItem(meal: MealPlanner.Slot, startLabel: String) {
    TimelineItem(
        time = startLabel,
        marker = {
            Text(
                meal.kind.emoji,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                meal.kind.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "~${formatDuration(meal.end - meal.start)} · loc păstrat pentru masă",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Mesele pentru care azi n-a fost loc în fereastră: o notă blândă, nu o eroare. */
@Composable
internal fun MissingMealsNote(missing: List<MealPlanner.Window>) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            missing.forEach { w ->
                Text(
                    "${w.kind.emoji} Azi n-ai pauză de ${w.kind.label.lowercase()} " +
                            "între ${clock(w.from)} și ${clock(w.to)}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            Text(
                if (missing.size == 1) "Ia ceva la pachet sau mănâncă între activități."
                else "Ia ceva la pachet sau mănâncă între activități, când poți.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun clock(minute: Int): String = "%d:%02d".format(minute / 60, minute % 60)

/** „Pregătire de plecare · 15 min”: îmbrăcat, geantă, încălțat. */
@Composable
internal fun TimelinePrepItem(leg: TimelineTravel.Leg, startLabel: String) {
    TimelineItem(
        time = startLabel,
        marker = {
            Text(
                "🧥",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                "Pregătire de plecare",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "~${formatDuration(leg.prepMinutes)} · îmbrăcat, geantă",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Un drum: „Drum spre Facultate · ~35 min (+10 marjă)”; cu avertisment când nu încape. */
@Composable
internal fun TimelineTravelItem(leg: TimelineTravel.Leg, toName: String, startLabel: String) {
    TimelineItem(
        time = startLabel,
        marker = {
            Text(
                "🚶",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            val margin = (leg.end - leg.start - leg.travelMinutes).coerceAtLeast(0)
            Text(
                "Drum spre $toName",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "~${formatDuration(leg.travelMinutes)}" +
                        (if (margin > 0) " + ${formatDuration(margin)} marjă" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (leg.tight) {
                Text(
                    "Nu încape: ar trebui să pleci mai devreme",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
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
