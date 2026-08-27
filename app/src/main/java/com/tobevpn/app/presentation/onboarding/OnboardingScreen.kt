package com.tobevpn.app.presentation.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.tobevpn.app.R
import com.tobevpn.app.presentation.theme.isLargeTabletLayout
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val trialTerms = viewModel.trialTerms.collectAsStateWithLifecycle().value
    val locale = LocalLocale.current.platformLocale
    val unitGb = stringResource(R.string.unit_gb)
    val anonTraffic = formatTrafficLimit(bytes = trialTerms.anonBytes, unit = unitGb, locale = locale)
    val freeTrialTraffic = formatTrafficLimit(
        bytes = SIGNED_IN_TRIAL_TRAFFIC_BYTES,
        unit = unitGb,
        locale = locale,
    )
    val freeTrialDuration = pluralStringResource(
        R.plurals.onboarding_trial_days,
        trialTerms.freeTrialDays,
        trialTerms.freeTrialDays,
    )
    val signedInTrialText = stringResource(
        R.string.onboarding_feature_device,
        freeTrialTraffic,
        freeTrialDuration,
    )
    val useLargeTabletLayout = isLargeTabletLayout()
    val completeOnboarding = {
        viewModel.completeOnboarding()
        onComplete()
    }

    if (useLargeTabletLayout) {
        LargeTabletOnboardingContent(
            anonTraffic = anonTraffic,
            signedInTrialText = signedInTrialText,
            isDark = isDark,
            onComplete = completeOnboarding,
        )
    } else {
        PhoneOnboardingContent(
            anonTraffic = anonTraffic,
            signedInTrialText = signedInTrialText,
            isDark = isDark,
            onComplete = completeOnboarding,
        )
    }
}

@Composable
private fun PhoneOnboardingContent(
    anonTraffic: String,
    signedInTrialText: String,
    isDark: Boolean,
    onComplete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.onboarding_logo),
            contentDescription = null,
            modifier = Modifier.size(112.dp),
        )

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(48.dp))

        FeatureItem(stringResource(R.string.onboarding_feature_trial, anonTraffic))
        Spacer(modifier = Modifier.height(12.dp))
        FeatureItem(signedInTrialText)
        Spacer(modifier = Modifier.height(12.dp))
        FeatureItem(stringResource(R.string.onboarding_feature_auth))
        Spacer(modifier = Modifier.height(12.dp))
        FeatureItem(stringResource(R.string.onboarding_feature_tools))

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = onboardingButtonColors(isDark),
        ) {
            Text(stringResource(R.string.onboarding_start))
        }
    }
}

@Composable
private fun LargeTabletOnboardingContent(
    anonTraffic: String,
    signedInTrialText: String,
    isDark: Boolean,
    onComplete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 72.dp, vertical = 52.dp),
        horizontalArrangement = Arrangement.spacedBy(88.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(0.43f)
                .fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.onboarding_logo),
                contentDescription = null,
                modifier = Modifier.size(260.dp),
            )

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = 44.sp,
                    lineHeight = 52.sp,
                ),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = stringResource(R.string.onboarding_tablet_subtitle),
                modifier = Modifier.widthIn(max = 360.dp),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 25.sp,
                    lineHeight = 32.sp,
                ),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(
            modifier = Modifier
                .weight(0.57f)
                .fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth(),
            ) {
                TabletFeatureItem(
                    icon = Icons.Default.DataUsage,
                    text = stringResource(R.string.onboarding_feature_trial, anonTraffic),
                    isDark = isDark,
                )
                Spacer(modifier = Modifier.height(24.dp))
                TabletFeatureItem(
                    icon = Icons.Default.Devices,
                    text = signedInTrialText,
                    isDark = isDark,
                )
                Spacer(modifier = Modifier.height(24.dp))
                TabletFeatureItem(
                    icon = Icons.Default.Lock,
                    text = stringResource(R.string.onboarding_feature_auth),
                    isDark = isDark,
                )
                Spacer(modifier = Modifier.height(24.dp))
                TabletFeatureItem(
                    icon = Icons.Default.Speed,
                    text = stringResource(R.string.onboarding_feature_tools),
                    isDark = isDark,
                )

                Spacer(modifier = Modifier.height(44.dp))

                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .align(Alignment.Start)
                        .offset(x = TABLET_FEATURE_TEXT_INSET)
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                        .height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = onboardingButtonColors(isDark),
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_start),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 21.sp,
                            lineHeight = 26.sp,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FeatureItem(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TabletFeatureItem(
    icon: ImageVector,
    text: String,
    isDark: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    color = if (isDark) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                        Color(0xFFE1E2E6)
                    },
                    shape = RoundedCornerShape(12.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF3F3F3F),
            )
        }

        Text(
            text = text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 22.sp,
                lineHeight = 29.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun onboardingButtonColors(isDark: Boolean) = if (isDark) {
    ButtonDefaults.buttonColors()
} else {
    ButtonDefaults.buttonColors(
        containerColor = Color(0xFF3F3F3F),
        contentColor = Color.White,
    )
}

private fun formatTrafficLimit(bytes: Long, unit: String, locale: Locale): String {
    val gigabytes = bytes / BYTES_IN_GB
    val isWhole = abs(gigabytes - gigabytes.roundToLong()) < 0.01
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = if (isWhole) 0 else 1
        minimumFractionDigits = 0
    }.format(gigabytes)
    return "$formatted $unit"
}

private const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0
private const val SIGNED_IN_TRIAL_TRAFFIC_BYTES = 3_221_225_472L
private val TABLET_FEATURE_TEXT_INSET = 54.dp
