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
import md.utm.digitalmirror.domain.MoveSuggestions
import md.utm.digitalmirror.domain.Replan
import md.utm.digitalmirror.domain.CardMirror
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.text.style.TextAlign
import md.utm.digitalmirror.domain.QuickCapture
import md.utm.digitalmirror.ui.theme.Fraunces
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import md.utm.digitalmirror.domain.DayArc
import md.utm.digitalmirror.domain.TravelPlanner
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
    requestAdd: Boolean = false,
    onAddShown: () -> Unit = {},
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
    // istoricul pentru oglinda de pe carduri; se reîncarcă când se schimbă activitățile zilei
    val estimatorHistory by produceState(emptyList<HistoryEstimator.Sample>(), activities) {
        value = vm.estimatorHistory()
    }
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

    // arcul zilei: de la trezire la culcare, cu soarele „acum” (doar azi și zilele viitoare)
    val nowMinute = rememberNowMinute()
    val isTodayShown = date == LocalDate.now().toString()
    val dayArc = sleep?.takeIf { activeTag == null }?.let { s ->
        val items = activities
            .filter { it.state != "ABANDONED" && it.state != "POSTPONED" }
            .filter { TravelPlanner.TRAVEL_TAG !in Tags.decode(it.tags) }
            .mapNotNull { a ->
                a.plannedStartMinute?.let { start ->
                    DayArc.Item(
                        start, start + (a.userEstimatedMinutes ?: a.estimatedMinutes ?: 30), a.title,
                        if (a.state == "DONE" || a.state == "PARTIAL") DayArc.Kind.DONE else DayArc.Kind.ACTIVITY
                    )
                }
            } +
                calendarEvents.filter { it.busy && !it.allDay }
                    .map { DayArc.Item(it.startMinute, it.endMinute, it.title, DayArc.Kind.EVENT) } +
                fixedBlocks.map { DayArc.Item(it.start, it.end, it.title, DayArc.Kind.EVENT) } +
                skeleton?.meals?.slots.orEmpty()
                    .map { DayArc.Item(it.start, it.end, it.kind.label, DayArc.Kind.MEAL) }
        val arcLegs = skeleton?.legs.orEmpty().map { leg ->
            DayArc.Leg(leg.prepStart, leg.start, leg.end, places.find { it.id == leg.toPlaceId }?.name ?: "alt loc")
        }
        val runningTitle = running?.let { r -> activities.find { it.id == r.activityId }?.title }
        val result = DayArc.build(
            s.today.wake, s.bedtime, items, arcLegs, nowMinute.takeIf { isTodayShown },
            running = runningTitle.takeIf { isTodayShown }
        )
        Triple(result, s, if (isTodayShown) SkyTime.at(nowMinute) else SkyTime.MORNING)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var addAtMinute by remember { mutableStateOf<Int?>(null) }       // ora completată când apeși „+ Adaugă” într-un spațiu liber
    var editing by remember { mutableStateOf<Activity?>(null) }      // activitatea în editare
    var postponing by remember { mutableStateOf<Activity?>(null) }   // activitatea pentru care alegem o zi
    var moving by remember { mutableStateOf<Activity?>(null) }       // activitatea pentru care arătăm propunerile „Mută”
    var interrupting by remember { mutableStateOf(false) }           // foaia „⚡ A apărut ceva”

    LaunchedEffect(Unit) { vm.refreshRunning() }

    // „+ › Ceva acum”: formularul de activitate, cu ora propusă
    LaunchedEffect(requestAdd) {
        if (requestAdd) {
            addAtMinute = null
            showAddDialog = true
            onAddShown()
        }
    }

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
        // fără buton „+” aici: planificarea are un singur loc, „+” din mijlocul barei de jos
    ) { padding ->

        // ecranul gol doar pentru zilele fără arc (trecute); azi și zilele viitoare au mereu arcul
        if (dayArc == null && activities.isEmpty() && calendarEvents.isEmpty() && fixedBlocks.isEmpty() && !isDayOff) {
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
                // arcul zilei, primul lucru pe care îl vezi
                dayArc?.let { (arc, s, sky) ->
                    item(key = "day-arc") {
                        DayArcHero(arc = arc, wake = s.today.wake, bedtime = s.bedtime, sky = sky)
                    }
                }

                // încărcarea zilei, un rând discret sub arc (doar pentru azi și zilele viitoare)
                dayLoad?.takeIf { analysisOn && date >= LocalDate.now().toString() && it.plannedMinutes > 0 }?.let { load ->
                    item(key = "day-load") { DayLoadCard(load) }
                }

                // „⚡ A apărut ceva”: doar azi, când ziua se poate strica
                if (isTodayShown && !isDayOff) {
                    item(key = "interrupt") {
                        OutlinedButton(
                            onClick = { interrupting = true },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) { Text("⚡  A apărut ceva") }
                    }
                }

                // somnul și pașii zilei: doar pentru zilele trecute (azi și mâine le arată arcul și confirmarea de dimineață)
                if (dayArc == null) health?.let { item { HealthSummary(it) } }

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
                        // trezirea și culcarea: le arată arcul de sus; în listă doar dacă arcul lipsește
                        listOfNotNull(sleep?.takeIf { activeTag == null && dayArc == null }?.let { TimelineEntry.Wake(it) }) +
                        listOfNotNull(sleep?.takeIf { activeTag == null && dayArc == null }?.let { TimelineEntry.Bed(it) }))
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
                        onPostponePick = { moving = activity },
                        onDelete = { vm.deleteActivity(activity) },
                        mirror = if (analysisOn) mirrorFor(activity, estimatorHistory, vm.priorRatio) else null
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
    // „⚡ A apărut ceva”: cât te ține, apoi restul zilei refăcut, înainte să aplici
    if (interrupting) {
        InterruptSheet(
            loadPreview = { until -> vm.replanPreview(until) },
            onApply = { moves ->
                vm.applyReplan(moves)
                interrupting = false
            },
            onDismiss = { interrupting = false }
        )
    }

    // „Mută”: propuneri gata calculate; la capăt, alegerea liberă a zilei
    moving?.let { activity ->
        MoveSheet(
            activity = activity,
            loadSuggestions = { vm.moveSuggestions(activity) },
            onPick = { s ->
                vm.moveTo(activity, s.date, s.start)
                moving = null
            },
            onOtherDay = {
                moving = null
                postponing = activity
            },
            onDismiss = { moving = null }
        )
    }

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
    onDelete: () -> Unit,
    mirror: String? = null                 // ce știe oglinda despre activitatea asta, pe scurt
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

                    // pe scurt: când, unde, cât; starea doar când nu e cea obișnuită („planificată” se înțelege)
                    val details = buildList {
                        activity.plannedStartMinute?.let { start ->
                            val end = activity.userEstimatedMinutes?.let { start + it }
                            add(if (end != null) "${formatMinute(start)}–${formatMinute(end)}" else formatMinute(start))
                        } ?: activity.userEstimatedMinutes?.let { add(formatDuration(it)) }
                        categoryName?.let { add(it) }
                        placeName?.let { add("la $it") }
                        if (!isRunning) activity.actualMinutes?.let { add("real ${formatDuration(it)}") }
                        if (activity.state != "PLANNED") add(stateLabel(activity.state))
                        postponedLabel(activity.postponedCount)?.let { add(it) }
                    }.joinToString(" · ")

                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // oglinda: ce spune istoricul tău despre activitatea asta
                    mirror?.let {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Insights,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }

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
 * Foaia „Mută”: unde mai încape activitatea, gata calculat (drumuri, mese, trezire, perna de seară).
 * Un tap pe o propunere și e mutată; la capăt, „Altă zi…” pentru alegerea liberă.
 */
/**
 * „⚡ A apărut ceva”: întâi cât te ține, apoi restul zilei refăcut. Nimic nu se schimbă până la „Aplică”.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InterruptSheet(
    loadPreview: suspend (until: Int) -> List<Replan.Move>,
    onApply: (List<Replan.Move>) -> Unit,
    onDismiss: () -> Unit
) {
    val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    var until by remember { mutableStateOf<Int?>(null) }
    var pickingTime by remember { mutableStateOf(false) }
    val preview by produceState<List<Replan.Move>?>(null, until) {
        value = null
        until?.let { value = loadPreview(it) }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("A apărut ceva", fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
            val chosen = until
            if (chosen == null) {
                Text(
                    "Se întâmplă. Cât te ține? Refac restul zilei, iar tu decizi dacă îl păstrezi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        FilledTonalButton(onClick = { until = now + m }) { Text(formatDuration(m)) }
                    }
                    OutlinedButton(onClick = { pickingTime = true }) { Text("Până la…") }
                    OutlinedButton(onClick = { until = 24 * 60 }) { Text("Tot restul zilei") }
                }
            } else {
                Text(
                    if (chosen >= 24 * 60) "Restul zilei e ocupat. Ce aveai de făcut trece pe mâine:"
                    else "Ocupat până la ${formatMinute(chosen)}. Așa arată restul zilei:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val moves = preview
                when {
                    moves == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    moves.isEmpty() -> Text(
                        "N-ai activități de mutat azi; cursurile și calendarul rămân cum sunt. Ai grijă de tine.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> moves.forEach { m ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(
                                when (m.outcome) {
                                    Replan.Outcome.STAYS -> "${formatMinute(m.item.start)} · rămâne"
                                    Replan.Outcome.MOVED -> "${formatMinute(m.item.start)} → ${formatMinute(m.newStart!!)}"
                                    Replan.Outcome.TOMORROW -> "→ mâine"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (m.outcome == Replan.Outcome.STAYS) FontWeight.Normal else FontWeight.SemiBold,
                                color = if (m.outcome == Replan.Outcome.STAYS) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(
                        onClick = { moves?.let(onApply) },
                        enabled = !moves.isNullOrEmpty() && moves.any { it.outcome != Replan.Outcome.STAYS }
                    ) { Text("Aplică") }
                    TextButton(onClick = { until = null }) { Text("Înapoi") }
                }
            }
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            initialMinute = ((now + 60) / 15 * 15).coerceAtMost(23 * 60 + 45),
            onDismiss = { pickingTime = false },
            onConfirm = { m ->
                until = if (m > now) m else now + 15
                pickingTime = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveSheet(
    activity: Activity,
    loadSuggestions: suspend () -> List<MoveSuggestions.Suggestion>,
    onPick: (MoveSuggestions.Suggestion) -> Unit,
    onOtherDay: () -> Unit,
    onDismiss: () -> Unit
) {
    val suggestions by produceState<List<MoveSuggestions.Suggestion>?>(null, activity.id) { value = loadSuggestions() }
    val today = LocalDate.now()
    val minutes = activity.userEstimatedMinutes ?: activity.estimatedMinutes ?: 30

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Mută", fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 28.sp)
            Text(
                "„${activity.title}” · ${formatDuration(minutes)}. Unde mai încape, cu drumuri, mese și somn:",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val list = suggestions
            when {
                list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> Text(
                    "N-am găsit niciun loc liber până la termen. Alege tu ziua și ora.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
                else -> list.forEach { s ->
                    Surface(
                        onClick = { onPick(s) },
                        shape = RoundedCornerShape(16.dp),
                        color = if (s.note == MoveSuggestions.Note.EARLIEST) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                QuickCapture.dayLabel(s.date, today),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(84.dp)
                            )
                            Text(
                                "${formatMinute(s.start)}–${formatMinute(s.end)}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                when (s.note) {
                                    MoveSuggestions.Note.EARLIEST -> "cel mai devreme"
                                    MoveSuggestions.Note.MORNING -> "dimineața"
                                    MoveSuggestions.Note.AFTERNOON -> "după-amiaza"
                                    MoveSuggestions.Note.EVENING -> "seara"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            TextButton(onClick = onOtherDay, modifier = Modifier.fillMaxWidth()) { Text("Altă zi…") }
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
            activityDate.isBefore(today) -> MoveTarget(today, "Mută pe azi", "Mută…")
            activityDate == today -> MoveTarget(today.plusDays(1), "Amână pe mâine", "Mută…")
            else -> {
                val next = activityDate.plusDays(1)
                val name = if (next == today.plusDays(1)) "mâine" else next.format(DAY_NAME)
                MoveTarget(next, "Mută pe $name", "Mută…")
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
    // un card clar, ca un curs: punctul în culoarea aplicației, titlul, până când, locul
    TimelineItem(
        time = formatMinute(event.startMinute),
        marker = {
            Box(
                Modifier
                    .padding(top = 18.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        event.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "până la " + (if (event.endMinute >= 24 * 60) "24:00" else formatMinute(event.endMinute)) +
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
/**
 * Încărcarea zilei, discret: un rând sub arc. Iese în evidență doar când ziua e prea plină.
 */
private fun DayLoadCard(load: DayLoad.Result) {
    val overloaded = load.status == DayLoad.Status.OVERLOADED
    val planned = formatDuration(load.plannedMinutes)
    val text = when (load.status) {
        DayLoad.Status.NO_HISTORY -> "$planned planificat · încă învăț ritmul tău (${load.daysUsed} din ${DayLoad.MIN_DAYS} zile)"
        DayLoad.Status.OVERLOADED ->
            "$planned planificat, de obicei reușești ~${formatDuration(load.typicalMinutes!!)}. Ziua e prea plină."
        DayLoad.Status.LIGHT -> "$planned planificat · mai ai loc pentru ceva"
        DayLoad.Status.OK -> "$planned planificat · o zi realistă pentru tine"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (overloaded) MaterialTheme.colorScheme.errorContainer else Color.Transparent)
            .padding(horizontal = if (overloaded) 12.dp else 4.dp, vertical = if (overloaded) 10.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (overloaded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = if (overloaded) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    var selectedPlaceId by remember { mutableStateOf(initial?.placeId) }
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

    // intervalul recomandat; doar când ziua nu e în trecut (rămâne vizibil și după ce îl alegi)
    val slotSuggestion = run {
        val day = LocalDate.parse(date)
        val today = LocalDate.now()
        if (!analysisOn || !revealed || day.isBefore(today)) return@run null

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

    // categoria nou creată se alege singură când apare în listă
    var addingCategory by remember { mutableStateOf(false) }
    var pendingCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(categories, pendingCategory) {
        val name = pendingCategory ?: return@LaunchedEffect
        categories.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }?.let {
            selectedCategory = it
            pendingCategory = null
        }
    }
    var showDetails by remember { mutableStateOf(initial?.let { it.note.isNotBlank() || it.tags.isNotBlank() } ?: false) }
    val dayLabel = remember(date) {
        val d = LocalDate.parse(date)
        val today = LocalDate.now()
        val name = when (d) {
            today -> "Azi"
            today.plusDays(1) -> "Mâine"
            else -> d.format(DateTimeFormatter.ofPattern("EEEE", Locale.forLanguageTag("ro"))).replaceFirstChar { it.uppercase() }
        }
        "$name, ${d.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ro")))}"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // antet
            Column {
                Text(
                    if (isEdit) "Editează activitatea" else "Activitate nouă",
                    fontFamily = Fraunces,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(dayLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // titlul: mare, fără chenar
            TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Ce vrei să faci?", style = MaterialTheme.typography.titleLarge) },
                textStyle = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // categoria: cipuri, cu „+ Nouă” la capăt
            FormSection("Categorie") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = selectedCategory?.id == c.id,
                            onClick = { selectedCategory = if (selectedCategory?.id == c.id) null else c },
                            label = { Text(c.name) },
                            leadingIcon = {
                                Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(md.utm.digitalmirror.ui.components.CategoryColor.hex(c))))
                            }
                        )
                    }
                    AssistChip(
                        onClick = { addingCategory = !addingCategory },
                        label = { Text("Nouă") },
                        leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) }
                    )
                }
                if (addingCategory) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newCategory,
                            onValueChange = { newCategory = it },
                            placeholder = { Text("Numele categoriei") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        )
                        FilledTonalButton(
                            onClick = {
                                onAddCategory(newCategory)
                                pendingCategory = newCategory
                                newCategory = ""
                                addingCategory = false
                            },
                            enabled = newCategory.isNotBlank()
                        ) { Text("Adaugă") }
                    }
                }
            }

            // locul (opțional); locurile se adaugă în Setări → Locuri
            if (places.isNotEmpty()) {
                FormSection("Unde?") {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = selectedPlaceId == null,
                            onClick = { selectedPlaceId = null },
                            label = { Text("Nu contează") }
                        )
                        places.forEach { place ->
                            FilterChip(
                                selected = selectedPlaceId == place.id,
                                onClick = { selectedPlaceId = place.id },
                                label = { Text(place.name) },
                                leadingIcon = { Icon(Icons.Outlined.Place, null, Modifier.size(16.dp)) }
                            )
                        }
                    }
                }
            }

            if (!revealed) {
                // pasul 1: utilizatorul spune cât crede, fără să vadă propunerea
                FormSection("Cât crezi că îți ia?") {
                    DurationPicker(minutes = guessInput, onChange = { guessInput = it })
                    Hint("Spune întâi cum crezi tu; apoi îți arăt ce zice istoricul tău.")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            guessMinutes = guessInput
                            revealed = true
                        },
                        enabled = title.isNotBlank() && (guessInput ?: 0) > 0,
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) { Text("Mai departe") }
                    OutlinedButton(
                        onClick = {
                            guessMinutes = null
                            revealed = true
                        },
                        enabled = title.isNotBlank(),
                        modifier = Modifier.height(52.dp)
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
                        onKeepGuess = { guessMinutes?.let { plannedMinutes = it } },
                        planned = plannedMinutes
                    )
                }

                FormSection("Durata") {
                    DurationPicker(minutes = plannedMinutes, onChange = { plannedMinutes = it })
                    if (isEdit) {
                        initial?.estimatedMinutes?.let { proposed ->
                            Hint(
                                "La creare, propunerea a fost ~${formatDuration(proposed)}" +
                                        (initial?.userGuessMinutes?.let { " · tu ai estimat ${formatDuration(it)}" } ?: "")
                            )
                        }
                    }
                }

                // ora: propunerea, o oră aleasă de tine sau fără oră
                FormSection("Când?") {
                    val suggested = slotSuggestion?.startMinute
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        slotSuggestion?.let { slot ->
                            FilterChip(
                                selected = startMinute == slot.startMinute,
                                onClick = { startMinute = slot.startMinute },
                                label = { Text("${formatMinute(slot.startMinute)}–${formatMinute(slot.endMinute)}") },
                                leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) }
                            )
                        }
                        val custom = startMinute?.takeIf { it != suggested }
                        FilterChip(
                            selected = custom != null,
                            onClick = { showTimePicker = true },
                            label = { Text(custom?.let { "La ${formatMinute(it)}" } ?: "Altă oră") },
                            leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp)) }
                        )
                        FilterChip(
                            selected = startMinute == null,
                            onClick = { startMinute = null },
                            label = { Text("Fără oră") }
                        )
                    }
                    slotSuggestion?.let { slot ->
                        Hint(
                            if (slot.fromHistory)
                                "✨ Recomandat: ~${slot.successPercent}% din activitățile ${selectedCategory?.name ?: ""} " +
                                        "de la ora asta le-ai terminat (${slot.nearbyCount} din ${slot.sampleSize})."
                            else "✨ Primul interval liber, ținând cont de activități, calendar și drumuri."
                        )
                    }
                    // ora aleasă nu lasă timp de drum
                    travelFix?.let { fix ->
                        TravelFixHint(fix = fix, onMove = { startMinute = fix.moveTo })
                    }
                }

                // etichete și notiță, ascunse până le ceri
                if (!showDetails) {
                    TextButton(onClick = { showDetails = true }, contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Etichete și notiță")
                    }
                } else {
                    FormSection("Detalii") {
                        OutlinedTextField(
                            value = tagsInput,
                            onValueChange = { tagsInput = it },
                            placeholder = { Text("#curs #urgent") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = note,
                            onValueChange = { note = it },
                            placeholder = { Text("Notiță") },
                            minLines = 2,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Button(
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
                    enabled = title.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (isEdit) "Salvează" else "Adaugă în zi") }
            }
        }
    }

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
    onKeepGuess: () -> Unit,
    planned: Int? = null          // durata aleasă acum: butonul potrivit apare ales
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
                        "Oamenii își subestimează de obicei activitățile; din tiparul tău de zi aș porni de la " +
                                "~${formatDuration(estimate.minutes)}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PickButton(planned == estimate.minutes, onUseEstimate, "Folosește ~${formatDuration(estimate.minutes)}")
                        PickButton(planned == guessMinutes, onKeepGuess, "Păstrez ${formatDuration(guessMinutes)}")
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
                    PickButton(planned == estimate.minutes, onUseEstimate, "Folosește ~${formatDuration(estimate.minutes)}")
                    PickButton(planned == guessMinutes, onKeepGuess, "Păstrez ${formatDuration(guessMinutes)}")
                }
            } else if (guessMinutes == null) {
                PickButton(planned == estimate.minutes, onUseEstimate, "Folosește ~${formatDuration(estimate.minutes)}")
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

/**
 * Oglinda de pe card, ca propoziție: înainte, ce spune istoricul (doar dacă spune clar altceva decât planul);
 * după, cât a durat față de plan. null = nimic sigur de spus.
 */
private fun mirrorFor(activity: Activity, history: List<HistoryEstimator.Sample>, priorRatio: Double): String? {
    val planned = activity.userEstimatedMinutes
    if (activity.state == "DONE" || activity.state == "PARTIAL") {
        // „copiată din plan” nu e o măsurătoare, deci nu are ce să arate
        if (activity.actualSource == Activity.SOURCE_AS_PLANNED) return null
        val took = CardMirror.took(planned, activity.actualMinutes) ?: return null
        return when (took.versus) {
            CardMirror.Versus.ON_PLAN -> "a durat ${formatDuration(took.actual)}, cât ai plănuit"
            CardMirror.Versus.OVER -> "a durat ${formatDuration(took.actual)}, cu ${formatDuration(took.difference)} peste plan"
            CardMirror.Versus.UNDER -> "a durat ${formatDuration(took.actual)}, cu ${formatDuration(took.difference)} sub plan"
        }
    }
    if (activity.state != "PLANNED") return null
    val estimate = HistoryEstimator.estimate(
        history, activity.titleKey ?: TitleKey.of(activity.title), activity.categoryId, null, priorRatio
    )
    val u = CardMirror.usually(planned, estimate) ?: return null
    val times = if (u.samples == 1) "o dată" else "${u.samples} dăți"
    return if (u.sameActivity) "de obicei îți ia ~${formatDuration(u.minutes)}, nu ${formatDuration(u.planned)} (din $times)"
    else "în categoria asta, de obicei ~${formatDuration(u.minutes)}, nu ${formatDuration(u.planned)}"
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
/** O secțiune a formularului: o etichetă mică și conținutul ei. */
@Composable
private fun FormSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

private fun categoryColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color(0xFF378ADD))

/** Durata cu − / + (din 5 în 5 sub o oră, din 15 în 15 peste) și valori rapide. null = încă nealeasă. */
@Composable
private fun DurationPicker(minutes: Int?, onChange: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(
                    onClick = {
                        val m = minutes ?: return@FilledTonalIconButton
                        onChange((m - if (m <= 60) 5 else 15).coerceAtLeast(5))
                    },
                    enabled = (minutes ?: 0) > 5
                ) { Icon(Icons.Outlined.Remove, contentDescription = "Mai puțin") }
                Text(
                    minutes?.let(::formatDuration) ?: "—",
                    fontFamily = Fraunces,
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center,
                    color = if (minutes == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalIconButton(
                    onClick = {
                        val m = minutes ?: 15
                        onChange(m + if (m < 60) 5 else 15)
                    }
                ) { Icon(Icons.Outlined.Add, contentDescription = "Mai mult") }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(15, 30, 45, 60, 90, 120, 180).forEach { value ->
                FilterChip(
                    selected = minutes == value,
                    onClick = { onChange(value) },
                    label = { Text(formatDuration(value)) }
                )
            }
        }
    }
}

/** Un buton dintr-o alegere: cel ales e plin, celălalt doar cu text. */
@Composable
private fun PickButton(selected: Boolean, onClick: () -> Unit, label: String) {
    if (selected) {
        FilledTonalButton(onClick = onClick) {
            Icon(androidx.compose.material.icons.Icons.Outlined.Check, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label)
        }
    } else {
        TextButton(onClick = onClick) { Text(label) }
    }
}
