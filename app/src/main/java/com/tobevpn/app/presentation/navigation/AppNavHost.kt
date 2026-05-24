package com.tobevpn.app.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.tobevpn.app.presentation.appfilter.AppFilterScreen
import com.tobevpn.app.presentation.auth.AuthScreen
import com.tobevpn.app.presentation.main.MainScreen
import com.tobevpn.app.presentation.onboarding.OnboardingScreen
import com.tobevpn.app.presentation.servers.ServerListScreen
import com.tobevpn.app.presentation.settings.SettingsScreen
import com.tobevpn.app.presentation.speedtest.SpeedTestScreen
import com.tobevpn.app.presentation.stats.StatsScreen
import com.tobevpn.app.util.DeepLinkBus
import com.tobevpn.app.util.DeepLinkDestination
import kotlinx.coroutines.delay

@Composable
fun AppNavHost(
    navController: NavHostController,
    startFromOnboarding: Boolean = false,
    deepLinkBus: DeepLinkBus,
    modifier: Modifier = Modifier,
) {
    val startDestination: Any = if (startFromOnboarding) OnboardingRoute else MainRoute
    val currentBackStackEntry by navController.currentBackStackEntryAsState()

    LaunchedEffect(currentBackStackEntry, startFromOnboarding) {
        if (currentBackStackEntry == null) {
            delay(NAV_RECOVERY_DELAY_MS)
            if (navController.currentBackStackEntry == null) {
                navController.navigateSingleTop(startDestination)
            }
        }
    }

    LaunchedEffect(navController) {
        deepLinkBus.navigationRequests.collect { destination ->
            when (destination) {
                DeepLinkDestination.AUTH -> navController.navigateSingleTop(AuthRoute)
                DeepLinkDestination.MAIN -> navController.navigateSingleTop(MainRoute)
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize(),
    ) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(MainRoute) {
                        launchSingleTop = true
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }
        composable<MainRoute> {
            MainScreen(
                onNavigateToServers = { navController.navigateSingleTop(ServerListRoute) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
                onNavigateToSettings = { navController.navigateSingleTop(SettingsRoute) },
                onNavigateToStats = { navController.navigateSingleTop(StatsRoute) },
                onNavigateToSpeedTest = { navController.navigateSingleTop(SpeedTestRoute) },
            )
        }
        composable<ServerListRoute> {
            ServerListScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        composable<AuthRoute> {
            AuthScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        composable<StatsRoute> {
            StatsScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        composable<SpeedTestRoute> {
            SpeedTestScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
                onNavigateToAppFilter = { navController.navigateSingleTop(AppFilterRoute) },
            )
        }
        composable<AppFilterRoute> {
            AppFilterScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
    }
}

private fun NavHostController.navigateSingleTop(route: Any) {
    runCatching {
        navigate(route) {
            launchSingleTop = true
            restoreState = true
        }
    }
}

private fun NavHostController.popBackStackOrRecover(startDestination: Any) {
    val popped = runCatching { popBackStack() }.getOrDefault(false)
    if (!popped || currentBackStackEntry == null) {
        navigateSingleTop(startDestination)
    }
}

private const val NAV_RECOVERY_DELAY_MS = 50L
