package com.tobevpn.app.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed

@Composable
fun UsageIndicator(
    usageInfo: UsageInfo,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
    ) {
        // Traffic
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Traffic", style = MaterialTheme.typography.labelMedium)
            Text(
                "${formatBytes(usageInfo.bytesUsed)} / ${formatBytes(usageInfo.bytesLimit)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { usageInfo.trafficProgress },
            modifier = Modifier.fillMaxWidth(),
            color = progressColor(usageInfo.trafficProgress),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Time
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Time", style = MaterialTheme.typography.labelMedium)
            Text(
                "${formatTime(usageInfo.timeUsedSeconds)} / ${formatTime(usageInfo.timeLimitSeconds)}",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { usageInfo.timeProgress },
            modifier = Modifier.fillMaxWidth(),
            color = progressColor(usageInfo.timeProgress),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

private fun progressColor(progress: Float) = when {
    progress < 0.7f -> VpnGreen
    progress < 0.9f -> VpnOrange
    else -> VpnRed
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun formatTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
