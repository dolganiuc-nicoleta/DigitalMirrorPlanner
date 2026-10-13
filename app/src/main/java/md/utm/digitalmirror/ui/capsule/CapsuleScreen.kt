package md.utm.digitalmirror.ui.capsule

import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import md.utm.digitalmirror.data.capsule.CapsuleVideo
import md.utm.digitalmirror.data.capsule.RecordVideo
import md.utm.digitalmirror.data.dao.PeriodStats
import md.utm.digitalmirror.data.entity.Goal
import md.utm.digitalmirror.domain.CapsuleLogic
import md.utm.digitalmirror.ui.components.formatDuration
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapsuleScreen(vm: CapsuleViewModel = viewModel()) {
    val cards by vm.cards.collectAsState()
    val goals by vm.activeGoals.collectAsState()

    var creating by rememberSaveable { mutableStateOf(false) }
    var opening by remember { mutableStateOf<CapsuleViewModel.CapsuleCard?>(null) }
    var deleting by remember { mutableStateOf<CapsuleViewModel.CapsuleCard?>(null) }
    var watching by remember { mutableStateOf<File?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Capsula timpului") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) { Text("+") }
        }
    ) { padding ->
        if (cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Nicio capsulă încă. Scrie-ți un mesaj și ce speri să realizezi, sigilează-l până la o dată " +
                            "aleasă și deschide-l atunci, ca să vezi ce s-a întâmplat între timp.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cards, key = { it.capsule.id }) { card ->
                    CapsuleCardView(
                        card = card,
                        vm = vm,
                        onOpen = { opening = card },
                        onDelete = { deleting = card },
                        onWatch = { watching = it }
                    )
                }
            }
        }
    }

    if (creating) {
        CapsuleDialog(
            goals = goals,
            onDismiss = { creating = false },
            onSave = { title, message, expectations, date, goalIds, video, slowing ->
                vm.create(title, message, expectations, date, goalIds, video, slowing)
                creating = false
            }
        )
    }

    opening?.let { card ->
        val early = card.state == CapsuleLogic.State.LOCKED
        AlertDialog(
            onDismissRequest = { opening = null },
            title = { Text(if (early) "Deschizi capsula mai devreme?" else "Deschizi capsula?") },
            text = {
                Text(
                    if (early) "Data stabilită era ${formatDate(card.capsule.unlockDate)}. Dacă o deschizi acum, " +
                            "comparația se face până azi și capsula nu se mai poate sigila la loc."
                    else "După ce o deschizi, vezi mesajul tău și ce s-a întâmplat de la sigilare până acum."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.open(card.capsule); opening = null }) { Text("Deschide") }
            },
            dismissButton = { TextButton(onClick = { opening = null }) { Text("Nu acum") } }
        )
    }

    deleting?.let { card ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Ștergi capsula?") },
            text = { Text("„${card.capsule.title}” se șterge definitiv, împreună cu videoul atașat.") },
            confirmButton = {
                TextButton(onClick = { vm.delete(card.capsule); deleting = null }) { Text("Șterge") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anulează") } }
        )
    }

    watching?.let { VideoDialog(it) { watching = null } }
}

@Composable
private fun CapsuleCardView(
    card: CapsuleViewModel.CapsuleCard,
    vm: CapsuleViewModel,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onWatch: (File) -> Unit
) {
    val context = LocalContext.current
    val capsule = card.capsule
    val video = CapsuleVideo.existing(context, capsule.videoFile)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(capsule.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            when (card.state) {
                CapsuleLogic.State.LOCKED -> {
                    Text(
                        "Sigilată · se deschide pe ${formatDate(capsule.unlockDate)} · " + when {
                            card.daysLeft == 1 -> "mai e 1 zi"
                            else -> "mai sunt ${card.daysLeft} zile"
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (capsule.videoFile != null) {
                        Text("Are și un video.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (card.nudge != CapsuleLogic.Nudge.NONE) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    if (card.nudge == CapsuleLogic.Nudge.STAGNANT)
                                        "S-a mai oprit ritmul la obiectivele legate, se întâmplă. " +
                                                "Ți-ai lăsat acest mesaj pentru zile ca asta."
                                    else
                                        "Ritmul a scăzut puțin la obiectivele legate. " +
                                                "Ți-ai lăsat acest mesaj pentru asemenea zile.",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                TextButton(onClick = onOpen) { Text("Deschide acum") }
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = onOpen) { Text("Deschide mai devreme") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Șterge") }
                    }
                }

                CapsuleLogic.State.READY -> {
                    Text(
                        "S-a ajuns la ${formatDate(capsule.unlockDate)}. Capsula se poate deschide.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row {
                        Button(onClick = onOpen) { Text("Deschide capsula") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Șterge") }
                    }
                }

                CapsuleLogic.State.OPENED -> OpenedContent(card, vm, video, onWatch, onDelete)
            }
        }
    }
}

@Composable
private fun OpenedContent(
    card: CapsuleViewModel.CapsuleCard,
    vm: CapsuleViewModel,
    video: File?,
    onWatch: (File) -> Unit,
    onDelete: () -> Unit
) {
    val capsule = card.capsule
    val end = card.openedOn ?: LocalDate.now()
    val start = LocalDate.parse(capsule.startDate)
    var stats by remember(capsule.id) { mutableStateOf<PeriodStats?>(null) }
    LaunchedEffect(capsule.id, card.openedOn) { stats = vm.periodStats(card) }

    Text(
        "Scrisă pe ${formatDate(capsule.startDate)} · deschisă pe ${end.format(dateFormatter)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    SectionTitle("Ce ți-ai scris")
    Text(capsule.message, style = MaterialTheme.typography.bodyMedium)

    if (capsule.expectations.isNotBlank()) {
        SectionTitle("Ce speri să realizezi")
        Text(capsule.expectations, style = MaterialTheme.typography.bodyMedium)
    }

    if (capsule.videoFile != null) {
        if (video != null) {
            OutlinedButton(onClick = { onWatch(video) }) { Text("Vezi videoul") }
        } else {
            Text(
                "Videoul nu mai e pe acest telefon (nu intră în copia de rezervă).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    SectionTitle("Ce s-a întâmplat")
    val days = CapsuleLogic.daysCovered(start, end)
    stats?.let {
        Text(
            if (it.activities == 0) "În aceste $days zile nu ai închis nicio activitate."
            else "În $days zile: ${it.activities} ${if (it.activities == 1) "activitate" else "activități"} " +
                    "în ${it.activeDays} ${if (it.activeDays == 1) "zi" else "zile"}, " +
                    "${formatDuration(it.minutes)} în total.",
            style = MaterialTheme.typography.bodyMedium
        )
    }
    card.goals.forEach { line ->
        val change = CapsuleLogic.change(line)
        Text(
            if (line.percentNow == null) "${line.title}: obiectivul a fost șters."
            else "${line.title}: ${line.percentStart}% → ${line.percentNow}%" +
                    (change?.takeIf { it != 0 }?.let { " (${if (it > 0) "+" else ""}$it puncte)" } ?: ""),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    if (card.goals.isEmpty()) {
        Text(
            "Capsula nu are obiective legate, așa că vezi doar activitatea din perioadă.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Row {
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onDelete) { Text("Șterge") }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun VideoDialog(file: File, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Închide") } },
        text = {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        val controller = MediaController(ctx)
                        controller.setAnchorView(this)
                        setMediaController(controller)
                        setVideoURI(Uri.fromFile(file))
                        setOnPreparedListener { start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.fillMaxWidth().height(320.dp)
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CapsuleDialog(
    goals: List<Goal>,
    onDismiss: () -> Unit,
    onSave: (String, String, String, LocalDate, Set<Long>, String?, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // rememberSaveable: aplicația de cameră poate închide aplicația noastră cât filmezi
    var title by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    var expectations by rememberSaveable { mutableStateOf("") }
    var unlockDate by rememberSaveable { mutableStateOf<String?>(null) }
    var selected by rememberSaveable { mutableStateOf<Set<Long>>(emptySet()) }
    var slowing by rememberSaveable { mutableStateOf(false) }
    var videoFile by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFile by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingDate by remember { mutableStateOf(false) }

    // videoul adăugat și apoi abandonat nu rămâne pe telefon
    fun discardVideo() {
        CapsuleVideo.delete(context, videoFile)
        videoFile = null
    }

    val recordLauncher = rememberLauncherForActivityResult(RecordVideo()) { ok ->
        val name = pendingFile
        pendingFile = null
        if (name != null) {
            val file = CapsuleVideo.file(context, name)
            if (ok && file.exists() && file.length() > 0) {
                discardVideo()
                videoFile = name
            } else file.delete()
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val name = CapsuleVideo.importFrom(context, uri)
            if (name != null) {
                discardVideo()
                videoFile = name
            } else Toast.makeText(context, "Nu am putut adăuga videoul.", Toast.LENGTH_SHORT).show()
        }
    }

    val date = unlockDate?.let { LocalDate.parse(it) }
    val valid = title.isNotBlank() && message.isNotBlank() && date != null

    fun dismiss() {
        discardVideo()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text("Capsulă nouă") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Odată sigilată, capsula nu se mai editează. Se deschide la data aleasă.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Titlu (de ex. Scrisoare pentru noiembrie)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = message, onValueChange = { message = it },
                    label = { Text("Mesaj către tine") },
                    minLines = 3, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = expectations, onValueChange = { expectations = it },
                    label = { Text("Ce speri să realizezi (opțional)") },
                    minLines = 2, modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(onClick = { pickingDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(unlockDate?.let { "Se deschide pe ${formatDate(it)}" } ?: "Alege data deschiderii")
                }

                Text("Video (opțional)", style = MaterialTheme.typography.labelLarge)
                if (videoFile != null) {
                    Text("Video adăugat.", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = ::discardVideo) { Text("Scoate videoul") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val name = CapsuleVideo.newFileName()
                            pendingFile = name
                            try {
                                recordLauncher.launch(CapsuleVideo.uriFor(context, CapsuleVideo.file(context, name)))
                            } catch (e: Exception) {
                                pendingFile = null
                                Toast.makeText(context, "Nu am putut deschide camera.", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Înregistrează") }
                        OutlinedButton(onClick = { pickLauncher.launch("video/*") }) { Text("Din galerie") }
                    }
                }

                Text("Obiective legate (opțional)", style = MaterialTheme.typography.labelLarge)
                if (goals.isEmpty()) {
                    Text(
                        "Nu ai obiective active. Creează unul în tabul Obiective ca să-l poți lega de capsulă; " +
                                "la deschidere vezi cât s-a schimbat.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    goals.forEach { g ->
                        FilterChip(
                            selected = g.id in selected,
                            onClick = {
                                selected = if (g.id in selected) selected - g.id else selected + g.id
                                if (selected.isEmpty()) slowing = false
                            },
                            label = { Text(g.title) }
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Întreabă-mă dacă vreau să o deschid mai devreme când încetinesc",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(checked = slowing, onCheckedChange = { slowing = it }, enabled = selected.isNotEmpty())
                }
                Text(
                    if (selected.isEmpty()) "Alege cel puțin un obiectiv ca să poți activa asta."
                    else "Dacă ritmul obiectivelor alese scade sau se oprește, primești o notificare blândă, " +
                            "cel mult o dată pe săptămână. Tu decizi dacă deschizi capsula.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    // videoul a fost salvat în capsulă, deci nu-l mai ștergem
                    val video = videoFile
                    videoFile = null
                    onSave(title, message, expectations, date!!, selected, video, slowing)
                }
            ) { Text("Sigilează") }
        },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("Anulează") } }
    )

    if (pickingDate) {
        UnlockDatePicker(
            onDismiss = { pickingDate = false },
            onConfirm = { unlockDate = it; pickingDate = false }
        )
    }
}

// cel mai devreme mâine
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnlockDatePicker(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val tomorrowMillis = LocalDate.now().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = tomorrowMillis,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= tomorrowMillis
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let {
                        onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                }
            ) { Text("Alege") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } }
    ) { DatePicker(state = state) }
}

private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ro"))

private fun formatDate(iso: String): String = LocalDate.parse(iso).format(dateFormatter)