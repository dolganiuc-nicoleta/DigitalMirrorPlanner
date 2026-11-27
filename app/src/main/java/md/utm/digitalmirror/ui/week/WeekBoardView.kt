package md.utm.digitalmirror.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import kotlinx.coroutines.delay
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.WeekBoard
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.week.WeekViewModel.BoardDay
import md.utm.digitalmirror.ui.week.WeekViewModel.BoardItem
import md.utm.digitalmirror.ui.week.WeekViewModel.Kind
import java.time.LocalDate
import kotlin.math.roundToInt

private val COLUMN_WIDTH = 168.dp
private val COLUMN_GAP = 10.dp

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
 * Tabla unei săptămâni: o coloană pe zi, derulată pe orizontală. În fiecare zi: încărcarea, ce e fix,
 * sarcinile tale și „+ Adaugă”. Ții apăsat pe o sarcină și o tragi în altă zi; o atingi pentru Deschide / Anulează.
 */
@Composable
fun WeekBoardView(
    days: List<BoardDay>,
    onAdd: (LocalDate, String) -> Unit,
    onMove: (Long, LocalDate) -> Unit,
    onCancel: (Long) -> Unit,
    onOpen: (Long) -> Unit
) {
    val today = LocalDate.now()
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val drag = remember { DragState() }
    val columns = remember { mutableStateMapOf<LocalDate, Rect>() }
    var root by remember { mutableStateOf(Rect.Zero) }
    var adding by remember { mutableStateOf<LocalDate?>(null) }

    val hovered = drag.item?.let { columns.entries.firstOrNull { it.value.contains(drag.pointer) }?.key }

    // săptămâna asta: pornim de la ziua de azi
    LaunchedEffect(days.firstOrNull()?.date) {
        val index = days.indexOfFirst { it.date == today }
        if (index > 0) scroll.scrollTo(with(density) { ((COLUMN_WIDTH + COLUMN_GAP) * index).roundToPx() })
        else scroll.scrollTo(0)
    }

    // la marginea ecranului în timpul tragerii, tabla se derulează singură
    LaunchedEffect(drag.item != null) {
        val edge = with(density) { 56.dp.toPx() }
        while (drag.item != null) {
            val x = drag.pointer.x - root.left
            when {
                x < edge -> scroll.scrollBy(-22f)
                x > root.width - edge -> scroll.scrollBy(22f)
            }
            delay(16)
        }
    }

    Box(Modifier.fillMaxWidth().onGloballyPositioned { root = it.boundsInWindow() }) {
        Row(
            Modifier.horizontalScroll(scroll, enabled = drag.item == null).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(COLUMN_GAP)
        ) {
            days.forEach { day ->
                DayColumn(
                    day = day,
                    isToday = day.date == today,
                    isTarget = hovered == day.date && drag.from != day.date,
                    dragging = drag.item,
                    adding = adding == day.date,
                    onStartAdd = { adding = day.date },
                    onAdd = { text -> onAdd(day.date, text); adding = null },
                    onCancelAdd = { adding = null },
                    onOpen = onOpen,
                    onCancel = onCancel,
                    onBounds = { columns[day.date] = it },
                    onDragStart = { item, pointer, grab, size ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        drag.item = item; drag.from = day.date; drag.pointer = pointer; drag.grab = grab; drag.size = size
                    },
                    onDrag = { delta -> drag.pointer += delta },
                    onDragEnd = {
                        val item = drag.item
                        val target = columns.entries.firstOrNull { it.value.contains(drag.pointer) }?.key
                        if (item?.activityId != null && target != null && target != drag.from) onMove(item.activityId, target)
                        drag.item = null; drag.from = null
                    },
                    onDragCancel = { drag.item = null; drag.from = null }
                )
            }
        }

        // copia care urmează degetul
        drag.item?.let { item ->
            val topLeft = drag.pointer - drag.grab - Offset(root.left, root.top)
            Box(
                Modifier
                    .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                    .width(with(density) { drag.size.width.toDp() })
            ) {
                ItemCard(item = item, lifted = true)
            }
        }
    }
}

@Composable
private fun DayColumn(
    day: BoardDay,
    isToday: Boolean,
    isTarget: Boolean,
    dragging: BoardItem?,
    adding: Boolean,
    onStartAdd: () -> Unit,
    onAdd: (String) -> Unit,
    onCancelAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onBounds: (Rect) -> Unit,
    onDragStart: (BoardItem, Offset, Offset, IntSize) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit
) {
    val past = day.date.isBefore(LocalDate.now())
    val shape = RoundedCornerShape(20.dp)
    Surface(
        shape = shape,
        color = when {
            isTarget -> MaterialTheme.colorScheme.primaryContainer
            isToday -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier
            .width(COLUMN_WIDTH)
            .then(if (isTarget) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .onGloballyPositioned { onBounds(it.boundsInWindow()) }
            .alpha(if (past) 0.6f else 1f)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // antetul: ziua, data, încărcarea
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Availability.shortDayName(day.date.dayOfWeek.value - 1),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "${day.date.dayOfMonth}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                if (isToday) {
                    Spacer(Modifier.width(6.dp))
                    Text("azi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            LoadBar(day)

            day.items.forEach { item ->
                DraggableItem(
                    item = item,
                    hidden = dragging?.key == item.key,
                    onOpen = onOpen,
                    onCancel = onCancel,
                    onDragStart = onDragStart,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel
                )
            }

            if (!past) {
                if (adding) AddField(onAdd = onAdd, onCancel = onCancelAdd)
                else {
                    TextButton(onClick = onStartAdd, contentPadding = PaddingValues(horizontal = 4.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Adaugă", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/** Bara de încărcare: cât ai pus față de cât încape realist, cu culoarea stării. */
@Composable
private fun LoadBar(day: BoardDay) {
    val load = day.load
    val tint = when (load) {
        WeekBoard.Load.OVER -> MaterialTheme.colorScheme.error
        WeekBoard.Load.FULL -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    val ratio = if (day.capacity <= 0) (if (day.plannedMinutes > 0) 1f else 0f)
    else (day.plannedMinutes.toFloat() / day.capacity).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(ratio).clip(CircleShape).background(tint))
        }
        Text(
            when {
                day.dayOff -> "zi liberă"
                day.capacity <= 0 && day.plannedMinutes == 0 -> "fără loc liber"
                else -> "${formatDuration(day.plannedMinutes)} din ~${formatDuration(day.capacity)}" +
                        if (load == WeekBoard.Load.OVER) " · prea mult" else ""
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (load == WeekBoard.Load.OVER) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DraggableItem(
    item: BoardItem,
    hidden: Boolean,
    onOpen: (Long) -> Unit,
    onCancel: (Long) -> Unit,
    onDragStart: (BoardItem, Offset, Offset, IntSize) -> Unit,
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
                        onDragStart = { grab -> onDragStart(item, position + grab, grab, size) },
                        onDrag = { change, delta -> change.consume(); onDrag(delta) },
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel
                    )
                } else Modifier
            )
    ) {
        ItemCard(item = item, lifted = false, onClick = if (item.activityId != null) ({ menu = true }) else null)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Deschide") }, onClick = { menu = false; item.activityId?.let(onOpen) })
            if (item.movable) {
                DropdownMenuItem(
                    text = { Text("Anulează", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; item.activityId?.let(onCancel) }
                )
            }
        }
    }
}

/** Cardul unui lucru: banda în culoarea categoriei, titlul, ora sau durata. Cele fixe sunt gri, cu lacăt. */
@Composable
private fun ItemCard(item: BoardItem, lifted: Boolean, onClick: (() -> Unit)? = null) {
    val done = item.state == "DONE" || item.state == "PARTIAL"
    val accent = when (item.kind) {
        Kind.ACTIVITY -> color(item.colorHex, MaterialTheme.colorScheme.primary)
        Kind.EVENT -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.outline
    }
    val fixed = item.kind != Kind.ACTIVITY
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (fixed) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surface,
        shadowElevation = if (lifted) 10.dp else if (fixed) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick) else Modifier)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent.copy(alpha = if (done) 0.4f else 0.9f)))
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        done -> Icon(Icons.Outlined.CheckCircle, null, tint = accent, modifier = Modifier.size(13.dp))
                        item.kind == Kind.EVENT -> Icon(Icons.Outlined.CalendarMonth, null, tint = accent, modifier = Modifier.size(13.dp))
                        item.kind == Kind.FIXED -> Icon(Icons.Outlined.Lock, null, tint = accent, modifier = Modifier.size(13.dp))
                        else -> null
                    }?.let { Spacer(Modifier.width(4.dp)) }
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium,
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
                        item.start != null -> "${hm(item.start)} · ${formatDuration(item.minutes)}"
                        else -> formatDuration(item.minutes)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** „+ Adaugă”: scrii liber, de ex. „licență 2h, sală 1h”, și apeși Gata pe tastatură. */
@Composable
private fun AddField(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("licență 2h", style = MaterialTheme.typography.bodySmall) },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) else onCancel() }),
            modifier = Modifier.fillMaxWidth()
        )
        Row {
            TextButton(onClick = onCancel, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Renunță") }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = { onAdd(text) },
                enabled = text.isNotBlank(),
                contentPadding = PaddingValues(horizontal = 6.dp)
            ) { Text("Adaugă") }
        }
    }
}
