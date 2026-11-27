package md.utm.digitalmirror.ui.week

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.ui.theme.Fraunces
import java.time.LocalDate

private val MONTHS = listOf("ian", "feb", "mar", "apr", "mai", "iun", "iul", "aug", "sep", "oct", "nov", "dec")

private fun short(date: LocalDate) =
    "${Availability.shortDayName(date.dayOfWeek.value - 1)} ${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}"

/** „‹ Săptămâna asta · 23–29 nov ›” și butonul „✨ Echilibrează”. */
@Composable
fun BoardHeader(
    weekStart: LocalDate,
    offset: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    aiAvailable: Boolean,
    aiLoading: Boolean,
    onBalance: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrev, enabled = offset > 0) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Săptămâna dinainte")
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when (offset) {
                        0 -> "Săptămâna asta"
                        1 -> "Săptămâna viitoare"
                        else -> "Peste două săptămâni"
                    },
                    fontFamily = Fraunces,
                    style = MaterialTheme.typography.titleLarge
                )
                val end = weekStart.plusDays(6)
                Text(
                    "${weekStart.dayOfMonth} ${MONTHS[weekStart.monthValue - 1]} – ${end.dayOfMonth} ${MONTHS[end.monthValue - 1]}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onNext, enabled = offset < 2) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Săptămâna următoare")
            }
        }
        if (aiAvailable) {
            FilledTonalButton(onClick = onBalance, enabled = !aiLoading, modifier = Modifier.fillMaxWidth()) {
                if (aiLoading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Mă uit pe săptămânile tale…")
                } else {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Echilibrează săptămânile")
                }
            }
        }
    }
}

/** Prima dată: ce pleacă din telefon și unde, înainte de orice cerere. */
@Composable
fun AiConsentDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AutoAwesome, null) },
        title = { Text("Echilibrare cu AI") },
        text = {
            Text(
                "Ca să-ți propună ce să muți, aplicația trimite serverului tău, iar el mai departe la Claude (Anthropic): " +
                        "titlurile sarcinilor, cât durează și cât de pline sunt zilele din următoarele 3 săptămâni.\n\n" +
                        "Nu pleacă numele, e-mailul, locurile sau notițele tale. AI-ul doar propune; nu mută nimic fără tine."
            )
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("Am înțeles, continuă") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Nu acum") } }
    )
}

/** Propunerile AI-ului: fiecare cu de unde, unde și de ce; Acceptă / Nu, sau toate odată. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiResultSheet(
    ai: WeekViewModel.AiUi,
    onAccept: (WeekViewModel.AiMove) -> Unit,
    onReject: (WeekViewModel.AiMove) -> Unit,
    onAcceptAll: () -> Unit,
    onDismiss: () -> Unit
) {
    if (ai !is WeekViewModel.AiUi.Done && ai !is WeekViewModel.AiUi.Error) return
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Propunerile AI", fontFamily = Fraunces, style = MaterialTheme.typography.headlineSmall)
            }
            when (ai) {
                is WeekViewModel.AiUi.Error -> {
                    Text(ai.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onDismiss) { Text("Închide") }
                }
                is WeekViewModel.AiUi.Done -> {
                    if (ai.summary.isNotBlank()) Text(ai.summary, style = MaterialTheme.typography.bodyLarge)
                    if (ai.moves.isEmpty()) {
                        Text(
                            "Nu e nimic de mutat. Săptămânile tale arată echilibrat.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Bine") }
                    } else {
                        ai.moves.forEach { move -> MoveCard(move, onAccept = { onAccept(move) }, onReject = { onReject(move) }) }
                        Button(onClick = onAcceptAll, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                            Text(if (ai.moves.size == 1) "Acceptă" else "Acceptă toate (${ai.moves.size})")
                        }
                        Text(
                            "Sunt doar propuneri. Ce nu accepți rămâne cum era.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun MoveCard(move: WeekViewModel.AiMove, onAccept: () -> Unit, onReject: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(move.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(short(move.from), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward, null,
                    Modifier.padding(horizontal = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.primary
                )
                Text(short(move.to), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
            if (move.reason.isNotBlank()) {
                Text(move.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onAccept) { Text("Mută") }
                TextButton(onClick = onReject) { Text("Nu") }
            }
        }
    }
}
