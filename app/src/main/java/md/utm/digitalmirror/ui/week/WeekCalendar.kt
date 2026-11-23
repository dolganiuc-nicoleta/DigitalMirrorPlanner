package md.utm.digitalmirror.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.theme.Fraunces
import md.utm.digitalmirror.ui.week.WeekViewModel.AgendaItem
import md.utm.digitalmirror.ui.week.WeekViewModel.Kind
import java.time.LocalDate
import java.time.YearMonth

private val FULL_MONTHS = listOf(
    "ianuarie", "februarie", "martie", "aprilie", "mai", "iunie",
    "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"
)
private val FULL_DAYS = listOf("Luni", "Marți", "Miercuri", "Joi", "Vineri", "Sâmbătă", "Duminică")

private fun hm(minute: Int) = "%02d:%02d".format(minute / 60 % 24, minute % 60)

private fun hexColor(hex: String?, fallback: Color): Color =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: fallback

private fun timeLabel(item: AgendaItem): String = when {
    item.allDay -> "toată ziua"
    item.start == null -> "fără oră"
    item.end == null -> hm(item.start)
    else -> "${hm(item.start)} – ${hm(item.end)}"
}

/** Cât de plină e ziua: verde = e loc, galben = aproape plin, roșu = prea mult. Null = nimic planificat. */
@Composable
private fun loadColor(cell: WeekViewModel.DayCell?): Color? {
    if (cell == null || cell.dayOff || cell.minutes == 0) return null
    val ratio = if (cell.budget <= 0) 1f else cell.minutes.toFloat() / cell.budget
    return when {
        ratio > 1.15f -> MaterialTheme.colorScheme.error
        ratio > 0.85f -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
}

// ---------------- banda săptămânii ----------------

@Composable
fun WeekStrip(
    weekStart: LocalDate,
    days: List<WeekViewModel.DayCell>,
    selected: LocalDate,
    canPrev: Boolean,
    canNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val end = weekStart.plusDays(6)
                val label = if (weekStart.month == end.month) FULL_MONTHS[weekStart.monthValue - 1]
                else "${FULL_MONTHS[weekStart.monthValue - 1]} – ${FULL_MONTHS[end.monthValue - 1]}"
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp).weight(1f)
                )
                IconButton(onClick = onPrev, enabled = canPrev) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Săptămâna asta")
                }
                IconButton(onClick = onNext, enabled = canNext) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Săptămâna viitoare")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (0..6).forEach { i ->
                    val date = weekStart.plusDays(i.toLong())
                    val isSelected = date == selected
                    val cell = days.firstOrNull { it.date == date }
                    Surface(
                        onClick = { onSelect(date) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        shadowElevation = if (isSelected) 4.dp else 1.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                Availability.shortDayName(i),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${date.dayOfMonth}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (date == today) FontWeight.Bold else FontWeight.Normal,
                                color = when {
                                    isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                                    date == today -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            )
                            Box(
                                Modifier.size(6.dp).clip(CircleShape)
                                    .background(loadColor(cell) ?: Color.Transparent)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------- grila lunii ----------------

@Composable
fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    dots: Map<LocalDate, List<String?>>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val fallbackDot = MaterialTheme.colorScheme.primary
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${FULL_MONTHS[month.monthValue - 1].replaceFirstChar { it.uppercase() }} ${month.year}",
                    fontFamily = Fraunces,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 4.dp).weight(1f)
                )
                IconButton(onClick = onPrev) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Luna trecută")
                }
                IconButton(onClick = onNext) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Luna viitoare")
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { i ->
                    Text(
                        Availability.shortDayName(i),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            val lead = month.atDay(1).dayOfWeek.value - 1          // câte celule goale înainte de 1
            val cells = lead + month.lengthOfMonth()
            val rows = (cells + 6) / 7
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0..6) {
                        val day = r * 7 + c - lead + 1
                        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.TopCenter) {
                            if (day in 1..month.lengthOfMonth()) {
                                val date = month.atDay(day)
                                val isSelected = date == selected
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onSelect(date) }
                                        .padding(horizontal = 4.dp)
                                ) {
                                    Box(
                                        Modifier.size(34.dp).clip(CircleShape)
                                            .background(if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                                            .then(
                                                if (date == today && !isSelected)
                                                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                                else Modifier
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "$day",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected || date == today) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp).padding(top = 3.dp)) {
                                        dots[date].orEmpty().forEach { hex ->
                                            Box(Modifier.size(5.dp).clip(CircleShape).background(hexColor(hex, fallbackDot)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------- antetul zilei alese ----------------

@Composable
fun SelectedDayHeader(
    date: LocalDate,
    cell: WeekViewModel.DayCell?,
    dayOff: Boolean,
    onOpenDay: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            val today = LocalDate.now()
            val prefix = when (date) {
                today -> "Azi, "
                today.plusDays(1) -> "Mâine, "
                else -> "${FULL_DAYS[date.dayOfWeek.value - 1]}, "
            }
            Text(
                "$prefix${date.dayOfMonth} ${FULL_MONTHS[date.monthValue - 1]}",
                fontFamily = Fraunces,
                style = MaterialTheme.typography.titleLarge
            )
            val load = when {
                dayOff -> "Zi liberă"
                cell == null || cell.minutes == 0 -> null
                cell.budget <= 0 -> "Planificat ${formatDuration(cell.minutes)}"
                cell.minutes > cell.budget * 1.15 -> "Planificat ${formatDuration(cell.minutes)}, peste cât reușești de obicei (${formatDuration(cell.budget)})"
                else -> "Planificat ${formatDuration(cell.minutes)} din ~${formatDuration(cell.budget)} cât reușești de obicei"
            }
            load?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        TextButton(onClick = onOpenDay) { Text("Deschide ziua") }
    }
}

// ---------------- ziua ca linie de timp (vederea săptămânii) ----------------

@Composable
fun DayAgendaTimeline(items: List<AgendaItem>, emptyText: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (items.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
            }
            items.forEach { item -> TimelineRow(item) }
        }
    }
}

@Composable
private fun TimelineRow(item: AgendaItem) {
    val done = item.state == "DONE" || item.state == "PARTIAL"
    val accent = accentFor(item)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(62.dp), verticalAlignment = Alignment.CenterVertically) {
            if (done) {
                Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
            } else {
                Box(Modifier.size(8.dp).clip(CircleShape).background(accent.copy(alpha = 0.7f)))
            }
            Spacer(Modifier.width(5.dp))
            Text(
                item.start?.let(::hm) ?: "–",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        val shape = RoundedCornerShape(12.dp)
        Row(
            Modifier
                .weight(1f)
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(if (item.kind == Kind.PROPOSED) Color.Transparent else accent.copy(alpha = if (done) 0.08f else 0.14f))
                .then(if (item.kind == Kind.PROPOSED) Modifier.border(1.dp, accent.copy(alpha = 0.6f), shape) else Modifier)
        ) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(accent.copy(alpha = if (done) 0.4f else 0.85f)))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    kindIcon(item)?.let {
                        Icon(it, null, tint = accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        textDecoration = if (done) TextDecoration.LineThrough else null,
                        color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        timeLabel(item) + kindSuffix(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------- ziua ca listă (vederea lunii) ----------------

@Composable
fun DayAgendaList(items: List<AgendaItem>, emptyText: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            if (items.isEmpty()) {
                Text(emptyText, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            }
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                val done = item.state == "DONE" || item.state == "PARTIAL"
                val accent = accentFor(item)
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.Top) {
                    val icon = when {
                        item.kind == Kind.ACTIVITY && done -> Icons.Outlined.CheckCircle
                        item.kind == Kind.ACTIVITY -> Icons.Outlined.RadioButtonUnchecked
                        else -> kindIcon(item) ?: Icons.Outlined.Schedule
                    }
                    Icon(icon, null, tint = accent, modifier = Modifier.padding(top = 16.dp).size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(kindLabel(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            textDecoration = if (done) TextDecoration.LineThrough else null
                        )
                        Text(timeLabel(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ---------------- comune ----------------

@Composable
private fun accentFor(item: AgendaItem): Color = when (item.kind) {
    Kind.ACTIVITY -> hexColor(item.colorHex, MaterialTheme.colorScheme.primary)
    Kind.PROPOSED -> MaterialTheme.colorScheme.primary
    Kind.EVENT -> MaterialTheme.colorScheme.tertiary
    Kind.FIXED -> MaterialTheme.colorScheme.outline
}

private fun kindIcon(item: AgendaItem): ImageVector? = when (item.kind) {
    Kind.ACTIVITY -> null
    Kind.PROPOSED -> Icons.Outlined.AutoAwesome
    Kind.EVENT -> Icons.Outlined.CalendarMonth
    Kind.FIXED -> Icons.Outlined.Lock
}

private fun kindLabel(item: AgendaItem): String = when (item.kind) {
    Kind.ACTIVITY -> when (item.state) {
        "DONE" -> "Activitate · făcută"
        "PARTIAL" -> "Activitate · făcută parțial"
        "IN_PROGRESS" -> "Activitate · în lucru"
        else -> "Activitate"
    }
    Kind.PROPOSED -> "Propus de plan · încă nescris în zi"
    Kind.EVENT -> "Din calendar"
    Kind.FIXED -> "Program fix"
}

private fun kindSuffix(item: AgendaItem): String = when (item.kind) {
    Kind.PROPOSED -> " · propus"
    Kind.EVENT -> " · calendar"
    Kind.FIXED -> " · fix"
    Kind.ACTIVITY -> if (item.state == "IN_PROGRESS") " · în lucru" else ""
}
