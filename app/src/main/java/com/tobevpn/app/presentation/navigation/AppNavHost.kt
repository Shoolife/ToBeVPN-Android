package com.tobevpn.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.presentation.auth.AuthScreen
import com.tobevpn.app.presentation.main.MainScreen
import com.tobevpn.app.presentation.onboarding.OnboardingScreen
import com.tobevpn.app.presentation.servers.ServerListScreen
import com.tobevpn.app.presentation.settings.SettingsScreen
import com.tobevpn.app.presentation.speedtest.SpeedTestScreen
import com.tobevpn.app.presentation.stats.StatsScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startFromOnboarding: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val startDestination: Any = if (startFromOnboarding) OnboardingRoute else MainRoute

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(MainRoute) {
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }
        composable<MainRoute> {
            MainScreen(
                onNavigateToServers = { navController.navigate(ServerListRoute) },
                onNavigateToAuth = { navController.navigate(AuthRoute) },
                onNavigateToSettings = { navController.navigate(SettingsRoute) },
                onNavigateToStats = { navController.navigate(StatsRoute) },
                onNavigateToSpeedTest = { navController.navigate(SpeedTestRoute) },
            )
        }
        composable<ServerListRoute> {
            ServerListScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<AuthRoute> {
            AuthScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<StatsRoute> {
            StatsScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SpeedTestRoute> {
            SpeedTestScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigateToAuth = { navController.navigate(AuthRoute) },
            )
        }
    }
}
