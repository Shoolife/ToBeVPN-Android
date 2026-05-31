package com.tobevpn.app.presentation.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.tobevpn.app.R
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val trialTerms = viewModel.trialTerms.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val unitGb = stringResource(R.string.unit_gb)
    val anonTraffic = formatTrafficLimit(bytes = trialTerms.anonBytes, unit = unitGb, locale = locale)
    val bonusTraffic = formatTrafficLimit(bytes = trialTerms.bonusBytes, unit = unitGb, locale = locale)
    val trialDays = pluralStringResource(
        R.plurals.onboarding_trial_days, trialTerms.trialDays, trialTerms.trialDays,
    )

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
        FeatureItem(stringResource(R.string.onboarding_feature_device, bonusTraffic, trialDays))
        Spacer(modifier = Modifier.height(12.dp))
        FeatureItem(stringResource(R.string.onboarding_feature_auth))
        Spacer(modifier = Modifier.height(12.dp))
        FeatureItem(stringResource(R.string.onboarding_feature_tools))

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = {
                viewModel.completeOnboarding()
                onComplete()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_start))
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
