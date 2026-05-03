package com.tobevpn.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.presentation.navigation.AppNavHost
import com.tobevpn.app.presentation.splash.SplashScreen
import com.tobevpn.app.presentation.theme.ToBeVPNTheme
import com.tobevpn.app.update.UpdateBannerCheck
import com.tobevpn.app.update.UpdateBannerHost
import com.tobevpn.app.util.DeepLinkBus
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var prefsDataStore: PrefsDataStore

    @Inject
    lateinit var deepLinkBus: DeepLinkBus

    override fun onCreate(savedInstanceState: Bundle?) {
        val systemSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Immediately dismiss the system splash
        systemSplash.setKeepOnScreenCondition { false }
        enableEdgeToEdge()
        dispatchDeepLink(intent)

        var splashFinished by mutableStateOf(false)

        setContent {
            val mainAlpha by animateFloatAsState(
                targetValue = if (splashFinished) 1f else 0f,
                animationSpec = tween(400),
                label = "mainAlpha",
            )

            Box(modifier = Modifier.fillMaxSize()) {
                ToBeVPNTheme {
                    // Surface establishes the app's background color and
                    // LocalContentColor for screens that don't use Scaffold
                    // (e.g. Onboarding) — without it, headline Text renders
                    // black even in dark theme.
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        val onboardingSeen by prefsDataStore.onboardingSeen
                            .collectAsStateWithLifecycle(initialValue = true)

                        val navController = rememberNavController()
                        Box(modifier = Modifier.alpha(mainAlpha)) {
                            AppNavHost(
                                navController = navController,
                                startFromOnboarding = !onboardingSeen,
                            )
                        }
                    }
                }

                // In-app updater banner. Mounted at the Activity level so it
                // overlays every NavHost route — Home, Servers, Settings,
                // Stats, etc. The state is owned by an Activity-scoped
                // UpdateViewModel, so the Settings "Check for updates" button
                // and this overlay stay in sync. The banner persists until
                // the user dismisses it via the in-card "Later" button.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp)
                        .alpha(mainAlpha),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    UpdateBannerCheck()
                    UpdateBannerHost()
                }

                if (!splashFinished) {
                    SplashScreen(onFinished = { splashFinished = true })
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
    }

    private fun dispatchDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        // Ignore non-app schemes outright — manifest only filters tobevpn://
        // but defense-in-depth: never forward foreign URIs to the bus.
        if (uri.scheme != "tobevpn") return
        deepLinkBus.post(uri)
    }
}
