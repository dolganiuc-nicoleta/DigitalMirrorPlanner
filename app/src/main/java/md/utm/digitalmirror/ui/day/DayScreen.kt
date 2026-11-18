package md.utm.digitalmirror.ui.day

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import md.utm.digitalmirror.data.calendar.CalendarReader
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.utm.digitalmirror.data.entity.Activity
import md.utm.digitalmirror.data.entity.Category
import md.utm.digitalmirror.data.entity.DailyActivity
import md.utm.digitalmirror.data.entity.Place
import md.utm.digitalmirror.data.prefs.AnalysisPrefs
import md.utm.digitalmirror.domain.Availability
import md.utm.digitalmirror.domain.DayLoad
import md.utm.digitalmirror.domain.HistoryEstimator
import md.utm.digitalmirror.domain.TitleKey
import md.utm.digitalmirror.domain.SlotRecommender
import md.utm.digitalmirror.domain.Tags
import md.utm.digitalmirror.domain.MealPlanner
import md.utm.digitalmirror.domain.SleepPlanner
import md.utm.digitalmirror.domain.TimelineGaps
import md.utm.digitalmirror.domain.TravelGaps
import md.utm.digitalmirror.ui.components.DurationField
import md.utm.digitalmirror.ui.components.formatDuration
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(
    requestedDate: String? = null,
    onRequestedDateShown: () -> Unit = {},
    onCloseDay: (String) -> Unit = {},
    onOpenActivity: (Long) -> Unit = {},
    onPlan: () -> Unit = {},
    onPlanWeek: () -> Unit = {},
    onPrep: () -> Unit = {},
    vm: DayViewModel = viewModel()
) {
    // altă pagină a cerut o anumită zi (de ex. mâine, din închiderea zilei)
    LaunchedEffect(requestedDate) {
        requestedDate?.let {
            vm.changeDate(it)
            onRequestedDateShown()
        }
    }

    val date by vm.date.collectAsState()
    val activities by vm.activities.collectAsState()
    val categories by vm.categories.collectAsState()
    val places by vm.places.collectAsState()
    val running by vm.runningSession.collectAsState()
    val finishDraft by vm.finishDraft.collectAsState()
    val runningOffset by vm.runningOffset.collectAsState()
    val reflection by vm.reflection.collectAsState()
    val health by vm.health.collectAsState()
    val dayLoad by vm.dayLoad.collectAsState()
    val calendarEvents by vm.calendarEvents.collectAsState()
    val fixedBlocks by vm.fixedBlocks.collectAsState()
    val calendarPlaces by vm.calendarPlaces.collectAsState()
    val skeleton by vm.skeleton.collectAsState()
    val sleep by vm.sleep.collectAsState()
    val dayWindow by vm.dayWindow.collectAsState()
    val sleepCheck by vm.sleepCheck.collectAsState()
    val isDayOff by vm.isDayOff.collectAsState()
    val legs by vm.legs.collectAsState()
    val travelMode by vm.travelMode.collectAsState()
    val weekDots by vm.weekDots.collectAsState()

    // comutatorul din Setări; se recitește de fiecare dată când ecranul reintră în compoziție
    val context = LocalContext.current
    val analysisOn = remember { AnalysisPrefs.isEnabled(context) }

    // filtrarea după etichetă; arătăm doar etichetele care apar în ziua aleasă
    val dayTags = activities.flatMap { Tags.decode(it.tags) }.distinct().sorted()
    var tagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val activeTag = tagFilter?.takeIf { it in dayTags }

    var showAddDialog by remember { mutableStateOf(false) }
    var addAtMinute by remember { mutableStateOf<Int?>(null) }       // ora completată când apeși „+ Adaugă” într-un spațiu liber
    var editing by remember { mutableStateOf<Activity?>(null) }      // activitatea în editare
    var postponing by remember { mutableStateOf<Activity?>(null) }   // activitatea pentru care alegem o zi

    LaunchedEffect(Unit) { vm.refreshRunning() }

    // la revenirea în aplicație recitim calendarul (poate ai adăugat un eveniment între timp)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshCalendar()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isToday = date == LocalDate.now().toString()
    val canClose = date <= LocalDate.now().toString()   // azi sau o zi trecută

    Scaffold(
        // bara de sus cu navigare între zile
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        val d = LocalDate.parse(date)
                        Column {
                            Text(
                                d.format(dayFormatter).replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(d.format(dateFormatter), style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    actions = {
                        if (!isToday) {
                            TextButton(onClick = { vm.goToToday() }) { Text("Azi") }
                        }
                        // meniul zilei: marcarea ca zi liberă
                        Box {
                            var dayMenuOpen by remember { mutableStateOf(false) }
                            TextButton(onClick = { dayMenuOpen = true }) {
                                Text("⋮", style = MaterialTheme.typography.titleMedium)
                            }
                            DropdownMenu(expanded = dayMenuOpen, onDismissRequest = { dayMenuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Planifică din text") },
                                    onClick = { dayMenuOpen = false; onPlan() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Planifică săptămâna") },
                                    onClick = { dayMenuOpen = false; onPlanWeek() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Pregătire pentru un eveniment") },
                                    onClick = { dayMenuOpen = false; onPrep() }
                                )
                                DropdownMenuItem(
                                    text = { Text(if (isDayOff) "Scoate marcajul de zi liberă" else "Marchează ca zi liberă") },
                                    onClick = { dayMenuOpen = false; vm.toggleDayOff() }
                                )
                            }
                        }
                    }
                )
                DayWeekStrip(
                    selected = LocalDate.parse(date),
                    dots = weekDots,
                    onSelect = { vm.changeDate(it.toString()) }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addAtMinute = null; showAddDialog = true }) { Text("+") }
        }
    ) { padding ->

        if (activities.isEmpty() && calendarEvents.isEmpty() && fixedBlocks.isEmpty() && !isDayOff) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    health?.let {
                        HealthSummary(it)
                        Spacer(Modifier.height(16.dp))
                    }
                    Text(
                        if (isToday) "Nicio activitate pentru astăzi"
                        else "Nicio activitate în această zi"
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPlan) { Text("Plan rapid") }
                        OutlinedButton(onClick = onPlanWeek) { Text("Săptămâna") }
                        OutlinedButton(onClick = onPrep) { Text("Pregătire") }
                    }
                    // reflecția se poate face și într-o zi fără activități
                    if (canClose) {
                        Spacer(Modifier.height(16.dp))
                        val mood = reflection?.moodScore
                        if (mood != null) {
                            DayClosedCard(mood = mood, onOpen = { onCloseDay(date) })
                        } else {
                            OutlinedButton(onClick = { onCloseDay(date) }) { Text("Închide ziua") }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // jos mai mult spațiu, ca butonul + să nu acopere ultimul element
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // scurtături vizibile către planificare
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val tight = PaddingValues(horizontal = 4.dp)
                        OutlinedButton(onClick = onPlan, contentPadding = tight, modifier = Modifier.weight(1f)) { Text("Plan rapid", maxLines = 1) }
                        OutlinedButton(onClick = onPlanWeek, contentPadding = tight, modifier = Modifier.weight(1f)) { Text("Săptămâna", maxLines = 1) }
                        OutlinedButton(onClick = onPrep, contentPadding = tight, modifier = Modifier.weight(1f)) { Text("Pregătire", maxLines = 1) }
                    }
                }

                // somnul și pașii zilei
                health?.let { item { HealthSummary(it) } }

                // evenimentele din calendarul telefonului
                if (isDayOff) {
                    item { DayOffCard() }
                }
                if (fixedBlocks.isNotEmpty()) {
                    item { FixedBlocksCard(fixedBlocks) }
                }
                if (legs.isNotEmpty()) {
                    item {
                        TravelLegsCard(
                            legs = legs,
                            mode = travelMode,
                            onModeChange = { vm.setTravelMode(it) },
                            onEstimate = { vm.estimateLeg(it) },
                            onAdd = { vm.addTravel(it) }
                        )
                    }
                }
                // evenimentele cu oră stau pe linia de timp; aici rămân doar cele de toată ziua
                val allDayEvents = calendarEvents.filter { it.allDay }
                if (allDayEvents.isNotEmpty()) {
                    item {
                        CalendarCard(
                            events = allDayEvents,
                            places = places,
                            calendarPlaces = calendarPlaces,
                            onSetPlace = { title, placeId -> vm.setCalendarPlace(title, placeId) }
                        )
                    }
                }

                // dimineața: confirmarea nopții trecute, dintr-un tap
                sleepCheck?.let { check ->
                    item(key = "sleep-check") {
                        SleepCheckCard(
                            check = check,
                            onConfirm = { bed, wake -> vm.confirmSleep(bed, wake) },
                            onDismiss = { vm.dismissSleepCheck() }
                        )
                    }
                }

                // încărcarea zilei, doar pentru azi și zilele viitoare
                dayLoad?.takeIf { analysisOn && date >= LocalDate.now().toString() && it.plannedMinutes > 0 }?.let { load ->
                    item { DayLoadCard(load) }
                }

                // filtrarea după etichete
                if (dayTags.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = activeTag == null,
                                onClick = { tagFilter = null },
                                label = { Text("Toate") }
                            )
                            dayTags.forEach { tag ->
                                FilterChip(
                                    selected = activeTag == tag,
                                    onClick = { tagFilter = if (activeTag == tag) null else tag },
                                    label = { Text("#$tag") }
                                )
                            }
                        }
                    }
                }

                // linia de timp: activitățile cu oră, cu spațiile libere între ele; cele fără oră, mai jos
                val visible = activities.filter { activeTag == null || activeTag in Tags.decode(it.tags) }
                val timed = visible.filter { it.plannedStartMinute != null }
                val untimed = visible.filter { it.plannedStartMinute == null }

                val today = LocalDate.now()
                // drumurile și mesele zilei (fără filtru de etichetă; într-o zi liberă nu le arătăm)
                val showSkeleton = activeTag == null && !isDayOff
                val legs = if (showSkeleton) skeleton?.legs.orEmpty() else emptyList()
                val meals = if (showSkeleton) skeleton?.meals?.slots.orEmpty() else emptyList()
                val missingMeals = if (showSkeleton) skeleton?.meals?.missing.orEmpty() else emptyList()
                val gaps = if (activeTag == null && !isDayOff && date >= today.toString()) {
                    val now = java.time.LocalTime.now()
                    // timpul liber e între trezire și culcare (fără somn propus: 8:00–22:00, ca înainte)
                    val dayStart = sleep?.today?.wake ?: 8 * 60
                    val dayEnd = sleep?.bedtime?.coerceAtMost(24 * 60) ?: 22 * 60
                    val from = if (date == today.toString()) maxOf(dayStart, (now.hour * 60 + now.minute + 4) / 5 * 5) else dayStart
                    val busy = activities
                        .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
                        .mapNotNull { a -> a.plannedStartMinute?.let { SlotRecommender.Busy(it, it + (a.userEstimatedMinutes ?: 30)) } } +
                            calendarEvents.filter { it.busy }.map { SlotRecommender.Busy(it.startMinute, it.endMinute) } +
                            // blocurile cu loc au drumul calculat (în legs); celelalte, drumul fix din Setări
                            fixedBlocks.map {
                                if (it.placeId != null) SlotRecommender.Busy(it.start, it.end)
                                else SlotRecommender.Busy(it.busyStart, it.busyEnd)
                            } +
                            legs.map { SlotRecommender.Busy(it.prepStart, it.end) } +
                            meals.map { SlotRecommender.Busy(it.start, it.end) }
                    TimelineGaps.compute(busy, from, dayEnd)
                } else emptyList()

                // mesele fără loc azi: doar o notă blândă
                if (missingMeals.isNotEmpty()) {
                    item(key = "missing-meals") { MissingMealsNote(missingMeals) }
                }

                // evenimentele din calendar cu oră (nu și când filtrăm după etichetă: ele nu au etichete)
                val timedEvents = if (activeTag == null) calendarEvents.filter { !it.allDay } else emptyList()

                val entries = (timed.map { TimelineEntry.Act(it) } +
                        timedEvents.map { TimelineEntry.Cal(it) } +
                        gaps.map { TimelineEntry.Gap(it) } +
                        legs.map { leg ->
                            TimelineEntry.Travel(leg, places.find { it.id == leg.toPlaceId }?.name ?: "alt loc")
                        } +
                        legs.filter { it.prepMinutes > 0 }.map { TimelineEntry.Prep(it) } +
                        meals.map { TimelineEntry.Meal(it) } +
                        // trezirea sus și culcarea jos (nu și când filtrăm după etichetă)
                        listOfNotNull(sleep?.takeIf { activeTag == null }?.let { TimelineEntry.Wake(it) }) +
                        listOfNotNull(sleep?.takeIf { activeTag == null }?.let { TimelineEntry.Bed(it) }))
                    .sortedBy { it.startMinute }

                val activityRow: @Composable (Activity) -> Unit = { activity ->
                    ActivityRow(
                        activity = activity,
                        categoryName = categories.find { it.id == activity.categoryId }?.name,
                        placeName = places.find { it.id == activity.placeId }?.name,
                        runningSince = running?.takeIf { it.activityId == activity.id }?.startedAt,
                        offsetMillis = runningOffset,
                        onToggleTimer = { vm.toggleTimer(activity) },
                        onFinish = { vm.requestFinish(activity) },
                        onOpen = { onOpenActivity(activity.id) },
                        onEdit = { editing = activity },
                        onPostponeTomorrow = {
                            vm.postponeActivity(activity, MoveTarget.of(LocalDate.parse(activity.date)).date.toString())
                        },
                        onPostponePick = { postponing = activity },
                        onDelete = { vm.deleteActivity(activity) }
                    )
                }

                items(entries, key = { it.key }) { entry ->
                    when (entry) {
                        is TimelineEntry.Gap -> TimelineGapItem(
                            gap = entry.gap,
                            startLabel = formatMinute(entry.gap.start),
                            onAdd = { addAtMinute = entry.gap.start; showAddDialog = true }
                        )
                        is TimelineEntry.Cal -> CalendarTimelineItem(
                            event = entry.event,
                            places = places,
                            calendarPlaces = calendarPlaces,
                            onSetPlace = { title, placeId -> vm.setCalendarPlace(title, placeId) }
                        )
                        is TimelineEntry.Wake -> TimelineWakeItem(entry.sleep)
                        is TimelineEntry.Bed -> TimelineBedItem(entry.sleep)
                        is TimelineEntry.Meal -> TimelineMealItem(
                            meal = entry.meal,
                            startLabel = formatMinute(entry.meal.start)
                        )
                        is TimelineEntry.Prep -> TimelinePrepItem(
                            leg = entry.leg,
                            startLabel = formatMinute(entry.leg.prepStart)
                        )
                        is TimelineEntry.Travel -> TimelineTravelItem(
                            leg = entry.leg,
                            toName = entry.toName,
                            startLabel = formatMinute(entry.leg.start)
                        )
                        is TimelineEntry.Act -> {
                            val a = entry.activity
                            TimelineItem(
                                time = formatMinute(entry.startMinute),
                                marker = {
                                    TimelineMarker(
                                        letter = categories.find { it.id == a.categoryId }?.name?.firstOrNull()?.uppercase() ?: "•",
                                        done = a.state == "DONE" || a.state == "PARTIAL"
                                    )
                                }
                            ) { activityRow(a) }
                        }
                    }
                }

                if (untimed.isNotEmpty()) {
                    item(key = "untimed-title") {
                        Text(
                            "Fără oră",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    items(untimed, key = { it.id }) { activity -> activityRow(activity) }
                }

                // închiderea zilei, doar pentru azi și zilele trecute
                if (canClose) {
                    item {
                        val mood = reflection?.moodScore
                        if (mood != null) {
                            // ziua e deja închisă → arătăm confirmarea
                            DayClosedCard(
                                mood = mood,
                                onOpen = { onCloseDay(date) },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        } else {
                            Button(
                                onClick = { onCloseDay(date) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                            ) { Text("Închide ziua") }
                        }
                    }
                }
            }
        }
    }

    // adăugare
    if (showAddDialog) {
        ActivityDialog(
            initial = null,
            presetStartMinute = addAtMinute,
            categories = categories,
            places = places,
            date = date,
            dayActivities = activities,
            loadEstimatorHistory = { vm.estimatorHistory() },
            priorRatio = vm.priorRatio,
            loadSlotHistory = { vm.slotHistory(it) },
            calendarEvents = calendarEvents,
            fixedBlocks = fixedBlocks,
            loadTravel = { placeId, others -> vm.travelFor(placeId, others) },
            homePlaceId = vm.homePlaceId,
            calendarPlaces = calendarPlaces,
            mealSlots = skeleton?.meals?.slots.orEmpty(),
            dayWindow = dayWindow,
            analysisOn = analysisOn,
            onDismiss = { showAddDialog = false; addAtMinute = null },
            onAddCategory = { vm.addCategory(it) },
            onConfirm = { r ->
                vm.addActivity(
                    r.title, r.categoryId, r.plannedMinutes, r.startMinute, r.guessMinutes, r.estimate,
                    r.note, r.tags, r.placeId
                )
                showAddDialog = false
                addAtMinute = null
            }
        )
    }

    // editare
    editing?.let { activity ->
        ActivityDialog(
            initial = activity,
            categories = categories,
            places = places,
            date = date,
            dayActivities = activities,
            loadEstimatorHistory = { vm.estimatorHistory() },
            priorRatio = vm.priorRatio,
            loadSlotHistory = { vm.slotHistory(it) },
            calendarEvents = calendarEvents,
            fixedBlocks = fixedBlocks,
            loadTravel = { placeId, others -> vm.travelFor(placeId, others) },
            homePlaceId = vm.homePlaceId,
            calendarPlaces = calendarPlaces,
            mealSlots = skeleton?.meals?.slots.orEmpty(),
            dayWindow = dayWindow,
            analysisOn = analysisOn,
            onDismiss = { editing = null },
            onAddCategory = { vm.addCategory(it) },
            onConfirm = { r ->
                vm.editActivity(activity.id, r.title, r.categoryId, r.plannedMinutes, r.startMinute, r.note, r.tags, r.placeId)
                editing = null
            }
        )
    }

    // amânare pe o zi aleasă
    postponing?.let { activity ->
        PostponeDatePicker(
            fromDate = activity.date,
            onDismiss = { postponing = null },
            onConfirm = { newDate ->
                vm.postponeActivity(activity, newDate)
                postponing = null
            }
        )
    }

    // finalizare
    finishDraft?.let { draft ->
        FinishDialog(
            draft = draft,
            onDismiss = { vm.dismissFinish() },
            onConfirm = { minutes, state -> vm.confirmFinish(minutes, state) }
        )
    }
}

@Composable
private fun ActivityRow(
    activity: Activity,
    categoryName: String?,
    placeName: String?,
    runningSince: Long?,
    offsetMillis: Long,
    onToggleTimer: () -> Unit,
    onFinish: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onPostponeTomorrow: () -> Unit,
    onPostponePick: () -> Unit,
    onDelete: () -> Unit
) {
    val isClosed = activity.state in setOf("DONE", "PARTIAL", "ABANDONED")
    val isRunning = runningSince != null

    // cronometru live, se actualizează în fiecare secundă
    val elapsed by produceState(0L, runningSince, offsetMillis) {
        while (runningSince != null) {
            value = (offsetMillis + System.currentTimeMillis() - runningSince) / 1000
            delay(1000)
        }
    }

    // apăsarea pe card deschide pagina activității
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp)) {

            // rândul de sus: textul la stânga, meniul ⋮ la dreapta
            Row(verticalAlignment = Alignment.Top) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 4.dp)
                ) {
                    Text(activity.title, fontWeight = FontWeight.Medium)

                    val details = buildList {
                        activity.plannedStartMinute?.let { start ->
                            val end = activity.userEstimatedMinutes?.let { start + it }
                            add(if (end != null) "${formatMinute(start)}–${formatMinute(end)}" else formatMinute(start))
                        }
                        categoryName?.let { add(it) }
                        placeName?.let { add("la $it") }
                        activity.userEstimatedMinutes?.let { add("planificat ${formatDuration(it)}") }
                        if (!isRunning) activity.actualMinutes?.let { add("real ${formatDuration(it)}") }
                        add(stateLabel(activity.state))
                        postponedLabel(activity.postponedCount)?.let { add(it) }
                    }.joinToString(" · ")

                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Tags.decode(activity.tags).takeIf { it.isNotEmpty() }?.let { tags ->
                        Text(
                            tags.joinToString(" ") { "#$it" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (activity.note.isNotBlank()) {
                        Text(
                            activity.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isRunning) {
                        val h = elapsed / 3600
                        val m = (elapsed % 3600) / 60
                        val s = elapsed % 60
                        Text(
                            if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                ActivityMenu(
                    isClosed = isClosed,
                    activityDate = LocalDate.parse(activity.date),
                    onEdit = onEdit,
                    onPostponeTomorrow = onPostponeTomorrow,
                    onPostponePick = onPostponePick,
                    onDelete = onDelete
                )
            }

            // butoanele apar doar la activitățile încă deschise
            if (!isClosed) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val compact = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    OutlinedButton(onClick = onToggleTimer, contentPadding = compact) {
                        Text(
                            when {
                                isRunning -> "Pauză"
                                activity.state == "IN_PROGRESS" -> "Continuă"
                                else -> "Pornește"
                            },
                            maxLines = 1
                        )
                    }
                    OutlinedButton(onClick = onFinish, contentPadding = compact) {
                        Text("Finalizează", maxLines = 1)
                    }
                }
            }
        }
    }
}

/**
 * Unde merge activitatea la „mută repede”, după ziua ei:
 *  - azi → mâine (amânare);
 *  - o zi trecută, nefăcută → azi;
 *  - o zi viitoare → ziua de după ea (replanificare, nu amânare).
 */
private data class MoveTarget(val date: LocalDate, val label: String, val pickLabel: String) {
    companion object {
        private val DAY_NAME = java.time.format.DateTimeFormatter.ofPattern("EEEE", java.util.Locale.forLanguageTag("ro"))

        fun of(activityDate: LocalDate, today: LocalDate = LocalDate.now()): MoveTarget = when {
            activityDate.isBefore(today) -> MoveTarget(today, "Mută pe azi", "Mută pe altă zi…")
            activityDate == today -> MoveTarget(today.plusDays(1), "Amână pe mâine", "Amână pe altă zi…")
            else -> {
                val next = activityDate.plusDays(1)
                val name = if (next == today.plusDays(1)) "mâine" else next.format(DAY_NAME)
                MoveTarget(next, "Mută pe $name", "Mută pe altă zi…")
            }
        }
    }
}

// meniul ⋮ al unei activități
@Composable
private fun ActivityMenu(
    isClosed: Boolean,
    activityDate: LocalDate,
    onEdit: () -> Unit,
    onPostponeTomorrow: () -> Unit,
    onPostponePick: () -> Unit,
    onDelete: () -> Unit
) {
    Box {
        var menuOpen by remember { mutableStateOf(false) }

        TextButton(
            onClick = { menuOpen = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text("⋮", style = MaterialTheme.typography.titleMedium)
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Editează") },
                onClick = { menuOpen = false; onEdit() }
            )
            if (!isClosed) {
                val target = MoveTarget.of(activityDate)
                DropdownMenuItem(
                    text = { Text(target.label) },
                    onClick = { menuOpen = false; onPostponeTomorrow() }
                )
                DropdownMenuItem(
                    text = { Text(target.pickLabel) },
                    onClick = { menuOpen = false; onPostponePick() }
                )
            }
            DropdownMenuItem(
                text = { Text("Șterge", color = MaterialTheme.colorScheme.error) },
                onClick = { menuOpen = false; onDelete() }
            )
        }
    }
}

// rândul cu somnul din noaptea dinainte și pașii zilei
@Composable
private fun HealthSummary(data: DailyActivity) {
    val parts = buildList {
        data.sleepMinutes?.let { minutes ->
            val range = if (data.bedtimeMinute != null && data.wakeMinute != null)
                " (${formatMinute(data.bedtimeMinute)}–${formatMinute(data.wakeMinute)})" else ""
            add("😴 ${formatDuration(minutes)}$range")
        }
        data.steps?.let { add("👣 ${"%,d".format(it).replace(',', '.')} pași") }
    }
    if (parts.isEmpty()) return

    Text(
        parts.joinToString("   "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// cât e planificat azi față de cât reușește utilizatorul de obicei
@Composable
private fun DayOffCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Zi liberă", style = MaterialTheme.typography.titleMedium)
            Text(
                "Blocurile fixe nu se aplică, iar ziua nu intră în media zilelor tale tipice.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun FixedBlocksCard(blocks: List<Availability.Block>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Program fix",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            blocks.forEach { b ->
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${formatMinute(b.start)}–${if (b.end >= 24 * 60) "24:00" else formatMinute(b.end)}",
                            modifier = Modifier.width(100.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            b.title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // drumul, doar dacă există
                    if (b.travelBefore > 0 || b.travelAfter > 0) {
                        Text(
                            "drum: " + listOfNotNull(
                                b.travelBefore.takeIf { it > 0 }?.let { "${formatDuration(it)} înainte" },
                                b.travelAfter.takeIf { it > 0 }?.let { "${formatDuration(it)} după" }
                            ).joinToString(" · "),
                            modifier = Modifier.padding(start = 100.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Evenimentele din calendar. Fiecare eveniment cu oră are un buton de loc: alegerea se ține minte
 * pentru toate evenimentele cu același nume (orarul se repetă), iar drumurile se calculează după el.
 */
@Composable
private fun CalendarCard(
    events: List<CalendarReader.Event>,
    places: List<Place>,
    calendarPlaces: Map<String, Long>,
    onSetPlace: (title: String, placeId: Long?) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Din calendar",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            events.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (e.allDay) "toată ziua"
                        else "${formatMinute(e.startMinute)}–${if (e.endMinute >= 24 * 60) "24:00" else formatMinute(e.endMinute)}",
                        modifier = Modifier.width(100.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        e.title,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // locul contează doar pentru evenimentele care ocupă timp și doar dacă există locuri
                    if (!e.allDay && e.busy && places.isNotEmpty()) {
                        CalendarPlaceChip(
                            current = calendarPlaces[TitleKey.of(e.title)]?.let { id -> places.find { it.id == id } },
                            places = places,
                            onSelect = { onSetPlace(e.title, it) }
                        )
                    }
                }
            }
        }
    }
}

/** Un eveniment din calendar pe linia de timp: rând gri, doar de citit, cu locul lui. */
@Composable
private fun CalendarTimelineItem(
    event: CalendarReader.Event,
    places: List<Place>,
    calendarPlaces: Map<String, Long>,
    onSetPlace: (title: String, placeId: Long?) -> Unit
) {
    TimelineItem(
        time = formatMinute(event.startMinute),
        marker = {
            Text(
                "📅",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatMinute(event.startMinute)}–" +
                                (if (event.endMinute >= 24 * 60) "24:00" else formatMinute(event.endMinute)) +
                                " · din calendar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (event.busy && places.isNotEmpty()) {
                    CalendarPlaceChip(
                        current = calendarPlaces[TitleKey.of(event.title)]?.let { id -> places.find { it.id == id } },
                        places = places,
                        onSelect = { onSetPlace(event.title, it) }
                    )
                }
            }
        }
    }
}

// „📍 Facultate” sau „📍 Unde e?”; la apăsare, lista locurilor
@Composable
private fun CalendarPlaceChip(current: Place?, places: List<Place>, onSelect: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
        ) {
            Text(
                "📍 " + (current?.name ?: "Unde e?"),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            places.forEach { place ->
                DropdownMenuItem(
                    text = { Text(place.name) },
                    onClick = { onSelect(place.id); open = false }
                )
            }
            if (current != null) {
                DropdownMenuItem(
                    text = { Text("fără loc") },
                    onClick = { onSelect(null); open = false }
                )
            }
        }
    }
}

/**
 * „😴 Ai dormit 0:15–9:00?” cu ✓ Da / Schimbă / ×.
 * Confirmarea salvează noaptea ca introdusă de tine, iar aplicația de somn nu o mai suprascrie.
 */
@Composable
private fun SleepCheckCard(
    check: SleepPlanner.Check,
    onConfirm: (bedtime: Int, wake: Int) -> Unit,
    onDismiss: () -> Unit
) {
    var bed by remember(check) { mutableStateOf(check.bedtime) }
    var wake by remember(check) { mutableStateOf(check.wake) }
    var pickingBed by remember { mutableStateOf(false) }
    var pickingWake by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "😴 Ai dormit ${SleepPlanner.clock(bed)}–${SleepPlanner.clock(wake)}?",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("×") }
            }
            val note = when {
                check.fromHealth -> "din aplicația de somn · ${formatDuration(SleepPlanner.duration(bed, wake))}"
                check.partialHealth != null -> {
                    val p = check.partialHealth
                    "aplicația de somn a salvat doar " +
                            (if (p.bedtime != null && p.wake != null) "${SleepPlanner.clock(p.bedtime)}–${SleepPlanner.clock(p.wake)}" else "o parte") +
                            ", așa că îți propun ritmul tău obișnuit"
                }
                else -> "ritmul tău obișnuit · ${formatDuration(SleepPlanner.duration(bed, wake))}"
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            if (editing) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp)
                ) {
                    OutlinedButton(onClick = { pickingBed = true }, modifier = Modifier.weight(1f)) {
                        Text("🌙 ${SleepPlanner.clock(bed)}")
                    }
                    OutlinedButton(onClick = { pickingWake = true }, modifier = Modifier.weight(1f)) {
                        Text("☀️ ${SleepPlanner.clock(wake)}")
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Button(onClick = { onConfirm(bed, wake) }) { Text(if (editing) "Salvează" else "✓ Da") }
                if (!editing) TextButton(onClick = { editing = true }) { Text("Schimbă") }
            }
        }
    }

    if (pickingBed) {
        TimePickerDialog(
            initialMinute = bed,
            onDismiss = { pickingBed = false },
            onConfirm = { bed = it; pickingBed = false }
        )
    }
    if (pickingWake) {
        TimePickerDialog(
            initialMinute = wake,
            onDismiss = { pickingWake = false },
            onConfirm = { wake = it; pickingWake = false }
        )
    }
}

@Composable
private fun DayLoadCard(load: DayLoad.Result) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Încărcarea zilei", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    load.typicalMinutes?.let { "${formatDuration(load.plannedMinutes)} / ${formatDuration(it)} obișnuit" }
                        ?: "${formatDuration(load.plannedMinutes)} planificat",
                    style = MaterialTheme.typography.titleSmall
                )
            }

            load.typicalMinutes?.let { typical ->
                LinearProgressIndicator(
                    progress = { (load.plannedMinutes.toFloat() / typical).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (load.status == DayLoad.Status.OVERLOADED) MaterialTheme.colorScheme.tertiaryContainer
                    else MaterialTheme.colorScheme.onPrimary,
                    trackColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f)
                )
            }

            Text(
                when (load.status) {
                    DayLoad.Status.NO_HISTORY ->
                        "Încă învăț cât reușești de obicei într-o zi (${load.daysUsed} din ${DayLoad.MIN_DAYS} zile)."
                    DayLoad.Status.OVERLOADED ->
                        "De obicei reușești ~${formatDuration(load.typicalMinutes!!)} într-o zi ca asta. " +
                                "Ziua e supraîncărcată cu ${formatDuration(load.difference)}."
                    DayLoad.Status.LIGHT ->
                        "Ai planificat mai puțin decât reușești de obicei. Mai ai loc pentru ceva."
                    DayLoad.Status.OK ->
                        "Pare o zi realistă pentru tine."
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// cardul afișat după ce reflecția zilei a fost salvată
@Composable
private fun DayClosedCard(
    mood: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "✓ Ziua e închisă · stare $mood/5",
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onOpen) { Text("Vezi reflecția") }
        }
    }
}

@Composable
private fun FinishDialog(
    draft: DayViewModel.FinishDraft,
    onDismiss: () -> Unit,
    onConfirm: (Int, String) -> Unit
) {
    var minutes by remember { mutableStateOf<Int?>(draft.trackedMinutes) }
    var state by remember { mutableStateOf("DONE") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Finalizare activitate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(draft.activity.title, fontWeight = FontWeight.Medium)

                draft.activity.userEstimatedMinutes?.let {
                    Text("Planificat ${formatDuration(it)} · cronometrat ${formatDuration(draft.trackedMinutes)}")
                }

                DurationField(
                    label = "Durata reală",
                    minutes = minutes,
                    onChange = { minutes = it },
                    showQuickValues = false
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state == "DONE",
                        onClick = { state = "DONE" },
                        label = { Text("Finalizată") }
                    )
                    FilterChip(
                        selected = state == "PARTIAL",
                        onClick = { state = "PARTIAL" },
                        label = { Text("Parțial") }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(minutes ?: 0, state) },
                enabled = minutes != null
            ) { Text("Salvează") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

// ce întoarce formularul de activitate
private data class ActivityFormResult(
    val title: String,
    val categoryId: Long?,
    val plannedMinutes: Int?,
    val startMinute: Int?,
    val guessMinutes: Int?,                    // doar la adăugare
    val estimate: HistoryEstimator.Estimate?,  // doar la adăugare
    val note: String,
    val tags: String,                          // deja în forma salvată (fără #, separate prin virgulă)
    val placeId: Long?                         // locul ales la „Unde?”, null = fără loc
)

/**
 * Formular comun pentru adăugare (initial = null) și editare (initial = activitatea).
 *
 * La adăugare, durata se stabilește în doi pași, ca utilizatorul să nu fie influențat de propunere:
 *  1. „Cât crezi că îți ia?”  → ghicitul lui (M0), salvat separat;
 *  2. abia apoi apare propunerea din istoric (M3), iar el alege cu ce durată planifică.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityDialog(
    initial: Activity?,
    presetStartMinute: Int? = null,
    categories: List<Category>,
    places: List<Place>,
    date: String,
    dayActivities: List<Activity>,
    loadEstimatorHistory: suspend () -> List<HistoryEstimator.Sample>,
    priorRatio: Double,
    loadSlotHistory: suspend (Long) -> List<SlotRecommender.Outcome>,
    calendarEvents: List<CalendarReader.Event>,
    fixedBlocks: List<Availability.Block>,
    loadTravel: suspend (placeId: Long, others: Collection<Long>) -> TravelGaps.Travel,
    homePlaceId: Long?,
    calendarPlaces: Map<String, Long>,
    mealSlots: List<MealPlanner.Slot>,
    dayWindow: Pair<Int, Int>,
    analysisOn: Boolean,
    onDismiss: () -> Unit,
    onAddCategory: (String) -> Unit,
    onConfirm: (ActivityFormResult) -> Unit
) {
    val isEdit = initial != null

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var selectedCategory by remember {
        mutableStateOf(categories.find { it.id == initial?.categoryId })
    }
    var newCategory by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    var selectedPlaceId by remember { mutableStateOf(initial?.placeId) }
    var placeExpanded by remember { mutableStateOf(false) }
    var startMinute by remember { mutableStateOf(initial?.plannedStartMinute ?: presetStartMinute) }
    var showTimePicker by remember { mutableStateOf(false) }
    var tagsInput by remember { mutableStateOf(initial?.let { Tags.toInput(it.tags) } ?: "") }
    var note by remember { mutableStateOf(initial?.note ?: "") }

    // pasul 1: ghicitul utilizatorului
    var guessInput by remember { mutableStateOf<Int?>(null) }
    // la editare, sau cu analiza oprită, nu mai cerem ghicitul
    var revealed by remember { mutableStateOf(isEdit || !analysisOn) }
    var guessMinutes by remember { mutableStateOf<Int?>(null) }

    // pasul 2: durata planificată finală
    var plannedMinutes by remember { mutableStateOf(initial?.userEstimatedMinutes) }

    // istoricul pentru estimator, încărcat o singură dată
    val history by produceState(emptyList<HistoryEstimator.Sample>()) {
        value = loadEstimatorHistory()
    }

    // propunerea se recalculează când se schimbă titlul sau categoria
    val titleKey = TitleKey.of(title)
    val estimate = remember(history, titleKey, selectedCategory?.id, guessMinutes, revealed) {
        if (!analysisOn || !revealed || isEdit) null
        else HistoryEstimator.estimate(history, titleKey, selectedCategory?.id, guessMinutes, priorRatio)
    }

    // la dezvăluire, durata planificată pornește de la propunere (utilizatorul o poate schimba)
    LaunchedEffect(estimate?.minutes) {
        if (!isEdit && estimate != null && plannedMinutes == null) {
            plannedMinutes = guessMinutes ?: estimate.minutes
        }
    }

    // istoricul orelor de start pentru categoria aleasă
    val slotHistory by produceState(emptyList<SlotRecommender.Outcome>(), selectedCategory?.id) {
        value = selectedCategory?.id?.let { loadSlotHistory(it) } ?: emptyList()
    }

    // ce e deja ocupat în zi, cu locul fiecăruia (pentru drum)
    val dayBusy = remember(dayActivities, calendarEvents, fixedBlocks, calendarPlaces, mealSlots, initial?.id) {
        // mesele: locul păstrat pentru ele nu se propune pentru altceva
        mealSlots.map { SlotRecommender.Busy(it.start, it.end) } +
        dayActivities
            .filter { it.id != initial?.id && it.state != "ABANDONED" && it.state != "POSTPONED" }
            .mapNotNull { a ->
                a.plannedStartMinute?.let { SlotRecommender.Busy(it, it + (a.userEstimatedMinutes ?: 30), a.placeId) }
            } +
                // orele ocupate din calendar (fără cele de toată ziua și cele marcate „liber”), cu locul ales pentru ele
                calendarEvents.filter { it.busy }.map {
                    SlotRecommender.Busy(it.startMinute, it.endMinute, calendarPlaces[TitleKey.of(it.title)])
                } +
                // blocurile fixe: drumul se calculează după locul activității, cel fix rămâne rezervă
                fixedBlocks.map { it.asBusy() }
    }

    // unde va fi activitatea, pentru drum: locul ales, altfel acasă
    val travelPlaceId = selectedPlaceId ?: homePlaceId
    val busyPlaces = remember(dayBusy) { dayBusy.mapNotNull { it.placeId }.toSet() }
    val travel by produceState(TravelGaps.NONE, travelPlaceId, busyPlaces) {
        value = travelPlaceId?.let { loadTravel(it, busyPlaces) } ?: TravelGaps.NONE
    }
    val busyWithTravel = TravelGaps.expand(dayBusy, travelPlaceId, travel)

    // ora aleasă (sau venită din spațiul liber) nu lasă timp de drum: propunem cea mai devreme oră potrivită
    val travelFix = run {
        val start = startMinute ?: return@run null
        if (LocalDate.parse(date).isBefore(LocalDate.now())) return@run null
        val duration = plannedMinutes ?: estimate?.minutes ?: 30
        val overlapsWithoutTravel = !SlotRecommender.isFree(start, start + duration, dayBusy)
        if (overlapsWithoutTravel || SlotRecommender.isFree(start, start + duration, busyWithTravel)) return@run null
        // doar drumul e problema: ce lucru din zi e prea aproape și cât e drumul până la el
        val near = dayBusy.firstOrNull { b ->
            val e = TravelGaps.expand(listOf(b), travelPlaceId, travel).first()
            start < e.end && e.start < start + duration
        }
        val fit = TravelGaps.earliestFit(start, duration, dayBusy, travelPlaceId, travel) ?: return@run null
        TravelFix(
            moveTo = fit,
            placeName = near?.placeId?.let { id -> places.find { it.id == id }?.name },
            minutes = near?.let { b ->
                if (b.end <= start) TravelGaps.needed(b.placeId, travelPlaceId, travel) ?: b.travelAfter
                else TravelGaps.needed(travelPlaceId, b.placeId, travel) ?: b.travelBefore
            },
            afterIt = near != null && near.end <= start,
            // plecarea e de acasă: drumul include și pregătirea
            withPrep = near != null && homePlaceId != null &&
                    (if (near.end <= start) near.placeId else travelPlaceId) == homePlaceId &&
                    near.placeId != travelPlaceId
        )
    }

    // intervalul recomandat; doar când ora nu e aleasă și ziua nu e în trecut
    val slotSuggestion = run {
        val day = LocalDate.parse(date)
        val today = LocalDate.now()
        if (!analysisOn || !revealed || startMinute != null || day.isBefore(today)) return@run null

        val busy = busyWithTravel

        // între trezirea ta obișnuită (plus dimineața) și ora ta de culcare
        val (dayStart, dayEnd) = dayWindow
        val earliest = if (day == today) {
            val now = LocalTime.now()
            maxOf(dayStart, now.hour * 60 + now.minute + 10)
        } else dayStart

        val duration = plannedMinutes ?: estimate?.minutes ?: 30
        // întâi din istoric; dacă nu avem destule date, măcar primul interval liber
        SlotRecommender.suggest(
            history = slotHistory,
            durationMinutes = duration,
            busy = busy,
            earliestStart = earliest,
            latestEnd = dayEnd
        ) ?: SlotRecommender.firstFree(duration, busy, earliest, dayEnd)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "Editează activitatea" else "Activitate nouă") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Ce vrei să faci?") },
                    singleLine = true
                )

                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = !expanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory?.name ?: "fără categorie",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categorie") },
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("fără categorie") },
                            onClick = {
                                selectedCategory = null
                                expanded = false
                            }
                        )
                        categories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.name) },
                                onClick = {
                                    selectedCategory = category
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text("Categorie nouă") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            onAddCategory(newCategory)
                            newCategory = ""
                        }
                    ) { Text("Adaugă") }
                }

                // unde se desfășoară (opțional); locurile se adaugă în Setări → Locuri
                if (places.isEmpty()) {
                    Hint("Ca să alegi un loc, adaugă-l mai întâi în Setări → Locuri.")
                } else {
                    ExposedDropdownMenuBox(
                        expanded = placeExpanded,
                        onExpandedChange = { placeExpanded = !placeExpanded }
                    ) {
                        OutlinedTextField(
                            value = places.find { it.id == selectedPlaceId }?.name ?: "fără loc",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Unde?") },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = placeExpanded,
                            onDismissRequest = { placeExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("fără loc") },
                                onClick = {
                                    selectedPlaceId = null
                                    placeExpanded = false
                                }
                            )
                            places.forEach { place ->
                                DropdownMenuItem(
                                    text = { Text(place.name) },
                                    onClick = {
                                        selectedPlaceId = place.id
                                        placeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (!revealed) {
                    // pasul 1: utilizatorul spune cât crede, fără să vadă propunerea
                    DurationField(
                        label = "Cât crezi că îți ia?",
                        minutes = guessInput,
                        onChange = { guessInput = it }
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                guessMinutes = guessInput
                                revealed = true
                            },
                            enabled = title.isNotBlank() && (guessInput ?: 0) > 0
                        ) { Text("Mai departe") }
                        TextButton(
                            onClick = {
                                guessMinutes = null
                                revealed = true
                            },
                            enabled = title.isNotBlank()
                        ) { Text("Nu știu") }
                    }
                } else {
                    // pasul 2: propunerea din istoric și durata planificată
                    estimate?.let { e ->
                        EstimateCard(
                            estimate = e,
                            guessMinutes = guessMinutes,
                            categoryName = selectedCategory?.name,
                            onUseEstimate = { plannedMinutes = e.minutes },
                            onKeepGuess = { guessMinutes?.let { plannedMinutes = it } }
                        )
                    }

                    if (isEdit) {
                        initial?.estimatedMinutes?.let { proposed ->
                            Hint(
                                "La creare, propunerea a fost ~${formatDuration(proposed)}" +
                                        (initial?.userGuessMinutes?.let { " · tu ai estimat ${formatDuration(it)}" } ?: "")
                            )
                        }
                    }

                    DurationField(
                        label = "Durata planificată",
                        minutes = plannedMinutes,
                        onChange = { plannedMinutes = it },
                        showQuickValues = estimate == null || estimate.level == HistoryEstimator.Level.PRIOR
                    )

                    // recomandarea orei de start
                    slotSuggestion?.let { slot ->
                        SlotHint(
                            slot = slot,
                            categoryName = selectedCategory?.name ?: "",
                            onUse = { startMinute = slot.startMinute }
                        )
                    }

                    // ora de start (opțională)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showTimePicker = true }) {
                            Text(startMinute?.let { "Ora: ${formatMinute(it)}" } ?: "Alege ora de start")
                        }
                        if (startMinute != null) {
                            TextButton(onClick = { startMinute = null }) { Text("Fără oră") }
                        }
                    }

                    // ora aleasă nu lasă timp de drum
                    travelFix?.let { fix ->
                        TravelFixHint(fix = fix, onMove = { startMinute = fix.moveTo })
                    }

                    OutlinedTextField(
                        value = tagsInput,
                        onValueChange = { tagsInput = it },
                        label = { Text("Etichete (opțional, de ex. #curs #urgent)") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text("Notiță (opțional)") },
                        minLines = 2
                    )
                }
            }
        },
        confirmButton = {
            if (revealed) {
                TextButton(
                    onClick = {
                        onConfirm(
                            ActivityFormResult(
                                title = title,
                                categoryId = selectedCategory?.id,
                                plannedMinutes = plannedMinutes,
                                startMinute = startMinute,
                                guessMinutes = guessMinutes,
                                estimate = estimate,
                                note = note,
                                tags = Tags.encode(Tags.parse(tagsInput)),
                                placeId = selectedPlaceId
                            )
                        )
                    },
                    enabled = title.isNotBlank()
                ) { Text("Salvează") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )

    if (showTimePicker) {
        TimePickerDialog(
            initialMinute = startMinute,
            onDismiss = { showTimePicker = false },
            onConfirm = {
                startMinute = it
                showTimePicker = false
            }
        )
    }
}

// caseta cu propunerea estimatorului, intervalul și explicația ei
@Composable
private fun EstimateCard(
    estimate: HistoryEstimator.Estimate,
    guessMinutes: Int?,
    categoryName: String?,
    onUseEstimate: () -> Unit,
    onKeepGuess: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val color = MaterialTheme.colorScheme.onSecondaryContainer

            if (estimate.level == HistoryEstimator.Level.PRIOR) {
                Text(
                    "Încă nu am istoric pentru asta",
                    style = MaterialTheme.typography.bodyMedium,
                    color = color
                )
                if (guessMinutes != null && guessMinutes != estimate.minutes) {
                    // fără istoric, propunerea vine doar din tiparul zilei ales la configurare
                    Text(
                        "De obicei oamenii își subestimează activitățile, iar din tiparul tău de zi " +
                                "pornesc de la ~${formatDuration(estimate.minutes)}. După câteva activități finalizate, " +
                                "propunerile vor veni din istoricul tău.",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onUseEstimate) { Text("Folosește ~${formatDuration(estimate.minutes)}") }
                        TextButton(onClick = onKeepGuess) { Text("Păstrez ${formatDuration(guessMinutes)}") }
                    }
                } else {
                    Text(
                        "Planifică după estimarea ta. După câteva activități finalizate, propunerile vor veni din istoricul tău.",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }
                return@Column
            }

            Text("Din istoricul tău", style = MaterialTheme.typography.labelMedium, color = color)
            Text(
                "~${formatDuration(estimate.minutes)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = color
            )
            Text(
                "de obicei între ${formatDuration(estimate.lowMinutes)} și ${formatDuration(estimate.highMinutes)}",
                style = MaterialTheme.typography.bodySmall,
                color = color
            )

            // de ce: ce date au stat la baza propunerii
            val why = buildList {
                if (estimate.activityCount > 0 && estimate.activityTypicalMinutes != null) {
                    add(
                        "${estimate.activityCount} " +
                                (if (estimate.activityCount == 1) "sesiune la fel" else "sesiuni la fel") +
                                ", de obicei ${formatDuration(estimate.activityTypicalMinutes)}"
                    )
                }
                estimate.categoryRatio?.let { ratio ->
                    val percent = ((ratio - 1) * 100).roundToInt()
                    val where = categoryName?.let { "la $it" } ?: "în această categorie"
                    add(
                        when {
                            abs(percent) <= 10 -> "$where estimezi de obicei corect"
                            percent > 0 -> "$where durează de obicei cu $percent% mai mult decât estimezi"
                            else -> "$where durează de obicei cu ${abs(percent)}% mai puțin decât estimezi"
                        }
                    )
                }
            }
            why.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = color) }

            if (guessMinutes != null && guessMinutes != estimate.minutes) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onUseEstimate) { Text("Folosește ~${formatDuration(estimate.minutes)}") }
                    TextButton(onClick = onKeepGuess) { Text("Păstrez ${formatDuration(guessMinutes)}") }
                }
            } else if (guessMinutes == null) {
                TextButton(onClick = onUseEstimate) { Text("Folosește ~${formatDuration(estimate.minutes)}") }
            }
        }
    }
}

@Composable
private fun Hint(text: String) =
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

// caseta cu intervalul recomandat și explicația lui
@Composable
private fun SlotHint(
    slot: SlotRecommender.Suggestion,
    categoryName: String,
    onUse: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    (if (slot.fromHistory) "Interval recomandat " else "Interval liber ") +
                            "${formatMinute(slot.startMinute)}–${formatMinute(slot.endMinute)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Text(
                    if (slot.fromHistory)
                        "~${slot.successPercent}% finalizate la această oră · " +
                                "${slot.nearbyCount} din ${slot.sampleSize} activități $categoryName în jur"
                    else "Primul interval liber, ținând cont de activități, calendar și drumuri",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            TextButton(onClick = onUse) { Text("Folosește") }
        }
    }
}

/** Ora aleasă nu lasă timp de drum; [moveTo] e cea mai devreme oră la care încape. */
private class TravelFix(
    val moveTo: Int,
    val placeName: String?,     // locul de lângă care e drumul, dacă îl știm
    val minutes: Int?,          // drumul cu tot cu marjă
    val afterIt: Boolean,       // drumul e de la acel loc (true) sau până la el (false)
    val withPrep: Boolean = false   // pleci de acasă, deci [minutes] include și pregătirea
)

// caseta „nu ai timp de drum”, cu propunerea unei ore potrivite
@Composable
private fun TravelFixHint(fix: TravelFix, onMove: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Nu ai timp de drum",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
                val extra = if (fix.withPrep) "cu pregătirea și marja" else "cu tot cu marja"
                val road = when {
                    fix.placeName != null && fix.minutes != null ->
                        (if (fix.afterIt) "De la ${fix.placeName}" else "Până la ${fix.placeName}") +
                                " ai ~${formatDuration(fix.minutes)}, $extra."
                    fix.minutes != null -> "Drumul durează ~${formatDuration(fix.minutes)}, $extra."
                    else -> null
                }
                Text(
                    listOfNotNull(road, "Cel mai devreme la ${formatMinute(fix.moveTo)}.").joinToString(" "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            TextButton(onClick = onMove) { Text("Mută la ${formatMinute(fix.moveTo)}") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    initialMinute: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val now = LocalTime.now()
    val state = rememberTimePickerState(
        initialHour = initialMinute?.div(60) ?: now.hour,
        initialMinute = initialMinute?.rem(60) ?: 0,
        is24Hour = true
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ora de start") },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    )
}

// calendar pentru amânare: se pot alege doar zile de după data activității
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostponeDatePicker(
    fromDate: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // de la azi încolo, oricare altă zi decât a ei (o activitate viitoare se poate muta și mai devreme)
    val from = LocalDate.parse(fromDate)
    val today = LocalDate.now()
    val isToday = from == today
    val minDate = today
    val initial = MoveTarget.of(from, today).date
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val minMillis = millis(minDate)
    val ownMillis = millis(from)

    val state = rememberDatePickerState(
        initialSelectedDateMillis = millis(initial),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= minMillis && utcTimeMillis != ownMillis
            override fun isSelectableYear(year: Int) = year >= minDate.year
        }
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        onConfirm(picked.toString())
                    }
                },
                enabled = state.selectedDateMillis != null
            ) { Text(if (isToday) "Amână" else "Mută") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Anulează") }
        }
    ) {
        DatePicker(state = state)
    }
}

private fun stateLabel(state: String) = when (state) {
    "PLANNED" -> "planificată"
    "IN_PROGRESS" -> "în curs"
    "DONE" -> "finalizată"
    "PARTIAL" -> "parțial"
    "POSTPONED" -> "amânată"
    "ABANDONED" -> "abandonată"
    else -> state
}

private fun postponedLabel(count: Int): String? = when {
    count <= 0 -> null
    count == 1 -> "amânată o dată"
    else -> "amânată de $count ori"
}

// formatare dată și oră
private val ro = Locale.forLanguageTag("ro")
private val dayFormatter = DateTimeFormatter.ofPattern("EEEE", ro)       // „sâmbătă”
private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", ro)    // „3 octombrie”

private fun formatMinute(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)