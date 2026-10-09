package com.tobevpn.app.presentation.speedtest

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.VerticalScrollEdgeArrow
import com.tobevpn.app.presentation.components.endEdgeFade
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.startEdgeFade
import com.tobevpn.app.presentation.components.verticalFadingEdges
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.VpnRed
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestScreen(
    onBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
    viewModel: SpeedTestViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val viaVpn by viewModel.viaVpn.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()

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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SpeedGauge(
                        speed = state.currentSpeed,
                        phase = state.phase,
                        hasError = state.errorRes != null,
                        successful = state.phase == SpeedTestPhase.Done &&
                            state.errorRes == null && state.downloadSpeed > 0.0,
                        modifier = Modifier.size(248.dp),
                    )

                    AnimatedContent(
                        targetState = state.phase to state.errorRes,
                        transitionSpec = {
                            (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 }) togetherWith
                                (fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 3 })
                        },
                        label = "speed-phase",
                    ) { (phase, errorRes) ->
                        Text(
                            text = when {
                                errorRes != null -> stringResource(errorRes)
                                phase == SpeedTestPhase.Idle -> stringResource(R.string.speed_press_start)
                                phase == SpeedTestPhase.Checking -> stringResource(R.string.speed_checking_connection)
                                phase == SpeedTestPhase.Ping -> stringResource(R.string.speed_measuring_ping)
                                phase == SpeedTestPhase.Download -> stringResource(R.string.speed_downloading)
                                phase == SpeedTestPhase.Done -> stringResource(R.string.speed_done)
                                else -> ""
                            },
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                            fontWeight = if (phase == SpeedTestPhase.Done && errorRes == null) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (errorRes != null) VpnRed else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                        )
                    }

                    AnimatedVisibility(
                        visible = state.errorRes == null && (
                            state.phase == SpeedTestPhase.Ping ||
                                state.phase == SpeedTestPhase.Download ||
                                state.phase == SpeedTestPhase.Done
                            ),
                        enter = fadeIn(tween(250)) +
                            scaleIn(tween(250), initialScale = 0.94f) +
                            expandVertically(
                                animationSpec = tween(250, easing = FastOutSlowInEasing),
                                expandFrom = Alignment.Top,
                            ),
                        exit = fadeOut(tween(220)) +
                            scaleOut(tween(220), targetScale = 0.96f) +
                            shrinkVertically(
                                animationSpec = tween(260, easing = FastOutSlowInEasing),
                                shrinkTowards = Alignment.Top,
                            ),
                    ) {
                        MeasurementStages(
                            phase = state.phase,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, start = 24.dp, end = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ResultCard(
                            label = stringResource(R.string.speed_ping),
                            value = if (state.ping > 0) "${state.ping}" else "—",
                            unit = stringResource(R.string.speed_unit_ms),
                            color = pingResultColor(state.ping),
                            modifier = Modifier.weight(1f),
                        )
                        ResultCard(
                            label = stringResource(R.string.speed_download),
                            value = if (state.downloadSpeed > 0) "%.1f".format(state.downloadSpeed) else "—",
                            unit = stringResource(R.string.speed_unit_mbps),
                            color = if (state.downloadSpeed > 0) {
                                downloadResultColor(state.downloadSpeed)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onNavigateToHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.speed_history_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                if (history.isNotEmpty()) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Surface(
                        modifier = Modifier
                            .height(28.dp)
                            .widthIn(min = 32.dp),
                        shape = RoundedCornerShape(percent = 50),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = history.size.toString(),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }

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
                    .padding(top = 8.dp, bottom = 12.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedTestHistoryScreen(
    onBack: () -> Unit,
    viewModel: SpeedTestViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.speed_history_title),
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            HistoryHeader(
                count = history.size,
                modifier = Modifier.padding(top = 16.dp, start = 24.dp, end = 24.dp),
            )

            SpeedTestHistoryList(
                history = history,
                onDelete = viewModel::deleteHistoryEntry,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .weight(1f),
            )
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
    hasError: Boolean,
    successful: Boolean,
    modifier: Modifier = Modifier,
) {
    // The old 100 Mbps scale saturated far below modern Wi-Fi and wired tariffs.
    // 500 Mbps keeps a 300 Mbps result readable without clipping the needle.
    val maxSpeed = 500f
    val fraction = (speed.toFloat() / maxSpeed).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "gauge",
    )

    val infiniteTransition = rememberInfiniteTransition(label = "speed-gauge-running")
    val scanFraction by infiniteTransition.animateFloat(
        initialValue = 0.06f,
        targetValue = 0.94f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speed-gauge-scan",
    )
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speed-gauge-pulse",
    )
    val inactiveVisual = phase == SpeedTestPhase.Idle ||
        phase == SpeedTestPhase.Checking || hasError
    val gaugeScale by animateFloatAsState(
        targetValue = if (inactiveVisual) 0.95f else 1f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "speed-gauge-scale",
    )
    val completionBurst by animateFloatAsState(
        targetValue = if (successful) 1f else 0f,
        animationSpec = tween(720, easing = FastOutSlowInEasing),
        label = "speed-gauge-completion",
    )
    val isRunning = phase == SpeedTestPhase.Ping || phase == SpeedTestPhase.Download
    val visualFraction = if (phase == SpeedTestPhase.Ping) scanFraction else animatedFraction

    val arcColor = when {
        phase == SpeedTestPhase.Ping -> VpnBlue
        speed < 25 -> VpnRed
        speed < 75 -> VpnOrange
        speed < 150 -> VpnGreen
        else -> VpnBlue
    }
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier.graphicsLayer {
            scaleX = gaugeScale
            scaleY = gaugeScale
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 16.dp.toPx()
            val padding = strokeWidth / 2 + 8.dp.toPx()
            val arcSize = Size(size.width - padding * 2, size.height - padding * 2)
            val topLeft = Offset(padding, padding)

            // Arc spans 240 degrees: from 150° to 390° (30° gap at bottom)
            val startAngle = 150f
            val totalSweep = 240f
            val center = Offset(size.width / 2, size.height / 2)

            if (isRunning) {
                drawCircle(
                    color = VpnBlue.copy(alpha = 0.05f + pulse * 0.07f),
                    radius = size.minDimension * (0.43f + pulse * 0.035f),
                    center = center,
                )
            }

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
            if (visualFraction > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = totalSweep * visualFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            }

            // Tick marks
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
            if (!inactiveVisual) {
                val needleAngle = Math.toRadians((startAngle + totalSweep * visualFraction).toDouble())
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

            if (completionBurst in 0.001f..0.999f) {
                val particleRadius = radius * (0.82f + completionBurst * 0.22f)
                repeat(12) { index ->
                    val angle = Math.toRadians((index * 30.0) - 90.0)
                    drawCircle(
                        color = VpnGreen.copy(alpha = (1f - completionBurst) * 0.75f),
                        radius = (2.8f - completionBurst * 1.2f).dp.toPx(),
                        center = Offset(
                            center.x + particleRadius * cos(angle).toFloat(),
                            center.y + particleRadius * sin(angle).toFloat(),
                        ),
                    )
                }
            }
        }

        // Center text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (inactiveVisual) "0" else "%.1f".format(speed),
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

        AnimatedVisibility(
            visible = successful,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 20.dp, end = 20.dp),
            enter = fadeIn(tween(220, delayMillis = 180)) +
                scaleIn(tween(360, delayMillis = 180), initialScale = 0.35f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.7f),
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(percent = 50),
                color = VpnGreen,
                contentColor = Color.White,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MeasurementStages(
    phase: SpeedTestPhase,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MeasurementStageChip(
            label = stringResource(R.string.speed_ping),
            active = phase == SpeedTestPhase.Ping,
            completed = phase == SpeedTestPhase.Download || phase == SpeedTestPhase.Done,
            modifier = Modifier.weight(1f),
        )
        MeasurementStageChip(
            label = stringResource(R.string.speed_download),
            active = phase == SpeedTestPhase.Download,
            completed = phase == SpeedTestPhase.Done,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MeasurementStageChip(
    label: String,
    active: Boolean,
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
    val pulseTransition = rememberInfiniteTransition(label = "speed-stage-$label")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "speed-stage-pulse-$label",
    )
    val accent = when {
        completed -> VpnGreen
        active -> VpnBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = when {
        completed -> VpnGreen.copy(alpha = 0.14f)
        active -> VpnBlue.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }

    Surface(
        modifier = modifier.height(38.dp),
        shape = RoundedCornerShape(percent = 50),
        color = background,
        contentColor = accent,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (completed) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
            } else {
                Canvas(modifier = Modifier.size(12.dp)) {
                    drawCircle(
                        color = accent.copy(alpha = if (active) pulse else 0.45f),
                        radius = size.minDimension * if (active) 0.4f * pulse else 0.28f,
                    )
                }
            }
            Spacer(modifier = Modifier.size(7.dp))
            Text(
                text = label,
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                fontWeight = if (active || completed) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HistoryHeader(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(13.dp),
            color = VpnBlue.copy(alpha = 0.14f),
            contentColor = VpnBlue,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.speed_history_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.speed_history_local_hint),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (count > 0) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Text(
                    text = count.toString(),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SpeedTestHistoryList(
    history: List<SpeedTestHistoryEntry>,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) {
        Box(modifier = modifier) {
            EmptyHistoryCard(
                modifier = Modifier.padding(top = 12.dp, start = 24.dp, end = 24.dp),
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val topFadeAlpha = listState.startEdgeFade()
    val bottomFadeAlpha = listState.endEdgeFade()

    Box(modifier = modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .verticalFadingEdges(
                    topAlpha = topFadeAlpha,
                    bottomAlpha = bottomFadeAlpha,
                    fadeHeight = 38.dp,
                ),
            contentPadding = PaddingValues(
                start = 24.dp,
                top = 16.dp,
                end = 24.dp,
                bottom = 16.dp,
            ),
        ) {
            itemsIndexed(
                items = history,
                key = { _, entry -> entry.timestampMillis },
            ) { index, entry ->
                SwipeableHistoryEntry(
                    entry = entry,
                    isLatest = index == 0,
                    onDelete = { onDelete(entry.timestampMillis) },
                    modifier = Modifier
                        .animateItem()
                        .padding(bottom = 8.dp),
                )
            }
        }

        VerticalScrollEdgeArrow(
            alpha = topFadeAlpha,
            isTop = true,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 1.dp),
        )
        VerticalScrollEdgeArrow(
            alpha = bottomFadeAlpha,
            isTop = false,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 1.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableHistoryEntry(
    entry: SpeedTestHistoryEntry,
    isLatest: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * 0.35f },
    )
    val density = LocalDensity.current
    val hapticFeedback = LocalHapticFeedback.current
    val offsetPx = runCatching { dismissState.requireOffset() }
        .getOrDefault(0f)
        .takeIf(Float::isFinite)
        ?: 0f
    val revealedWidthPx = (-offsetPx).coerceAtLeast(0f)
    val deleteRevealDistancePx = with(density) { 64.dp.toPx() }
    val fadeRevealDistancePx = with(density) { 112.dp.toPx() }
    val fadeProgress = (revealedWidthPx / fadeRevealDistancePx).coerceIn(0f, 1f)
    val startFadeAlpha by animateFloatAsState(
        targetValue = fadeProgress,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "speed-history-start-fade",
    )
    val revealProgress = (revealedWidthPx / deleteRevealDistancePx).coerceIn(0f, 1f)
    val deleteActionReady = revealProgress >= 0.999f
    val deleteReveal = FastOutSlowInEasing.transform(revealProgress)
    val deleteIconHalfSizePx = with(density) { 14.dp.toPx() }
    val deleteIconTranslationX = deleteIconHalfSizePx - revealedWidthPx / 2f
    LaunchedEffect(deleteActionReady) {
        if (deleteActionReady) {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        }
    }
    LaunchedEffect(dismissState.settledValue) {
        if (dismissState.settledValue == SwipeToDismissBoxValue.EndToStart) {
            onDelete()
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .swipeStartFadingEdge(
                alpha = startFadeAlpha,
                fadeWidth = 64.dp,
            ),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.fillMaxWidth(),
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true,
            backgroundContent = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 1.dp)
                        .clip(shape)
                        .background(VpnRed),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(R.string.speed_history_delete),
                        tint = Color.White,
                        modifier = Modifier
                            .size(28.dp)
                            .graphicsLayer {
                                alpha = deleteReveal
                                scaleX = 0.55f + deleteReveal * 0.45f
                                scaleY = 0.55f + deleteReveal * 0.45f
                                translationX = deleteIconTranslationX
                                rotationZ = 12f * (1f - deleteReveal)
                            },
                    )
                }
            },
            content = {
                HistoryEntryCard(
                    entry = entry,
                    isLatest = isLatest,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )

    }
}

/** Makes a swiped row dissolve at the list boundary instead of being cut abruptly. */
private fun Modifier.swipeStartFadingEdge(
    alpha: Float,
    fadeWidth: Dp,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()

        val edgeAlpha = alpha.coerceIn(0f, 1f)
        val fadeWidthPx = fadeWidth.toPx().coerceAtMost(size.width)
        if (edgeAlpha <= 0.001f || fadeWidthPx <= 0f) return@drawWithContent

        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 1f - edgeAlpha),
                    Color.Black,
                ),
                startX = 0f,
                endX = fadeWidthPx,
            ),
            topLeft = Offset.Zero,
            size = Size(fadeWidthPx, size.height),
            blendMode = BlendMode.DstIn,
        )
    }

@Composable
private fun EmptyHistoryCard(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Text(
            text = stringResource(R.string.speed_history_empty),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun HistoryEntryCard(
    entry: SpeedTestHistoryEntry,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val dateText = remember(entry.timestampMillis, locale) {
        SimpleDateFormat("dd MMM · HH:mm", locale).format(Date(entry.timestampMillis))
    }
    val routeColor = if (entry.viaVpn) VpnGreen else VpnBlue
    val routeLabel = stringResource(if (entry.viaVpn) R.string.speed_via_vpn else R.string.speed_direct)
    val baseCardColor = MaterialTheme.colorScheme.surfaceContainerLow

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLatest) {
                routeColor.copy(alpha = 0.10f).compositeOver(baseCardColor)
            } else {
                baseCardColor
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(14.dp),
                color = routeColor.copy(alpha = 0.16f),
                contentColor = routeColor,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (entry.viaVpn) Icons.Rounded.Lock else Icons.Rounded.Public,
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = String.format(locale, "%.1f", entry.downloadMbps),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        color = downloadResultColor(entry.downloadMbps),
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.size(5.dp))
                    Text(
                        text = stringResource(R.string.speed_unit_mbps),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                        maxLines = 1,
                    )
                }
                Text(
                    text = "$dateText · $routeLabel",
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = pingResultColor(entry.pingMs),
                    )
                    Spacer(modifier = Modifier.size(3.dp))
                    Text(
                        text = entry.pingMs.toString(),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.Bold,
                        color = pingResultColor(entry.pingMs),
                    )
                }
                Text(
                    text = stringResource(R.string.speed_unit_ms),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun downloadResultColor(speed: Double): Color = when {
    speed < 25.0 -> VpnRed
    speed < 75.0 -> VpnOrange
    speed < 150.0 -> VpnGreen
    else -> VpnBlue
}

@Composable
private fun pingResultColor(ping: Long): Color = when (ping) {
    in 1..100 -> VpnGreen
    in 101..200 -> VpnOrange
    in 201..Long.MAX_VALUE -> VpnRed
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
        modifier = modifier.animateContentSize(),
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
            AnimatedContent(
                targetState = value,
                transitionSpec = {
                    (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.86f)) togetherWith
                        fadeOut(tween(120))
                },
                label = "speed-result-$label",
            ) { animatedValue ->
                Text(
                    text = animatedValue,
                    style = fixedLayoutTextStyle(TextStyle(fontSize = 22.sp)),
                    fontWeight = FontWeight.Bold,
                    color = color,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
