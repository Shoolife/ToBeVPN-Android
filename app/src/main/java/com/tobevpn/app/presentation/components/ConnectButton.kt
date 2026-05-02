package com.tobevpn.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnRed

@Composable
fun ConnectButton(
    isConnected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isConnected) VpnGreen else VpnRed
    val iconTint = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = if (isConnected) "Disconnect" else "Connect",
            tint = iconTint,
        )
    }
}
