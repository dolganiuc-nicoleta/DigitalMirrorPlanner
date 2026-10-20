package md.utm.digitalmirror.ui.prep

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.domain.PrepTemplates
import md.utm.digitalmirror.ui.components.formatDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** Eveniment nou: titlu, dată, tip (șablon), timp total și pașii, pe care îi poți schimba. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PrepNewScreen(
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
    vm: PrepViewModel = viewModel(),
    form: PrepFormViewModel = viewModel()
) {
    val context = LocalContext.current
    var pickingDate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    val daysLeft = ChronoUnit.DAYS.between(LocalDate.now(), form.date).toInt()
    val perDay = if (daysLeft > 0) form.stepsTotal / daysLeft else form.stepsTotal

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Eveniment nou") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Înapoi") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = form.title,
                    onValueChange = { form.title = it },
                    label = { Text("Pentru ce te pregătești?") },
                    placeholder = { Text("Susținere lab 1 IoT") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Data evenimentului", style = MaterialTheme.typography.labelMedium)
                        Text(form.date.format(prepDateFormatter), style = MaterialTheme.typography.titleMedium)
                        Text(
                            daysLeftText(daysLeft),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { pickingDate = true }) { Text("Alege data") }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Ce fel de eveniment e?", style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrepTemplates.ALL.forEach { t ->
                            FilterChip(
                                selected = form.templateKey == t.key,
                                onClick = { form.chooseTemplate(t.key) },
                                label = { Text(t.label) }
                            )
                        }
                    }
                    Text(
                        PrepTemplates.byKey(form.templateKey).hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Timp total de pregătire", style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { form.changeTotal(-30) }) { Text("−") }
                            Text(
                                formatDuration(form.stepsTotal),
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { form.changeTotal(30) }) { Text("+") }
                        }
                        Text(
                            when {
                                daysLeft <= 0 -> "Alege o dată în viitor."
                                perDay > 240 -> "Ai $daysLeft zile: ~${formatDuration(perDay)} pe zi. E mult; mută data sau scade timpul."
                                else -> "Ai $daysLeft zile: în medie ~${formatDuration(perDay)} pe zi."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (daysLeft <= 0 || perDay > 240) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Schimbarea timpului total reface pașii de mai jos.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Text("Pașii", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium) }

            itemsIndexed(form.steps) { index, step ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedTextField(
                            value = step.title,
                            onValueChange = { form.setStepTitle(index, it) },
                            label = { Text("Pasul ${index + 1}") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { form.changeStepMinutes(index, -15) }) { Text("−15") }
                            Text(
                                formatDuration(step.minutes),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { form.changeStepMinutes(index, 15) }) { Text("+15") }
                            TextButton(onClick = { form.removeStep(index) }) {
                                Text("Scoate", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item { TextButton(onClick = { form.addStep() }) { Text("+ Adaugă un pas") } }

            item {
                Button(
                    enabled = form.canSave && !saving,
                    onClick = {
                        saving = true
                        vm.create(form.title, form.date, form.steps) { id, message ->
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                            onCreated(id)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Planifică pregătirea") }
                if (!form.canSave) {
                    Text(
                        "Scrie titlul, alege o dată în viitor și dă un nume fiecărui pas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

    if (pickingDate) {
        val tomorrowMillis = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= tomorrowMillis
            }
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDateMillis != null,
                    onClick = {
                        state.selectedDateMillis?.let {
                            form.date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        pickingDate = false
                    }
                ) { Text("Alege") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Anulează") } }
        ) { DatePicker(state = state) }
    }
}
