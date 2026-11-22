package md.utm.digitalmirror.ui.plan

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.domain.DayArc
import md.utm.digitalmirror.domain.PrepTemplates
import md.utm.digitalmirror.domain.QuickCapture
import md.utm.digitalmirror.ui.components.formatDuration
import md.utm.digitalmirror.ui.theme.Fraunces
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Culorile etichetelor, aceleași ca în foaia „+”. */
private data class KindStyle(val label: String, val icon: ImageVector, val ink: Color, val soft: Color)

private fun styleOf(item: QuickCapture.Item, today: LocalDate): KindStyle = when (item.kind) {
    QuickCapture.Kind.DAY -> {
        val label = item.date?.let { QuickCapture.dayLabel(it, today) } ?: "Azi"
        if (item.date == null || item.date == today) KindStyle(label, Icons.Outlined.WbSunny, Color(0xFF2F5A2A), Color(0xFFE6EFE0))
        else KindStyle(label, Icons.Outlined.WbSunny, Color(0xFF2F45C8), Color(0xFFE3E7FA))
    }
    QuickCapture.Kind.WEEK -> KindStyle("Săptămâna", Icons.Outlined.CalendarMonth, Color(0xFF8A4E17), Color(0xFFF6E3CC))
    QuickCapture.Kind.EVENT -> KindStyle("Eveniment", Icons.Outlined.Flag, Color(0xFF6B3FA0), Color(0xFFEEE3F5))
}

/** Ce înseamnă lucrul în plan, pe o linie: „Mâine, la 18:00 · 1h”, „6h, împărțit pe zile”. */
private fun detailOf(item: QuickCapture.Item, today: LocalDate): String = when (item.kind) {
    QuickCapture.Kind.DAY -> listOfNotNull(
        item.date?.let { QuickCapture.dayLabel(it, today) } ?: "Azi",
        item.startMinute?.let { "la ${DayArc.clock(it)}" } ?: item.dayPart?.label,
        item.minutes?.let { formatDuration(it) }
    ).joinToString(" · ").let { if (item.startMinute == null && item.dayPart == null) "$it · ora o propun eu" else it }
    QuickCapture.Kind.WEEK -> listOfNotNull(
        item.minutes?.let { formatDuration(it) } ?: "2h (poți schimba)",
        "împărțit pe zile",
        item.deadline?.let { "până ${QuickCapture.dayLabel(it, today).lowercase()}" }
    ).joinToString(" · ")
    QuickCapture.Kind.EVENT -> {
        val t = PrepTemplates.byKey(item.templateKey ?: "other")
        listOfNotNull(
            item.date?.let { QuickCapture.dayLabel(it, today) } ?: "alege ziua",
            "${t.steps.size} pași de pregătire (${t.label.lowercase()})"
        ).joinToString(" · ")
    }
}

/**
 * „Ce ai de făcut?”: scrii (sau dictezi) tot ce ai în cap; vezi imediat ce am înțeles și unde merge fiecare lucru.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(onBack: () -> Unit, vm: CaptureViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val today = LocalDate.now()
    var pickingDateFor by remember { mutableStateOf<QuickCapture.Item?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // dictarea, cu recunoașterea vocală a telefonului
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.appendDictated(it) }
        }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 20.dp)
        ) {
            // antetul
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Ce ai de făcut?", fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp)
                    Text(
                        "Scrie tot ce ai în cap, cum îți vine. Pun eu fiecare lucru unde trebuie.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalIconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Închide")
                }
            }

            // câmpul
            Box(Modifier.padding(top = 16.dp)) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = vm::setText,
                    placeholder = { Text("sună la bancă, sală mâine la 18, licență 6h săptămâna asta, examen PAD joi") },
                    minLines = 3,
                    maxLines = 6,
                    shape = RoundedCornerShape(20.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                FilledTonalIconButton(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ro-RO")
                            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Spune ce ai de făcut")
                        try {
                            dictate.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "Telefonul nu are recunoaștere vocală.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(44.dp)
                ) {
                    Icon(Icons.Outlined.Mic, contentDescription = "Dictează")
                }
            }

            // ce am înțeles
            if (state.items.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (state.items.size == 1) "Am înțeles 1 lucru" else "Am înțeles ${state.items.size} lucruri",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text("atinge eticheta ca să schimbi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.items) { item ->
                    UnderstoodRow(
                        item = item,
                        style = styleOf(item, today),
                        detail = detailOf(item, today),
                        onCycle = { vm.cycleKind(item) },
                        onPickDate = { pickingDateFor = item }
                    )
                }
            }

            // confirmarea
            Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        vm.confirm { message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            onBack()
                        }
                    },
                    enabled = state.ready && !state.working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface
                    ),
                    modifier = Modifier.fillMaxWidth().height(54.dp)
                ) {
                    Text(if (state.working) "Pun în plan…" else "Pune-le în plan", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Text(
                    when {
                        state.items.any { it.kind == QuickCapture.Kind.EVENT && it.date == null } -> "Alege ziua evenimentului, ca să-ți pot planifica pregătirea."
                        state.items.isEmpty() -> "Desparte lucrurile prin virgulă sau rând nou."
                        else -> "Le vezi apoi pe arcul zilei. Poți schimba orice după."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    pickingDateFor?.let { item ->
        val state = rememberDatePickerState(
            initialSelectedDateMillis = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis > today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDateFor = null },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        vm.setEventDate(item, Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    pickingDateFor = null
                }) { Text("Alege") }
            },
            dismissButton = { TextButton(onClick = { pickingDateFor = null }) { Text("Anulează") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun UnderstoodRow(
    item: QuickCapture.Item,
    style: KindStyle,
    detail: String,
    onCycle: () -> Unit,
    onPickDate: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(style.soft), contentAlignment = Alignment.Center) {
                Icon(style.icon, contentDescription = null, tint = style.ink, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (item.kind == QuickCapture.Kind.EVENT) {
                    TextButton(onClick = onPickDate, contentPadding = PaddingValues(0.dp), modifier = Modifier.height(32.dp)) {
                        Text(if (item.date == null) "Alege ziua" else "Altă zi")
                    }
                }
            }
            // eticheta: atingi și schimbi tipul (Zi → Săptămâna → Eveniment)
            Surface(
                onClick = onCycle,
                shape = RoundedCornerShape(999.dp),
                color = style.soft,
                contentColor = style.ink,
                modifier = Modifier.heightIn(min = 32.dp)
            ) {
                Box(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                    Text(style.label, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
