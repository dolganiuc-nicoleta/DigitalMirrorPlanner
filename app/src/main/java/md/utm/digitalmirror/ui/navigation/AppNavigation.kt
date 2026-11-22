package md.utm.digitalmirror.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import md.utm.digitalmirror.ui.mirror.MIRROR_TAB_CAPSULE
import md.utm.digitalmirror.ui.mirror.MirrorScreen
import md.utm.digitalmirror.ui.plan.CaptureScreen
import md.utm.digitalmirror.ui.plan.PlanChoice
import md.utm.digitalmirror.ui.plan.PlanHubSheet
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import md.utm.digitalmirror.data.auth.AuthRepository
import md.utm.digitalmirror.data.prefs.AuthPrefs
import md.utm.digitalmirror.ui.activity.ActivityDetailScreen
import md.utm.digitalmirror.ui.auth.AuthScreen
import md.utm.digitalmirror.ui.capsule.CapsuleScreen
import md.utm.digitalmirror.ui.closing.ClosingScreen
import md.utm.digitalmirror.ui.day.DayScreen
import md.utm.digitalmirror.ui.goals.GoalsScreen
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import md.utm.digitalmirror.ui.onboarding.OnboardingScreen
import md.utm.digitalmirror.ui.places.PlacesScreen
import md.utm.digitalmirror.ui.prep.PrepDetailScreen
import md.utm.digitalmirror.ui.prep.PrepNewScreen
import md.utm.digitalmirror.ui.prep.PrepScreen
import md.utm.digitalmirror.ui.profile.ProfileScreen
import md.utm.digitalmirror.ui.plan.PlanScreen
import md.utm.digitalmirror.ui.report.ReportScreen
import md.utm.digitalmirror.ui.settings.SettingsScreen
import md.utm.digitalmirror.ui.week.WeekScreen
import md.utm.digitalmirror.ui.stats.StatsScreen
import java.time.LocalDate

// rutele aplicației
object Routes {
    const val DAY = "day"
    const val STATS = "stats"
    const val GOALS = "goals"
    const val CAPSULE = "capsule"
    const val REPORT = "report"
    const val PLAN = "plan"
    const val WEEK = "week"
    const val SETTINGS = "settings"
    const val PROFILE = "profile"
    const val PREP = "prep"
    const val PREP_NEW = "prep_new"
    const val PREP_DETAIL = "prep_detail/{id}"
    const val CLOSING = "closing/{date}"
    const val ONBOARDING = "onboarding"
    const val AUTH = "auth"
    const val PLACES = "places"
    const val ACTIVITY = "activity/{id}"
    const val MIRROR = "mirror"
    const val CAPTURE = "capture"

    fun closing(date: String) = "closing/$date"
    fun activity(id: Long) = "activity/$id"
    fun prepDetail(id: Long) = "prep_detail/$id"
}

/** Cheia prin care un ecran cere ecranului zilei să arate o anumită dată. */
private const val OPEN_DATE = "open_date"

/** Cheia prin care „+ › Ceva acum” cere ecranului zilei să deschidă formularul de activitate. */
private const val OPEN_ADD = "open_add"

/** Cheia prin care „+ › Pentru săptămână” cere ecranului săptămânii să deschidă o sarcină nouă. */
private const val OPEN_NEW_TASK = "open_new_task"

// tab-urile din bara de jos; în mijloc stă „+”, singurul loc din care planifici
private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val leftTabs = listOf(
    Tab(Routes.DAY, "Azi", Icons.Outlined.WbSunny),
    Tab(Routes.WEEK, "Săptămâna", Icons.Outlined.CalendarMonth)
)
private val rightTabs = listOf(
    Tab(Routes.MIRROR, "Oglinda", Icons.Outlined.Insights),
    Tab(Routes.PROFILE, "Eu", Icons.Outlined.Person)
)
private val tabs = leftTabs + rightTabs

@Composable
fun AppNavigation(openClosingToday: Boolean, openCapsule: Boolean = false) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // prima pornire: autentificare (sau „fără cont”), apoi configurarea inițială; după aceea direct ecranul zilei
    val startDestination = remember {
        when {
            OnboardingPrefs.isDone(context) -> Routes.DAY
            AuthRepository.isSignedIn || AuthPrefs.skipped(context) -> Routes.ONBOARDING
            else -> Routes.AUTH
        }
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // bara de jos apare doar pe ecranele principale, nu și pe Închiderea zilei
    val showBottomBar = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }

    // ca notificarea să deschidă Închiderea zilei o singură dată
    var notificationHandled by rememberSaveable { mutableStateOf(false) }
    var capsuleHandled by rememberSaveable { mutableStateOf(false) }
    var mirrorTab by rememberSaveable { mutableIntStateOf(0) }
    var planHubOpen by rememberSaveable { mutableStateOf(false) }

    fun openTab(route: String) {
        navController.navigate(route) {
            // nu adunăm ecrane în stivă la fiecare apăsare pe tab
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    @Composable
                    fun RowScope.TabItem(tab: Tab) {
                        NavigationBarItem(
                            selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                            onClick = { openTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, maxLines = 1) }
                        )
                    }
                    leftTabs.forEach { TabItem(it) }
                    // „+”: un cerc mare, ridicat, în mijloc
                    // doar cât bara (fillMaxHeight ar întinde bara pe tot ecranul)
                    Box(Modifier.weight(1f).align(Alignment.CenterVertically), contentAlignment = Alignment.Center) {
                        Surface(
                            onClick = { planHubOpen = true },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .size(58.dp)
                                .semantics { contentDescription = "Planifică: ce ai de făcut?" }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    rightTabs.forEach { TabItem(it) }
                }
            }
        }
    ) { innerPadding ->
        if (planHubOpen) {
            PlanHubSheet(
                onDismiss = { planHubOpen = false },
                onChoose = { choice ->
                    planHubOpen = false
                    when (choice) {
                        PlanChoice.WRITE -> navController.navigate(Routes.CAPTURE)
                        PlanChoice.NOW -> {
                            openTab(Routes.DAY)
                            runCatching { navController.getBackStackEntry(Routes.DAY).savedStateHandle[OPEN_ADD] = true }
                        }
                        PlanChoice.WEEK -> {
                            openTab(Routes.WEEK)
                            runCatching { navController.getBackStackEntry(Routes.WEEK).savedStateHandle[OPEN_NEW_TASK] = true }
                        }
                        PlanChoice.EVENT -> navController.navigate(Routes.PREP_NEW)
                    }
                }
            )
        }
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Routes.DAY) { entry ->
                // deschis din notificare → mergem direct la închiderea zilei de azi
                LaunchedEffect(Unit) {
                    if (openClosingToday && !notificationHandled) {
                        notificationHandled = true
                        navController.navigate(Routes.closing(LocalDate.now().toString()))
                    }
                }
                // ziua cerută de alt ecran (de ex. „Deschide ziua de mâine” din închiderea zilei)
                val requested by entry.savedStateHandle.getStateFlow<String?>(OPEN_DATE, null).collectAsState()
                val requestAdd by entry.savedStateHandle.getStateFlow(OPEN_ADD, false).collectAsState()
                DayScreen(
                    requestedDate = requested,
                    onRequestedDateShown = { entry.savedStateHandle[OPEN_DATE] = null },
                    requestAdd = requestAdd,
                    onAddShown = { entry.savedStateHandle[OPEN_ADD] = false },
                    onCloseDay = { date -> navController.navigate(Routes.closing(date)) },
                    onOpenActivity = { id -> navController.navigate(Routes.activity(id)) },
                    onPlan = { navController.navigate(Routes.PLAN) },
                    onPlanWeek = { navController.navigate(Routes.WEEK) },
                    onPrep = { navController.navigate(Routes.PREP) }
                )
            }

            composable(Routes.AUTH) {
                // la prima pornire nu există „înapoi”; din Setări există și nu arătăm „Continuă fără cont”
                val firstRun = !OnboardingPrefs.isDone(context)
                AuthScreen(
                    allowSkip = firstRun,
                    onBack = if (firstRun) null else ({ navController.popBackStack() }),
                    onDone = {
                        when {
                            // cont nou, sau fără copie în cloud: configurarea inițială
                            !OnboardingPrefs.isDone(context) -> navController.navigate(Routes.ONBOARDING) {
                                popUpTo(Routes.AUTH) { inclusive = true }
                            }
                            // datele au fost aduse din cloud: direct ecranul zilei
                            firstRun -> navController.navigate(Routes.DAY) {
                                popUpTo(Routes.AUTH) { inclusive = true }
                            }
                            else -> navController.popBackStack()
                        }
                    }
                )
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinish = {
                    navController.navigate(Routes.DAY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }   // înapoi nu mai duce la configurare
                    }
                })
            }

            // Oglinda: statistici, obiective, capsule
            composable(Routes.MIRROR) {
                MirrorScreen(initialTab = mirrorTab, onOpenReport = { navController.navigate(Routes.REPORT) })
            }

            composable(Routes.STATS) {
                StatsScreen(onOpenReport = { navController.navigate(Routes.REPORT) })
            }

            composable(Routes.PLAN) {
                PlanScreen(onBack = { navController.popBackStack() })
            }

            // „Ce ai de făcut?”: un singur câmp pentru tot
            composable(Routes.CAPTURE) {
                CaptureScreen(onBack = { navController.popBackStack() })
            }

            // Săptămâna e acum un tab: fără „Înapoi”
            composable(Routes.WEEK) { entry ->
                val newTask by entry.savedStateHandle.getStateFlow(OPEN_NEW_TASK, false).collectAsState()
                WeekScreen(
                    onBack = null,
                    onPlanTomorrow = { navController.navigate(Routes.PLAN) },
                    startAdding = newTask,
                    onStartAddingShown = { entry.savedStateHandle[OPEN_NEW_TASK] = false }
                )
            }

            composable(Routes.PREP) {
                PrepScreen(
                    onBack = { navController.popBackStack() },
                    onNew = { navController.navigate(Routes.PREP_NEW) },
                    onOpen = { id -> navController.navigate(Routes.prepDetail(id)) }
                )
            }

            composable(Routes.PREP_NEW) {
                PrepNewScreen(
                    onBack = { navController.popBackStack() },
                    onCreated = { id ->
                        navController.navigate(Routes.prepDetail(id)) {
                            popUpTo(Routes.PREP_NEW) { inclusive = true }   // înapoi nu mai duce la formular
                        }
                    }
                )
            }

            composable(
                route = Routes.PREP_DETAIL,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                PrepDetailScreen(
                    eventId = id,
                    onBack = { navController.popBackStack() },
                    onOpenActivity = { activityId -> navController.navigate(Routes.activity(activityId)) }
                )
            }

            composable(Routes.REPORT) {
                ReportScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.GOALS) {
                GoalsScreen()
            }

            composable(Routes.CAPSULE) {
                CapsuleScreen()
            }

            composable(Routes.PROFILE) {
                ProfileScreen(
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onOpenAuth = { navController.navigate(Routes.AUTH) }
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { navController.popBackStack() },
                    onOpenPlaces = { navController.navigate(Routes.PLACES) }
                )
            }

            composable(Routes.PLACES) {
                PlacesScreen(onBack = { navController.popBackStack() })
            }

            composable(
                route = Routes.CLOSING,
                arguments = listOf(navArgument("date") { type = NavType.StringType })
            ) { entry ->
                val date = entry.arguments?.getString("date") ?: LocalDate.now().toString()
                ClosingScreen(
                    date = date,
                    onBack = { navController.popBackStack() },
                    onOpenDay = { day ->
                        navController.previousBackStackEntry?.savedStateHandle?.set(OPEN_DATE, day)
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Routes.ACTIVITY,
                arguments = listOf(navArgument("id") { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong("id") ?: return@composable
                ActivityDetailScreen(activityId = id, onBack = { navController.popBackStack() })
            }
        }

        // deschis din notificarea capsulei → mergem direct la tabul Capsulă, o singură dată
        LaunchedEffect(openCapsule) {
            if (openCapsule && !capsuleHandled && startDestination != Routes.ONBOARDING && startDestination != Routes.AUTH) {
                capsuleHandled = true
                mirrorTab = MIRROR_TAB_CAPSULE
                openTab(Routes.MIRROR)
            }
        }
    }
}