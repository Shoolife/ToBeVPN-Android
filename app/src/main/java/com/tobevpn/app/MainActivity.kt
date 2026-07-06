package com.tobevpn.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.presentation.navigation.AppNavHost
import com.tobevpn.app.presentation.splash.SplashScreen
import com.tobevpn.app.domain.model.ThemeMode
import com.tobevpn.app.presentation.theme.ToBeVPNTheme
import com.tobevpn.app.update.UpdateBannerCheck
import com.tobevpn.app.update.UpdateBannerHost
import com.tobevpn.app.util.DeepLinkBus
import com.tobevpn.app.util.DeepLinkDestination
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var prefsDataStore: PrefsDataStore

    @Inject
    lateinit var deepLinkBus: DeepLinkBus

    private val quickSettingsConnectRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Immediately dismiss the system splash
        systemSplash.setKeepOnScreenCondition { false }
        enableEdgeToEdge()
        dispatchDeepLink(intent)
        dispatchQuickSettingsIntent(intent)
        dispatchQuickSettingsPreferencesIntent(intent)

        setContent {
            val quickSettingsConnectRequest by quickSettingsConnectRequests.collectAsStateWithLifecycle()
            val notificationPermissionPrompted by prefsDataStore.notificationPermissionPrompted
                .collectAsStateWithLifecycle(initialValue = true)
            val themeMode by prefsDataStore.themeMode
                .map { ThemeMode.fromName(it) }
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}
            var showStartupSplash by rememberSaveable {
                mutableStateOf(savedInstanceState == null)
            }

            LaunchedEffect(notificationPermissionPrompted) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    notificationPermissionPrompted
                ) {
                    return@LaunchedEffect
                }
                prefsDataStore.setNotificationPermissionPrompted()
                if (ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                ToBeVPNTheme(themeMode = themeMode) {
                    // Surface establishes the app's background color and
                    // LocalContentColor for screens that don't use Scaffold
                    // (e.g. Onboarding) — without it, headline Text renders
                    // black even in dark theme.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val onboardingSeen by remember {
                            prefsDataStore.onboardingSeen.map { it as Boolean? }
                        }.collectAsStateWithLifecycle(initialValue = null)

                        val navController = rememberNavController()
                        Box(modifier = Modifier.fillMaxSize()) {
                            onboardingSeen?.let { seen ->
                                AppNavHost(
                                    navController = navController,
                                    startFromOnboarding = !seen,
                                    deepLinkBus = deepLinkBus,
                                    quickSettingsConnectRequest = quickSettingsConnectRequest,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }

                // In-app updater banner. Mounted at the Activity level so it
                // overlays every NavHost route — Home, Servers, Settings,
                // Stats, etc. The state is owned by an Activity-scoped
                // UpdateViewModel, so the Settings "Check for updates" button
                // and this overlay stay in sync. The banner persists until
                // the user dismisses it via the in-card "Later" button.
                val updateRequired by prefsDataStore.observeUpdateRequired()
                    .collectAsStateWithLifecycle(initialValue = false)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    UpdateBannerCheck()
                    // Hide the top banner when the block-update dialog is up —
                    // the dialog already shows download progress in-place.
                    if (!updateRequired) {
                        UpdateBannerHost()
                    }
                }

                if (showStartupSplash) {
                    SplashScreen(onFinished = { showStartupSplash = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // setIntent so any subsequent getIntent() returns the new one — keeps
        // the framework's notion of "current intent" in sync with what we just
        // received, in case other code reads it later.
        setIntent(intent)
        dispatchDeepLink(intent)
        dispatchQuickSettingsIntent(intent)
        dispatchQuickSettingsPreferencesIntent(intent)
    }

    private fun dispatchDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        if (!deepLinkBus.supports(uri)) return
        deepLinkBus.post(uri)
    }

    private fun dispatchQuickSettingsIntent(intent: Intent?) {
        val fromTile = intent?.action == ACTION_CONNECT_FROM_QS_TILE ||
            intent?.getBooleanExtra(EXTRA_CONNECT_FROM_QS_TILE, false) == true
        if (!fromTile) return
        quickSettingsConnectRequests.value += 1
    }

    private fun dispatchQuickSettingsPreferencesIntent(intent: Intent?) {
        if (intent?.action != ACTION_QS_TILE_PREFERENCES) return
        deepLinkBus.navigateTo(DeepLinkDestination.SERVERS)
    }

    companion object {
        const val ACTION_CONNECT_FROM_QS_TILE = "com.tobevpn.app.action.CONNECT_FROM_QS_TILE"
        const val EXTRA_CONNECT_FROM_QS_TILE = "com.tobevpn.app.extra.CONNECT_FROM_QS_TILE"
        private const val ACTION_QS_TILE_PREFERENCES =
            "android.service.quicksettings.action.QS_TILE_PREFERENCES"
    }
}
