package md.utm.digitalmirror.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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

    fun closing(date: String) = "closing/$date"
    fun activity(id: Long) = "activity/$id"
    fun prepDetail(id: Long) = "prep_detail/$id"
}

// tab-urile din bara de jos
private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DAY, "Zi", Icons.Filled.Today),
    Tab(Routes.STATS, "Statistici", Icons.Filled.BarChart),
    Tab(Routes.GOALS, "Obiective", Icons.Filled.Flag),
    Tab(Routes.CAPSULE, "Capsulă", Icons.Filled.Email),
    Tab(Routes.PROFILE, "Profil", Icons.Filled.Person)
)

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

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    // nu adunăm ecrane în stivă la fiecare apăsare pe tab
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Routes.DAY) {
                // deschis din notificare → mergem direct la închiderea zilei de azi
                LaunchedEffect(Unit) {
                    if (openClosingToday && !notificationHandled) {
                        notificationHandled = true
                        navController.navigate(Routes.closing(LocalDate.now().toString()))
                    }
                }
                DayScreen(
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
                        if (firstRun) {
                            navController.navigate(Routes.ONBOARDING) {
                                popUpTo(Routes.AUTH) { inclusive = true }
                            }
                        } else {
                            navController.popBackStack()
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

            composable(Routes.STATS) {
                StatsScreen(onOpenReport = { navController.navigate(Routes.REPORT) })
            }

            composable(Routes.PLAN) {
                PlanScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.WEEK) {
                WeekScreen(
                    onBack = { navController.popBackStack() },
                    onPlanTomorrow = { navController.navigate(Routes.PLAN) }
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
                ClosingScreen(date = date, onBack = { navController.popBackStack() })
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
                navController.navigate(Routes.CAPSULE)
            }
        }
    }
}
