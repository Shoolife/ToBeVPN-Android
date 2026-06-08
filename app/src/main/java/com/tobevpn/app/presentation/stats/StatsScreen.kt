package com.tobevpn.app.presentation.stats

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.local.dao.TrafficStat
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onBack: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val period by viewModel.period.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val totalBytes by viewModel.totalBytes.collectAsStateWithLifecycle()

    val totalSessions = remember(stats) { stats.sumOf { it.sessions } }
    val totalSeconds = remember(stats) { stats.sumOf { it.totalSeconds } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.stats_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize(),
            ) {
                Spacer(Modifier.height(8.dp))
                HeroStatsCard(
                    totalBytes = totalBytes,
                    totalSessions = totalSessions,
                    totalSeconds = totalSeconds,
                )

                Spacer(Modifier.height(12.dp))

                // Period selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatsPeriod.entries.forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { viewModel.setPeriod(p) },
                            label = {
                                Text(
                                    when (p) {
                                        StatsPeriod.DAY -> stringResource(R.string.stats_period_day)
                                        StatsPeriod.WEEK -> stringResource(R.string.stats_period_week)
                                        StatsPeriod.MONTH -> stringResource(R.string.stats_period_month)
                                    }
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = VpnBlue.copy(alpha = 0.25f),
                                selectedLabelColor = VpnBlue,
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (stats.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.stats_no_data),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    // Animated chart card
                    EnhancedTrafficChart(
                        stats = stats,
                        period = period,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    // Period label
                    Text(
                        text = when (period) {
                            StatsPeriod.DAY -> stringResource(R.string.stats_today_by_hour)
                            StatsPeriod.WEEK -> stringResource(R.string.stats_week_by_day)
                            StatsPeriod.MONTH -> stringResource(R.string.stats_month_by_week)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    )

                    val maxRowBytes = remember(stats) {
                        stats.maxOf { it.totalBytes }.coerceAtLeast(1L)
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                    ) {
                        items(stats) { stat ->
                            EnhancedStatRow(stat = stat, period = period, maxBytes = maxRowBytes)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStatsCard(
    totalBytes: Long,
    totalSessions: Int,
    totalSeconds: Long,
) {
    val isDarkTheme = isSystemInDarkTheme()
    val primaryContentColor = if (isDarkTheme) {
        Color.White
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryContentColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tertiaryContentColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
    }
    val dividerColor = if (isDarkTheme) {
        Color.White.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            VpnBlue.copy(alpha = 0.35f),
                            VpnGreen.copy(alpha = 0.18f),
                        ),
                    )
                )
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(VpnBlue.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ShowChart,
                            contentDescription = null,
                            tint = secondaryContentColor,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            stringResource(R.string.stats_total_used),
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryContentColor,
                        )
                        Text(
                            text = stringResource(R.string.stats_context_device),
                            style = MaterialTheme.typography.labelSmall,
                            color = tertiaryContentColor,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = formatBytes(totalBytes),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = primaryContentColor,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    HeroMetric(
                        icon = Icons.Default.BarChart,
                        label = stringResource(R.string.stats_metric_sessions),
                        value = totalSessions.toString(),
                        primaryContentColor = primaryContentColor,
                        secondaryContentColor = secondaryContentColor,
                    )
                    HeroDivider(color = dividerColor)
                    HeroMetric(
                        icon = Icons.Default.Schedule,
                        label = stringResource(R.string.stats_metric_time),
                        value = formatTime(totalSeconds),
                        primaryContentColor = primaryContentColor,
                        secondaryContentColor = secondaryContentColor,
                    )
                    HeroDivider(color = dividerColor)
                    HeroMetric(
                        icon = Icons.AutoMirrored.Filled.ShowChart,
                        label = stringResource(R.string.stats_metric_avg),
                        value = formatBytes(
                            if (totalSessions > 0) totalBytes / totalSessions else 0
                        ),
                        primaryContentColor = primaryContentColor,
                        secondaryContentColor = secondaryContentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMetric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    primaryContentColor: Color,
    secondaryContentColor: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = secondaryContentColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = primaryContentColor,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = secondaryContentColor,
        )
    }
}

@Composable
private fun HeroDivider(color: Color) {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(36.dp)
            .background(color),
    )
}

@Composable
private fun EnhancedTrafficChart(
    stats: List<TrafficStat>,
    period: StatsPeriod,
    modifier: Modifier = Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelColorArgb = labelColor.hashCode()
    val scaleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val scaleColorArgb = scaleColor.hashCode()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val weekPrefixFormat = stringResource(R.string.stats_week_short)

    // Build full slots (fill gaps with zero)
    val slots = buildSlots(stats, period)
    val maxBytes = slots.maxOfOrNull { it.totalBytes }?.coerceAtLeast(1L) ?: 1L
    val maxIndex = slots.indexOfFirst { it.totalBytes == maxBytes && maxBytes > 0 }

    // Grow-in animation — re-runs when data or period changes
    val animProgress = remember(stats, period) { Animatable(0f) }
    LaunchedEffect(stats, period) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        )
    }
    val progress = animProgress.value

    val density = LocalDensity.current
    val yLabelWidthPx = with(density) { 36.dp.toPx() }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 16.dp, start = 12.dp, end = 16.dp, bottom = 28.dp),
            ) {
                if (slots.isEmpty()) return@Canvas

                val chartLeft = yLabelWidthPx
                val chartWidth = size.width - chartLeft
                val chartHeight = size.height
                val usableHeight = chartHeight * 0.88f

                // Gridlines + Y-axis labels (0, 50%, 100%)
                val gridValues = listOf(0f, 0.5f, 1f)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
                gridValues.forEach { frac ->
                    val y = chartHeight - usableHeight * frac
                    drawLine(
                        color = gridColor,
                        start = Offset(chartLeft, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = if (frac == 0f) null else dashEffect,
                    )
                    // Y-axis label
                    val bytes = (maxBytes * frac).toLong()
                    val labelText = shortBytes(bytes)
                    drawScaleLabel(labelText, chartLeft - 6.dp.toPx(), y + 4.dp.toPx(), scaleColorArgb)
                }

                // Bars
                val barCount = slots.size
                val totalSpacing = chartWidth * 0.22f
                val spacing = totalSpacing / (barCount + 1)
                val barWidth = (chartWidth - totalSpacing) / barCount
                val corner = CornerRadius(4.dp.toPx())

                slots.forEachIndexed { index, slot ->
                    val frac = slot.totalBytes.toFloat() / maxBytes
                    val barHeight = frac * usableHeight * progress
                    val x = chartLeft + spacing + index * (barWidth + spacing)

                    if (barHeight > 0.5f) {
                        val isMax = index == maxIndex
                        val topColor = if (isMax) VpnGreen else VpnBlue
                        val bottomColor = if (isMax) {
                            VpnGreen.copy(alpha = 0.2f)
                        } else {
                            VpnBlue.copy(alpha = 0.18f)
                        }
                        val barTop = chartHeight - barHeight

                        // Glow below the bar
                        drawRoundRect(
                            color = topColor.copy(alpha = 0.12f),
                            topLeft = Offset(x - 2.dp.toPx(), barTop - 2.dp.toPx()),
                            size = Size(barWidth + 4.dp.toPx(), barHeight + 2.dp.toPx()),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                        )

                        // Main bar with vertical gradient
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(topColor, bottomColor),
                                startY = barTop,
                                endY = chartHeight,
                            ),
                            topLeft = Offset(x, barTop),
                            size = Size(barWidth, barHeight),
                            cornerRadius = corner,
                        )

                        // Highlight stroke on max bar
                        if (isMax && progress > 0.95f) {
                            drawRoundRect(
                                color = VpnGreen,
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barHeight),
                                cornerRadius = corner,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }

                    // X-axis label
                    val label = slotLabel(slot, period, index, barCount, weekPrefixFormat)
                    if (label.isNotEmpty()) {
                        drawLabel(
                            label,
                            x + barWidth / 2,
                            chartHeight + 16.dp.toPx(),
                            labelColorArgb,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawScaleLabel(text: String, x: Float, y: Float, colorArgb: Int) {
    val paint = android.graphics.Paint().apply {
        color = colorArgb
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.RIGHT
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun shortBytes(bytes: Long): String {
    return when {
        bytes <= 0 -> "0"
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}K"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}M"
        else -> "%.1fG".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun DrawScope.drawLabel(text: String, x: Float, y: Float, colorArgb: Int) {
    val paint = android.graphics.Paint().apply {
        color = colorArgb
        textSize = 9.dp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
}

private fun buildSlots(stats: List<TrafficStat>, period: StatsPeriod): List<TrafficStat> {
    val cal = Calendar.getInstance(TimeZone.getDefault())

    return when (period) {
        StatsPeriod.DAY -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val dayStart = cal.timeInMillis / 1000
            val statsMap = stats.associateBy { it.period }
            (0 until 24).map { hour ->
                val slotTime = dayStart + hour * 3600L
                statsMap[slotTime] ?: TrafficStat(slotTime, 0, 0, 0)
            }
        }
        StatsPeriod.WEEK -> {
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            val weekStart = cal.timeInMillis / 1000
            val statsMap = stats.associateBy { it.period }
            (0 until 7).map { day ->
                val slotTime = weekStart + day * 86400L
                statsMap[slotTime] ?: TrafficStat(slotTime, 0, 0, 0)
            }
        }
        StatsPeriod.MONTH -> {
            if (stats.isEmpty()) {
                emptyList()
            } else {
                stats
            }
        }
    }
}

private fun slotLabel(
    slot: TrafficStat,
    period: StatsPeriod,
    index: Int,
    total: Int,
    weekPrefixFormat: String,
): String {
    return when (period) {
        StatsPeriod.DAY -> {
            // Show every 3rd hour
            val hour = ((slot.period % 86400) / 3600).toInt()
            if (hour % 3 == 0) "${hour}:00" else ""
        }
        StatsPeriod.WEEK -> {
            val sdf = SimpleDateFormat("EE", Locale.getDefault())
            sdf.timeZone = TimeZone.getDefault()
            sdf.format(Date(slot.period * 1000)).replaceFirstChar { it.uppercase() }
        }
        StatsPeriod.MONTH -> {
            weekPrefixFormat.format(index + 1)
        }
    }
}

@Composable
private fun EnhancedStatRow(
    stat: TrafficStat,
    period: StatsPeriod,
    maxBytes: Long,
) {
    val fraction = (stat.totalBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = formatPeriodLabel(stat.period, period),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.stats_sessions_short, stat.sessions) +
                            "  •  " + formatTime(stat.totalSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = formatBytes(stat.totalBytes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = VpnGreen,
                    textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(10.dp))
            // Mini progress bar — fraction of max period's bytes
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(VpnBlue, VpnGreen),
                                )
                            ),
                    )
                }
            }
        }
    }
}

private fun formatPeriodLabel(epochSeconds: Long, period: StatsPeriod): String {
    val locale = Locale.getDefault()
    val sdf = when (period) {
        StatsPeriod.DAY -> SimpleDateFormat("HH:00", locale)
        StatsPeriod.WEEK -> SimpleDateFormat("EEEE, dd MMM", locale)
        StatsPeriod.MONTH -> SimpleDateFormat("dd MMM", locale)
    }
    sdf.timeZone = TimeZone.getDefault()
    val label = sdf.format(Date(epochSeconds * 1000))

    return when (period) {
        StatsPeriod.WEEK -> label.replaceFirstChar { it.uppercase() }
        StatsPeriod.MONTH -> {
            val endDate = SimpleDateFormat("dd MMM", locale).apply {
                timeZone = TimeZone.getDefault()
            }.format(Date((epochSeconds + 6 * 86400) * 1000))
            "$label — $endDate"
        }
        else -> label
    }
}

private fun formatBytes(bytes: Long): String {
    val isRu = Locale.getDefault().language == "ru"
    val kb = if (isRu) "КБ" else "KB"
    val mb = if (isRu) "МБ" else "MB"
    val gb = if (isRu) "ГБ" else "GB"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f %s".format(bytes / 1024.0, kb)
        bytes < 1024 * 1024 * 1024 -> "%.1f %s".format(bytes / (1024.0 * 1024.0), mb)
        else -> "%.2f %s".format(bytes / (1024.0 * 1024.0 * 1024.0), gb)
    }
}

private fun formatTime(seconds: Long): String {
    val isRu = Locale.getDefault().language == "ru"
    val hUnit = if (isRu) "ч" else "h"
    val mUnit = if (isRu) "м" else "m"
    val sUnit = if (isRu) "с" else "s"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return when {
        h > 0 -> "${h}${hUnit} ${m}${mUnit}"
        m > 0 -> "${m}${mUnit}"
        else -> "${seconds}${sUnit}"
    }
}
