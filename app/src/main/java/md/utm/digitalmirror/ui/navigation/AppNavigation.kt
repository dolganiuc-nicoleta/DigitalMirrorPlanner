package md.utm.digitalmirror.ui.navigation

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
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
import md.utm.digitalmirror.ui.closing.ClosingScreen
import md.utm.digitalmirror.ui.day.DayScreen
import md.utm.digitalmirror.ui.onboarding.OnboardingPrefs
import md.utm.digitalmirror.ui.onboarding.OnboardingScreen
import md.utm.digitalmirror.ui.settings.SettingsScreen
import md.utm.digitalmirror.ui.stats.StatsScreen
import java.time.LocalDate

// rutele aplicației
object Routes {
    const val DAY = "day"
    const val STATS = "stats"
    const val SETTINGS = "settings"
    const val CLOSING = "closing/{date}"
    const val ONBOARDING = "onboarding"

    fun closing(date: String) = "closing/$date"
}

// tab-urile din bara de jos
private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DAY, "Zi", Icons.Filled.Today),
    Tab(Routes.STATS, "Statistici", Icons.Filled.BarChart),
    Tab(Routes.SETTINGS, "Setări", Icons.Filled.Settings)
)

@Composable
fun AppNavigation(openClosingToday: Boolean) {
    val navController = rememberNavController()
    val context = LocalContext.current
    // prima pornire: configurarea inițială; după aceea direct ecranul zilei
    val startDestination = remember { if (OnboardingPrefs.isDone(context)) Routes.DAY else Routes.ONBOARDING }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // bara de jos apare doar pe ecranele principale, nu și pe Închiderea zilei
    val showBottomBar = tabs.any { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.route } == true
    }

    // ca notificarea să deschidă Închiderea zilei o singură dată
    var notificationHandled by rememberSaveable { mutableStateOf(false) }

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
                DayScreen(onCloseDay = { date -> navController.navigate(Routes.closing(date)) })
            }

            composable(Routes.ONBOARDING) {
                OnboardingScreen(onFinish = {
                    navController.navigate(Routes.DAY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }   // înapoi nu mai duce la configurare
                    }
                })
            }

            composable(Routes.STATS) {
                StatsScreen()
            }

            composable(Routes.SETTINGS) {
                SettingsScreen()
            }

            composable(
                route = Routes.CLOSING,
                arguments = listOf(navArgument("date") { type = NavType.StringType })
            ) { entry ->
                val date = entry.arguments?.getString("date") ?: LocalDate.now().toString()
                ClosingScreen(date = date, onBack = { navController.popBackStack() })
            }
        }
    }
}