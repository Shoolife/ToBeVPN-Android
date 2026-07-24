package com.tobevpn.app.presentation.speedtest

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    onBack: () -> Unit,
    viewModel: SpeedTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viaVpn by viewModel.viaVpn.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.speed_test_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    SpeedRouteBadge(
                        viaVpn = viaVpn,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Speedometer
            SpeedGauge(
                speed = state.currentSpeed,
                phase = state.phase,
                modifier = Modifier.size(260.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Phase label
            Text(
                text = when {
                    state.errorRes != null -> stringResource(state.errorRes!!)
                    state.phase == SpeedTestPhase.Idle -> stringResource(R.string.speed_press_start)
                    state.phase == SpeedTestPhase.Ping -> stringResource(R.string.speed_measuring_ping)
                    state.phase == SpeedTestPhase.Download -> stringResource(R.string.speed_downloading)
                    state.phase == SpeedTestPhase.Done -> stringResource(R.string.speed_done)
                    else -> ""
                },
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                color = if (state.errorRes != null) VpnRed else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(36.dp))

            // Results
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ResultCard(
                    label = stringResource(R.string.speed_ping),
                    value = if (state.ping > 0) "${state.ping}" else "—",
                    unit = stringResource(R.string.speed_unit_ms),
                    color = if (state.ping in 1..100) VpnGreen
                    else if (state.ping in 101..200) VpnOrange
                    else if (state.ping > 200) VpnRed
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ResultCard(
                    label = stringResource(R.string.speed_download),
                    value = if (state.downloadSpeed > 0) "%.1f".format(state.downloadSpeed) else "—",
                    unit = stringResource(R.string.speed_unit_mbps),
                    color = VpnGreen,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // Start / Reset button
            // Same dark-grey CTA style as the "Купить" button in the
            // subscription sheet — they're both committed primary actions.
            Button(
                onClick = {
                    if (state.phase == SpeedTestPhase.Done || state.phase == SpeedTestPhase.Idle) {
                        viewModel.startTest()
                    } else {
                        viewModel.reset()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                shape = RoundedCornerShape(16.dp),
                colors = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                    androidx.compose.material3.ButtonDefaults.buttonColors()
                } else {
                    androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = androidx.compose.ui.graphics.Color(0xFF3F3F3F),
                        contentColor = androidx.compose.ui.graphics.Color.White,
                    )
                },
            ) {
                Text(
                    text = when (state.phase) {
                        SpeedTestPhase.Idle, SpeedTestPhase.Done -> stringResource(R.string.speed_start_test)
                        else -> stringResource(R.string.speed_stop)
                    },
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    modifier = Modifier.padding(vertical = 4.dp),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SpeedRouteBadge(
    viaVpn: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (viaVpn) {
        VpnGreen.copy(alpha = 0.16f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (viaVpn) {
        VpnGreen
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val borderColor = if (viaVpn) {
        VpnGreen.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = modifier
            .height(28.dp)
            .widthIn(min = 76.dp, max = 120.dp),
        shape = RoundedCornerShape(percent = 50),
        color = backgroundColor,
        contentColor = contentColor,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(
                    if (viaVpn) R.string.speed_via_vpn else R.string.speed_direct,
                ),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SpeedGauge(
    speed: Double,
    phase: SpeedTestPhase,
    modifier: Modifier = Modifier,
) {
    // Max scale: 100 Mbps
    val maxSpeed = 100f
    val fraction = (speed.toFloat() / maxSpeed).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(300),
        label = "gauge",
    )

    val arcColor = when {
        speed < 10 -> VpnRed
        speed < 30 -> VpnOrange
        speed < 60 -> VpnGreen
        else -> VpnBlue
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 16.dp.toPx()
            val padding = strokeWidth / 2 + 8.dp.toPx()
            val arcSize = Size(size.width - padding * 2, size.height - padding * 2)
            val topLeft = Offset(padding, padding)

            // Arc spans 240 degrees: from 150° to 390° (30° gap at bottom)
            val startAngle = 150f
            val totalSweep = 240f

            // Track
            drawArc(
                color = trackColor,
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )

            // Value arc
            if (animatedFraction > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = totalSweep * animatedFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }

            // Tick marks
            val center = Offset(size.width / 2, size.height / 2)
            val radius = arcSize.width / 2
            val tickCount = 10
            for (i in 0..tickCount) {
                val angle = Math.toRadians((startAngle + totalSweep * i / tickCount).toDouble())
                val innerR = radius - strokeWidth / 2 - 6.dp.toPx()
                val outerR = radius - strokeWidth / 2 - 2.dp.toPx()
                drawLine(
                    color = trackColor,
                    start = Offset(
                        center.x + innerR * cos(angle).toFloat(),
                        center.y + innerR * sin(angle).toFloat(),
                    ),
                    end = Offset(
                        center.x + outerR * cos(angle).toFloat(),
                        center.y + outerR * sin(angle).toFloat(),
                    ),
                    strokeWidth = 2.dp.toPx(),
                )
            }

            // Needle
            if (phase != SpeedTestPhase.Idle) {
                val needleAngle = Math.toRadians((startAngle + totalSweep * animatedFraction).toDouble())
                val needleLength = radius - strokeWidth - 16.dp.toPx()
                drawLine(
                    color = arcColor,
                    start = center,
                    end = Offset(
                        center.x + needleLength * cos(needleAngle).toFloat(),
                        center.y + needleLength * sin(needleAngle).toFloat(),
                    ),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
                drawCircle(
                    color = arcColor,
                    radius = 6.dp.toPx(),
                    center = center,
                )
            }
        }

        // Center text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (phase == SpeedTestPhase.Idle) "0" else "%.1f".format(speed),
                style = fixedLayoutTextStyle(TextStyle(fontSize = 40.sp)),
                fontWeight = FontWeight.Bold,
                color = textColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.speed_unit_mbps),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResultCard(
    label: String,
    value: String,
    unit: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = fixedLayoutTextStyle(TextStyle(fontSize = 22.sp)),
                fontWeight = FontWeight.Bold,
                color = color,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = unit,
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
