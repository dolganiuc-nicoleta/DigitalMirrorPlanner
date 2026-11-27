package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
        SleepPlanner.Reason.CHOSEN -> "ora aleasă de tine pentru ziua asta"
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

// culorile rândurilor discrete: mesele calde, ca pe arc
private val MealSoft = Color(0xFFF6E3CC)
private val MealInk = Color(0xFF8A4E17)

/**
 * Un rând discret (masă, pregătire, drum): ora, o iconiță mică, un text pe o linie.
 * Lucrurile care leagă ziua nu trebuie să arate la fel de important ca un curs.
 */
@Composable
private fun QuietRow(
    time: String,
    icon: ImageVector,
    iconTint: Color,
    iconBackground: Color?,
    content: @Composable () -> Unit
) {
    TimelineItem(
        time = time,
        marker = {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconBackground ?: Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
            }
        }
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp), contentAlignment = Alignment.CenterStart) { content() }
    }
}

/** „Prânz · 30 min · loc păstrat”: locul păstrat pentru masă, ales din fereastra ei. */
@Composable
internal fun TimelineMealItem(meal: MealPlanner.Slot, startLabel: String) {
    QuietRow(startLabel, Icons.Outlined.Restaurant, MealInk, MealSoft) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)) {
                    append(meal.kind.label)
                }
                append("  ${formatDuration(meal.end - meal.start)} · ")
                append(
                    when (meal.fit) {
                        MealPlanner.Fit.NORMAL -> "loc păstrat"
                        MealPlanner.Fit.SHORT -> "în pauză, mai scurt"
                        MealPlanner.Fit.EARLY -> "mai devreme, în pauză"
                        MealPlanner.Fit.LATE -> "mai târziu, când ai loc"
                    }
                )
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
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

/** „Te pregătești · pleci la 14:46”: îmbrăcat, geantă, încălțat. */
@Composable
internal fun TimelinePrepItem(leg: TimelineTravel.Leg, startLabel: String) {
    QuietRow(startLabel, Icons.Outlined.Checkroom, MaterialTheme.colorScheme.onSurfaceVariant, null) {
        Text(
            buildAnnotatedString {
                append("Te pregătești · pleci la ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                    append(clock(leg.start))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** „Spre Facultate · ajungi la 15:15 · 19 min”; cu avertisment când nu încape. */
@Composable
internal fun TimelineTravelItem(leg: TimelineTravel.Leg, toName: String, startLabel: String, onClick: (() -> Unit)? = null) {
    QuietRow(startLabel, Icons.AutoMirrored.Outlined.DirectionsWalk, MaterialTheme.colorScheme.onSurfaceVariant, null) {
        // apăsat: harta cu drumul ăsta, modurile de deplasare și drumurile tale reale
        Column(
            if (onClick != null) Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            else Modifier
        ) {
            Text(
                buildAnnotatedString {
                    append("Spre $toName · ajungi la ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)) {
                        append(clock(leg.end))
                    }
                    append(" · ${formatDuration(leg.travelMinutes)}")
                    if (onClick != null) append("  ›")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
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
 * Un rând din linia de timp: ora în stânga, un semn mic, conținutul în dreapta.
 * Fără linie verticală: ierarhia vine din conținut (carduri pentru ce contează, rânduri subțiri pentru rest).
 */
@Composable
internal fun TimelineItem(
    time: String?,
    marker: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            time ?: "",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.width(46.dp).padding(top = 10.dp)
        )
        Box(Modifier.width(32.dp), contentAlignment = Alignment.TopCenter) { marker() }
        Box(Modifier.weight(1f).padding(start = 6.dp)) { content() }
    }
}

/** Semnul unei activități: un punct în culoarea aplicației, sau ✓ când e terminată. */
@Composable
internal fun TimelineMarker(letter: String, done: Boolean) {
    if (done) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Check, contentDescription = "terminată", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
        }
    } else {
        Box(
            Modifier
                .padding(top = 14.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

/** Un spațiu liber: un loc punctat, „2h 15 libere”, și „Adaugă” cu ora deja completată. */
@Composable
internal fun TimelineGapItem(gap: TimelineGaps.Gap, startLabel: String, onAdd: () -> Unit) {
    val dash = MaterialTheme.colorScheme.outline
    TimelineItem(time = startLabel, marker = {}) {
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        color = dash,
                        cornerRadius = CornerRadius(18.dp.toPx()),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
                        )
                    )
                }
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${formatDuration(gap.minutes)} libere",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            FilledTonalButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Text("Adaugă", maxLines = 1)
            }
        }
    }
}
