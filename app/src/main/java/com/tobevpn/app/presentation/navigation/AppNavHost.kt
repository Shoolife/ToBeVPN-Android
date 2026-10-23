package com.tobevpn.app.presentation.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import com.tobevpn.app.presentation.appfilter.AppFilterScreen
import com.tobevpn.app.presentation.auth.AuthScreen
import com.tobevpn.app.presentation.main.MainScreen
import com.tobevpn.app.presentation.onboarding.DisplayScaleHintDialog
import com.tobevpn.app.presentation.onboarding.OnboardingScreen
import com.tobevpn.app.presentation.promocodes.PromocodesScreen
import com.tobevpn.app.presentation.referrals.ReferralsScreen
import com.tobevpn.app.presentation.servers.ServerListScreen
import com.tobevpn.app.presentation.settings.AboutScreen
import com.tobevpn.app.presentation.settings.AdvancedScreen
import com.tobevpn.app.presentation.settings.DisplayScaleTextScreen
import com.tobevpn.app.presentation.settings.PersonalizationScreen
import com.tobevpn.app.presentation.settings.ServerListViewScreen
import com.tobevpn.app.presentation.settings.SettingsScreen
import com.tobevpn.app.presentation.settings.SettingsTransferScreen
import com.tobevpn.app.presentation.settings.SettingsViewModel
import com.tobevpn.app.presentation.settings.SupportScreen
import com.tobevpn.app.presentation.speedtest.SpeedTestHistoryScreen
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
    quickSettingsConnectRequest: Int = 0,
    modifier: Modifier = Modifier,
) {
    val appSessionViewModel = hiltViewModel<AppSessionViewModel>()
    val showDisplayScaleHint by appSessionViewModel.displayScaleHintPending
        .collectAsStateWithLifecycle(initialValue = false)
    // DataStore flips onboardingSeen before navigation finishes. Keep the graph's
    // initial route stable so that its transition is not interrupted, while
    // startFromOnboarding itself remains live for deep links and quick settings.
    val startDestination: Any = remember(navController) {
        if (startFromOnboarding) OnboardingRoute else MainRoute
    }
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
                DeepLinkDestination.SERVERS -> {
                    if (!startFromOnboarding) {
                        navController.navigateSingleTop(ServerListRoute)
                    }
                }
                DeepLinkDestination.SETTINGS -> navController.navigateSingleTop(SettingsRoute)
            }
        }
    }

    LaunchedEffect(quickSettingsConnectRequest, startFromOnboarding) {
        if (quickSettingsConnectRequest > 0 && !startFromOnboarding) {
            navController.navigateSingleTop(MainRoute)
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize(),
    ) {
        screen<OnboardingRoute> {
            OnboardingScreen(
                onComplete = {
                    navController.navigate(MainRoute) {
                        launchSingleTop = true
                        popUpTo(OnboardingRoute) { inclusive = true }
                    }
                },
            )
        }
        screen<MainRoute> {
            MainScreen(
                onNavigateToServers = { navController.navigateSingleTop(ServerListRoute) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
                onNavigateToSettings = { navController.navigateSingleTop(SettingsRoute) },
                onNavigateToStats = { navController.navigateSingleTop(StatsRoute) },
                onNavigateToSpeedTest = { navController.navigateSingleTop(SpeedTestRoute) },
                quickSettingsConnectRequest = quickSettingsConnectRequest,
            )
        }
        screen<ServerListRoute> {
            ServerListScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
            )
        }
        screen<AuthRoute> {
            AuthScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        screen<StatsRoute> {
            StatsScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        screen<SpeedTestRoute> {
            SpeedTestScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToHistory = { navController.navigateSingleTop(SpeedTestHistoryRoute) },
            )
        }
        screen<SpeedTestHistoryRoute> {
            SpeedTestHistoryScreen(
                onBack = { navController.popBackStackOrRecover(SpeedTestRoute) },
            )
        }
        screen<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
                onNavigateToPersonalization = { navController.navigateSingleTop(PersonalizationRoute) },
                onNavigateToAdvanced = { navController.navigateSingleTop(AdvancedRoute) },
                onNavigateToSupport = { navController.navigateSingleTop(SupportRoute) },
                onNavigateToAbout = { navController.navigateSingleTop(AboutRoute) },
                onNavigateToReferrals = { navController.navigateSingleTop(ReferralsRoute) },
                onNavigateToPromocodes = { navController.navigateSingleTop(PromocodesRoute) },
                onNavigateToSettingsTransfer = { navController.navigateSingleTop(SettingsTransferRoute) },
            )
        }
        screen<PersonalizationRoute> { entry ->
            // Share the SettingsRoute-scoped ViewModel so its init side effects
            // (subscription sync, device registration, pairing observer) run
            // once, not once per sub-screen.
            val parentEntry = remember(entry) { navController.getBackStackEntry(SettingsRoute) }
            PersonalizationScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToDisplayScaleText = {
                    navController.navigateSingleTop(DisplayScaleTextRoute)
                },
                onNavigateToServerListView = {
                    navController.navigateSingleTop(ServerListViewRoute)
                },
                viewModel = hiltViewModel<SettingsViewModel>(parentEntry),
            )
        }
        screen<ServerListViewRoute> { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(SettingsRoute) }
            ServerListViewScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                viewModel = hiltViewModel<SettingsViewModel>(parentEntry),
            )
        }
        screen<DisplayScaleTextRoute> { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(SettingsRoute) }
            DisplayScaleTextScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                viewModel = hiltViewModel<SettingsViewModel>(parentEntry),
            )
        }
        screen<SettingsTransferRoute> {
            SettingsTransferScreen(
                onBack = { navController.popBackStackOrRecover(SettingsRoute) },
            )
        }
        screen<AdvancedRoute> { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(SettingsRoute) }
            AdvancedScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToAppFilter = { navController.navigateSingleTop(AppFilterRoute) },
                viewModel = hiltViewModel<SettingsViewModel>(parentEntry),
            )
        }
        screen<SupportRoute> {
            SupportScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
        screen<AboutRoute> { entry ->
            val parentEntry = remember(entry) { navController.getBackStackEntry(SettingsRoute) }
            AboutScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                viewModel = hiltViewModel<SettingsViewModel>(parentEntry),
            )
        }
        screen<ReferralsRoute> {
            ReferralsScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
            )
        }
        screen<PromocodesRoute> {
            PromocodesScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
                onNavigateToAuth = { navController.navigateSingleTop(AuthRoute) },
            )
        }
        screen<AppFilterRoute> {
            AppFilterScreen(
                onBack = { navController.popBackStackOrRecover(startDestination) },
            )
        }
    }

    if (showDisplayScaleHint) {
        DisplayScaleHintDialog(
            onDismiss = appSessionViewModel::consumeDisplayScaleHint,
            onConfigure = {
                appSessionViewModel.consumeDisplayScaleHint()
                // DisplayScaleTextRoute uses the Settings-scoped ViewModel, so
                // keep SettingsRoute directly below it in the back stack.
                navController.navigateSingleTop(SettingsRoute)
                navController.navigateSingleTop(DisplayScaleTextRoute)
            },
        )
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

/**
 * A destination whose screen lets touches through while it animates out.
 * Going back, Navigation keeps the closed screen on top for the whole fade, so
 * a tap right after leaving (About, then Referrals in Settings) hit the closed
 * screen's buttons. While leaving, the screen is drawn from a snapshot layer
 * and the live content is clipped to nothing: it is still composed (and keeps
 * fading the same way) but no longer takes part in hit testing, so the tap
 * reaches the screen underneath.
 */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        val leaving = transition.targetState == EnterExitState.PostExit
        val snapshot = rememberGraphicsLayer()
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (leaving) {
                            Modifier.graphicsLayer {
                                clip = true
                                shape = NoHitShape
                            }
                        } else {
                            Modifier
                        },
                    )
                    .drawWithContent {
                        snapshot.record { this@drawWithContent.drawContent() }
                        if (!leaving) drawLayer(snapshot)
                    },
            ) {
                content(entry)
            }
            if (leaving) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawBehind { drawLayer(snapshot) },
                )
            }
        }
    }
}

/** A clip that contains no point: the clipped content draws and hits nothing. */
private object NoHitShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Rectangle(Rect.Zero)
}
