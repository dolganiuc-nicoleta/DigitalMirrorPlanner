package md.utm.digitalmirror.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.WeekBoard
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.theme.Fraunces
import md.utm.digitalmirror.ui.week.WeekViewModel.BoardDay
import md.utm.digitalmirror.ui.week.WeekViewModel.BoardItem
import md.utm.digitalmirror.ui.week.WeekViewModel.Kind
import java.time.LocalDate
import kotlin.math.roundToInt

private val MONTHS = listOf(
    "ianuarie", "februarie", "martie", "aprilie", "mai", "iunie",
    "iulie", "august", "septembrie", "octombrie", "noiembrie", "decembrie"
)
private val DAYS = listOf("Luni", "Marți", "Miercuri", "Joi", "Vineri", "Sâmbătă", "Duminică")

private fun hm(minute: Int) = "%d:%02d".format((minute / 60) % 24, minute % 60)

private fun color(hex: String?, fallback: Color): Color =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: fallback

/** Ce se trage acum: lucrul, ziua din care pleacă, unde e degetul (în fereastră) și de unde l-a apucat. */
private class DragState {
    var item by mutableStateOf<BoardItem?>(null)
    var from by mutableStateOf<LocalDate?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var grab by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
}

/**
 * Săptămâna ca bandă de 7 zile (toate vizibile, cu încărcarea fiecăreia) și, dedesubt, ziua aleasă.
 * În ziua aleasă scrii ce ai de făcut („+ Adaugă”), atingi o sarcină pentru Deschide / Anulează,
 * sau o ții apăsat și o tragi pe altă zi din bandă.
 */
@Composable
fun WeekDayPlanner(
    days: List<BoardDay>,
    offset: Int,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onAdd: (LocalDate, String) -> Unit,
    onMove: (Long, LocalDate) -> Unit,
    onCancel: (Long) -> Unit,
    onOpen: (Long) -> Unit,
    onEdit: (Long, LocalDate) -> Unit = { _, _ -> }
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val drag = remember { DragState() }
    val chips = remember { mutableStateMapOf<LocalDate, Rect>() }
    var root by remember { mutableStateOf(Rect.Zero) }
    var adding by remember { mutableStateOf(false) }
    val day = days.firstOrNull { it.date == selected } ?: days.first()
    LaunchedEffect(day.date) { adding = false }

    val hovered = drag.item?.let { chips.entries.firstOrNull { it.value.contains(drag.pointer) }?.key }

    Box(Modifier.fillMaxWidth().onGloballyPositioned { root = it.boundsInWindow() }) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WeekStripCard(
                days = days,
                offset = offset,
                selected = day.date,
                hovered = hovered?.takeIf { it != drag.from },
                dragging = drag.item != null,
                onSelect = onSelect,
                onPrev = onPrev,
                onNext = onNext,
                onBounds = { date, rect -> chips[date] = rect }
            )

            DayHeader(day)

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (day.items.isEmpty()) {
                        Text(
                            if (day.dayOff) "Zi liberă." else "Nimic planificat încă.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(4.dp)
                        )
                    }
                    day.items.forEach { item ->
                        DraggableRow(
                            item = item,
                            hidden = drag.item?.key == item.key,
                            onOpen = onOpen,
                            onEdit = { id -> onEdit(id, day.date) },
                            onCancel = onCancel,
                            onDragStart = { pointer, grab, size ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                drag.item = item; drag.from = day.date
                                drag.pointer = pointer; drag.grab = grab; drag.size = size
                            },
                            onDrag = { delta -> drag.pointer += delta },
                            onDragEnd = {
                                val target = chips.entries.firstOrNull { it.value.contains(drag.pointer) }?.key
                                val id = drag.item?.activityId
                                if (id != null && target != null && target != drag.from) {
                                    onMove(id, target)
                                    onSelect(target)       // ziua în care a ajuns, ca să se vadă unde e acum
                                }
                                drag.item = null; drag.from = null
                            },
                            onDragCancel = { drag.item = null; drag.from = null }
                        )
                    }
                    if (!day.date.isBefore(LocalDate.now())) {
                        if (adding) AddField(onAdd = { onAdd(day.date, it); adding = false }, onCancel = { adding = false })
                        else TextButton(onClick = { adding = true }) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Adaugă ce ai de făcut")
                        }
                    }
                }
            }
        }

        // copia care urmează degetul
        drag.item?.let { item ->
            val topLeft = drag.pointer - drag.grab - Offset(root.left, root.top)
            Box(
                Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .width(with(density) { drag.size.width.toDp() })
            ) { ItemRow(item = item, lifted = true) }
        }
    }
}

/** Banda: „SĂPTĂMÂNA VIITOARE · 30 nov – 6 dec” cu săgeți, apoi cele 7 zile cu un punct de încărcare. */
@Composable
private fun WeekStripCard(
    days: List<BoardDay>,
    offset: Int,
    selected: LocalDate,
    hovered: LocalDate?,
    dragging: Boolean,
    onSelect: (LocalDate) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onBounds: (LocalDate, Rect) -> Unit
) {
    val today = LocalDate.now()
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.padding(start = 4.dp).weight(1f)) {
                    Text(
                        when (offset) { 0 -> "SĂPTĂMÂNA ASTA"; 1 -> "SĂPTĂMÂNA VIITOARE"; else -> "PESTE DOUĂ SĂPTĂMÂNI" },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    val first = days.first().date
                    val last = days.last().date
                    Text(
                        "${first.dayOfMonth} ${MONTHS[first.monthValue - 1].take(3)} – ${last.dayOfMonth} ${MONTHS[last.monthValue - 1].take(3)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onPrev, enabled = offset > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Săptămâna dinainte")
                }
                IconButton(onClick = onNext, enabled = offset < 2) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Săptămâna următoare")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                days.forEach { d ->
                    val isSelected = d.date == selected
                    val isTarget = d.date == hovered
                    val shape = RoundedCornerShape(16.dp)
                    Surface(
                        onClick = { onSelect(d.date) },
                        shape = shape,
                        color = when {
                            isTarget -> MaterialTheme.colorScheme.primary
                            isSelected -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        },
                        shadowElevation = if (isSelected || isTarget) 4.dp else 1.dp,
                        modifier = Modifier
                            .weight(1f)
                            // în timpul tragerii, zilele se văd ca ținte
                            .then(if (dragging && !isTarget) Modifier.border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), shape) else Modifier)
                            .onGloballyPositioned { onBounds(d.date, it.boundsInWindow()) }
                    ) {
                        val ink = when {
                            isTarget -> MaterialTheme.colorScheme.onPrimary
                            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
                            d.date == today -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                        Column(
                            Modifier.padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(Availability.shortDayName(d.date.dayOfWeek.value - 1), style = MaterialTheme.typography.labelMedium, color = ink)
                            Text(
                                "${d.date.dayOfMonth}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = if (d.date == today) FontWeight.Bold else FontWeight.Normal,
                                color = ink
                            )
                            Box(Modifier.size(6.dp).clip(CircleShape).background(loadColor(d) ?: Color.Transparent))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun loadColor(day: BoardDay): Color? = when (day.load) {
    WeekBoard.Load.EMPTY -> null
    WeekBoard.Load.OK -> MaterialTheme.colorScheme.primary
    WeekBoard.Load.FULL -> MaterialTheme.colorScheme.tertiary
    WeekBoard.Load.OVER -> MaterialTheme.colorScheme.error
}

/** „Marți, 25 noiembrie” și „3 h din ~4 h 30 · prea mult”, cu bara. */
@Composable
private fun DayHeader(day: BoardDay) {
    val today = LocalDate.now()
    val name = when (day.date) {
        today -> "Azi"
        today.plusDays(1) -> "Mâine"
        else -> DAYS[day.date.dayOfWeek.value - 1]
    }
    val over = day.load == WeekBoard.Load.OVER
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$name, ${day.date.dayOfMonth} ${MONTHS[day.date.monthValue - 1]}", fontFamily = Fraunces, style = MaterialTheme.typography.titleLarge)
        if (!day.dayOff && (day.capacity > 0 || day.plannedMinutes > 0)) {
            val ratio = if (day.capacity <= 0) 1f else (day.plannedMinutes.toFloat() / day.capacity).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).clip(CircleShape).background(loadColor(day) ?: Color.Transparent))
            }
        }
        Text(
            when {
                day.dayOff -> "Zi liberă"
                day.capacity <= 0 && day.plannedMinutes == 0 -> "Ziua e plină cu lucruri fixe"
                else -> "${formatDuration(day.plannedMinutes)} planificat din ~${formatDuration(day.capacity)} cât încape" +
                        if (over) " · prea mult" else ""
            } + if (day.fixedMinutes > 0) " · ${formatDuration(day.fixedMinutes)} fixe" else "",
            style = MaterialTheme.typography.bodySmall,
            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DraggableRow(
    item: BoardItem,
    hidden: Boolean,
    onOpen: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDragStart: (Offset, Offset, IntSize) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    var position by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var menu by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .alpha(if (hidden) 0.25f else 1f)
            .onGloballyPositioned { position = it.positionInWindow(); size = it.size }
            .then(
                if (item.movable) Modifier.pointerInput(item.key) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { grab -> onDragStart(position + grab, grab, size) },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel
                    )
                } else Modifier
            )
    ) {
        ItemRow(item = item, lifted = false, onClick = if (item.activityId != null) ({ menu = true }) else null)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Deschide") }, onClick = { menu = false; item.activityId?.let(onOpen) })
            if (item.state == "PLANNED" || item.state == "IN_PROGRESS") {
                DropdownMenuItem(text = { Text("Editează") }, onClick = { menu = false; item.activityId?.let(onEdit) })
            }
            if (item.movable) {
                DropdownMenuItem(
                    text = { Text("Anulează", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; item.activityId?.let(onCancel) }
                )
            }
        }
    }
}

/** Un rând: ora în stânga, cardul în dreapta, cu banda în culoarea categoriei. Cele fixe sunt gri, cu lacăt. */
@Composable
private fun ItemRow(item: BoardItem, lifted: Boolean, onClick: (() -> Unit)? = null) {
    val done = item.state == "DONE" || item.state == "PARTIAL"
    val fixed = item.kind != Kind.ACTIVITY
    val accent = when (item.kind) {
        Kind.ACTIVITY -> color(item.colorHex, MaterialTheme.colorScheme.primary)
        Kind.EVENT -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                item.allDay -> "zi"
                item.start != null -> hm(item.start)
                else -> "–"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp)
        )
        val shape = RoundedCornerShape(12.dp)
        Surface(
            shape = shape,
            color = if (fixed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else accent.copy(alpha = if (done) 0.08f else 0.14f),
            shadowElevation = if (lifted) 10.dp else 0.dp,
            modifier = Modifier.weight(1f).then(if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier)
        ) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(5.dp).fillMaxHeight().background(accent.copy(alpha = if (done) 0.4f else 0.85f)))
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = when {
                            done -> Icons.Outlined.CheckCircle
                            item.kind == Kind.EVENT -> Icons.Outlined.CalendarMonth
                            item.kind == Kind.FIXED -> Icons.Outlined.Lock
                            else -> null
                        }
                        if (icon != null) {
                            Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (fixed) FontWeight.Normal else FontWeight.Medium,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            color = if (fixed || done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        when {
                            item.allDay -> "toată ziua"
                            item.start != null -> "${hm(item.start)} – ${hm(item.start + item.minutes)}"
                            else -> formatDuration(item.minutes)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** „+ Adaugă”: scrii liber, de ex. „licență 2h, sală 1h la 18”, și apeși Gata pe tastatură. */
@Composable
private fun AddField(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("licență 2h, sală 1h la 18") },
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) else onCancel() }),
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            TextButton(onClick = onCancel) { Text("Renunță") }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Adaugă") }
        }
    }
}
