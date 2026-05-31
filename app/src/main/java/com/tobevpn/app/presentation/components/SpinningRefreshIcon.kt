package com.tobevpn.app.presentation.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color

/**
 * Refresh icon that spins continuously while [spinning] is true, matching the
 * desktop client's 0.9s linear rotation on its refresh button.
 */
@Composable
fun SpinningRefreshIcon(
    spinning: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val transition = rememberInfiniteTransition(label = "refresh-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "refresh-angle",
    )
    Icon(
        imageVector = Icons.Default.Refresh,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.rotate(if (spinning) angle else 0f),
    )
}
