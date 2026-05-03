package com.tobevpn.app.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tobevpn.app.R
import com.tobevpn.app.data.repository.UpdateCheckResult

/**
 * "Check for updates" row inside the About card on Settings.
 *
 * Tapping the button bypasses the 7-day cache and forces a fresh GitHub
 * probe. The result is reflected back into the same UpdateBannerHost the
 * Home screen mounts, so if a new version is found the home banner appears
 * the next time the user navigates back.
 *
 * The row also surfaces a transient inline status so the user gets feedback
 * without having to leave Settings — "Доступна v1.0.1" or "Установлена
 * последняя версия" (rendered in `formatStatus`).
 */
@Composable
fun SettingsUpdateCheckRow(viewModel: UpdateViewModel = rememberAppUpdateViewModel()) {
    val state by viewModel.state.collectAsState()
    val inFlight by viewModel.manualCheckInFlight.collectAsState()
    val versionName = remember { com.tobevpn.app.BuildConfig.VERSION_NAME }

    val statusText = when (val s = state) {
        is UpdateUiState.Available ->
            stringResource(R.string.update_available_short, s.info.versionName)
        is UpdateUiState.Downloading,
        is UpdateUiState.ReadyToInstall,
        is UpdateUiState.Failed,
        UpdateUiState.Idle ->
            stringResource(R.string.update_check_uptodate, versionName)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        OutlinedButton(
            onClick = { viewModel.forceCheck() },
            enabled = !inFlight,
            shape = RoundedCornerShape(10.dp),
        ) {
            if (inFlight) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(R.string.update_check_button),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
