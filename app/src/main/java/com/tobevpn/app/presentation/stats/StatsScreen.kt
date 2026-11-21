package com.tobevpn.app.presentation.stats

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.BorderStroke
import com.tobevpn.app.presentation.components.FadingText
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.local.dao.TrafficStat
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.lazyScrollCues
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.rememberAppWindowAdaptiveLayout
import com.tobevpn.app.presentation.theme.responsiveMaxWidth
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
    val pageMaxWidth = responsiveMaxWidth(560.dp)
    // Charts and rows read better across the whole window. Include unfolded
    // foldables here: Pixel Fold reports a 701dp smallest side, just below the
    // regular 720dp tablet breakpoint, despite exposing a wide inner display.
    val useLargeTabletLayout = rememberAppWindowAdaptiveLayout().useFullWidthContent
    val period by viewModel.period.collectAsStateWithLifecycle()
    val serverSource by viewModel.serverSource.collectAsStateWithLifecycle()
    // The period and servers the rows were read for: the chips follow a tap
    // at once, everything below changes once its rows are there.
    val view by viewModel.view.collectAsStateWithLifecycle()
    val stats = view?.stats.orEmpty()
    val shownPeriod = view?.period ?: period
    val totalBytes by viewModel.totalBytes.collectAsStateWithLifecycle()

    val totalSessions = remember(stats) { stats.sumOf { it.sessions } }
    val periodBytes = remember(stats) { stats.sumOf { it.totalBytes } }
    val totalSeconds = remember(stats) { stats.sumOf { it.totalSeconds } }
    // As on PC: the router's traffic (the whole home network) next to this
    // phone's, in the chart and the rows; only with "all servers", since the
    // router's traffic has no server type of the phone's.
    val routerHours by viewModel.routerHours.collectAsStateWithLifecycle()
    val withRouter = viewModel.routerLinked && (view?.source ?: serverSource) == StatsServerSource.ALL
    // A month with the router: its weeks, this phone's weekly rows put in
    // them, so weeks with only router traffic have their row too.
    val slots = remember(stats, shownPeriod, withRouter) {
        if (shownPeriod == StatsPeriod.MONTH && withRouter) monthWeeks(stats) else buildSlots(stats, shownPeriod)
    }
    val routerAt: (Long) -> Long = remember(routerHours, shownPeriod, withRouter) {
        { start ->
            if (!withRouter) {
                0L
            } else {
                val end = start + slotSeconds(shownPeriod)
                routerHours.filter { it.hour >= start && it.hour < end }.sumOf { it.bytes }
            }
        }
    }
    val routerTotal = remember(slots, routerAt) { slots.sumOf { routerAt(it.period) } }
    val rows = remember(slots, routerAt) { slots.filter { it.totalBytes + routerAt(it.period) > 0 } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.stats_title),
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
                modifier = if (useLargeTabletLayout) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .widthIn(max = pageMaxWidth)
                        .fillMaxSize()
                },
            ) {
                Spacer(Modifier.height(8.dp))
                HeroStatsCard(
                    totalBytes = totalBytes,
                    totalSessions = totalSessions,
                    totalSeconds = totalSeconds,
                    routerBytes = if (withRouter) routerTotal else null,
                    // Fades follow the period and servers, not each minute of traffic.
                    fadeKey = shownPeriod to (view?.source ?: serverSource),
                    // The total is for the servers chosen, whatever the period.
                    // (keyed as its own query is: by the chosen servers).
                    totalFadeKey = serverSource,
                    periodBytes = periodBytes,
                )

                Spacer(Modifier.height(12.dp))

                StatsServerSourceSelector(
                    selected = serverSource,
                    onSelected = viewModel::setServerSource,
                )

                Spacer(Modifier.height(8.dp))

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
                                    text = when (p) {
                                        StatsPeriod.DAY -> stringResource(R.string.stats_period_day)
                                        StatsPeriod.WEEK -> stringResource(R.string.stats_period_week)
                                        StatsPeriod.MONTH -> stringResource(R.string.stats_period_month)
                                    },
                                    style = fixedLayoutTextStyle(
                                        MaterialTheme.typography.labelLarge,
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
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

                // Another period or servers: the chart and rows cross-fade as
                // the Router screen's content does (the old leaves quickly, the
                // new comes in evenly), each with the rows read for it. New
                // traffic within the same period only updates them.
                AnimatedContent(
                    // Keyed as the rows will be once read: the first rows appear
                    // in place, with no "no data" moment fading out before them.
                    targetState = StatsSection(shownPeriod, view?.source ?: serverSource, slots, rows, routerAt, loaded = view != null),
                    contentKey = { it.period to it.source },
                    transitionSpec = {
                        fadeIn(tween(420, delayMillis = 120, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(tween(220, easing = FastOutSlowInEasing))
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    label = "stats-period",
                ) { section ->
                Column(Modifier.fillMaxSize()) {
                if (!section.loaded) {
                    Spacer(Modifier.fillMaxWidth().weight(1f))
                } else if (section.rows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.stats_no_data),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    val statsListState = rememberLazyListState()
                    val listScope = rememberCoroutineScope()
                    // The bar tapped last: its row is scrolled to and lit up.
                    var selectedPeriod by remember(section.period, section.source) { mutableStateOf<Long?>(null) }
                    // Raised when the list has arrived at the row: its outline plays then.
                    // The picked row's outline: it fades in once the list has
                    // arrived, pulses twice and stays (the bar is simply outlined).
                    val outline = remember(section.period, section.source) { Animatable(0f) }
                    // How far the rows are pulled past the list's ends, so a row
                    // near the top or bottom can still stand in the middle.
                    val pull = remember(section.period, section.source) { mutableFloatStateOf(0f) }
                    var gliding by remember { mutableStateOf(false) }
                    // Scrolling by hand lets the rows settle back.
                    LaunchedEffect(statsListState) {
                        snapshotFlow { statsListState.isScrollInProgress }.collect { scrolling ->
                            if (scrolling && !gliding && pull.floatValue != 0f) {
                                androidx.compose.animation.core.animate(pull.floatValue, 0f, animationSpec = tween(300)) { value, _ ->
                                    pull.floatValue = value
                                }
                            }
                        }
                    }
                    // A bar or a row picked: its row comes to the middle (the last
                    // one only as far as the list scrolls), lit up with its bar.
                    // The row's outline pulses only when the pick came from its bar
                    // (to show where it went); a tapped row is simply outlined.
                    val pickRow: (Long, Boolean) -> Unit = { tapped, fromBar ->
                        val index = section.rows.indexOfFirst { it.period == tapped }
                        if (index >= 0) {
                            selectedPeriod = tapped
                            listScope.launch {
                                outline.snapTo(0f)
                                gliding = true
                                try {
                                    statsListState.glideToCenter(index, pull, pullPastEnd = index != section.rows.lastIndex)
                                } finally {
                                    gliding = false
                                }
                                // The row's outline fades in; from a bar it also pulses twice.
                                outline.animateTo(1f, tween(260, easing = FastOutSlowInEasing))
                                if (fromBar) {
                                    repeat(2) {
                                        outline.animateTo(0.35f, tween(320, easing = FastOutSlowInEasing))
                                        outline.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
                                    }
                                }
                            }
                        }
                    }
                    // Animated chart card
                    EnhancedTrafficChart(
                        stats = section.slots,
                        period = section.period,
                        routerAt = section.routerAt,
                        selectedPeriod = selectedPeriod,
                        onBarClick = { tapped -> pickRow(tapped, true) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    // Period label
                    Text(
                        text = when (section.period) {
                            StatsPeriod.DAY -> stringResource(R.string.stats_today_by_hour)
                            StatsPeriod.WEEK -> stringResource(R.string.stats_week_by_day)
                            StatsPeriod.MONTH -> stringResource(R.string.stats_month_by_week)
                        },
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )

                    val maxRowBytes = remember(section) {
                        section.rows.maxOfOrNull { it.totalBytes + section.routerAt(it.period) }?.coerceAtLeast(1L) ?: 1L
                    }
                    Box(Modifier.weight(1f).clipToBounds().lazyScrollCues(statsListState)) {
                    LazyColumn(
                        state = statsListState,
                        modifier = Modifier.fillMaxSize().graphicsLayer { translationY = pull.floatValue },
                    ) {
                        items(section.rows) { stat ->
                            EnhancedStatRow(
                                stat = stat,
                                period = section.period,
                                maxBytes = maxRowBytes,
                                routerBytes = section.routerAt(stat.period),
                                highlighted = stat.period == selectedPeriod,
                                outline = if (stat.period == selectedPeriod) outline.value else 0f,
                                onClick = { pickRow(stat.period, false) },
                            )
                        }
                    }
                    }
                }
                }
                }
            }
        }
    }
}

@Composable
private fun StatsServerSourceSelector(
    selected: StatsServerSource,
    onSelected: (StatsServerSource) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatsServerSource.entries.forEach { source ->
            val accent = if (source == StatsServerSource.BASE_STATION_BYPASS) {
                VpnGreen
            } else {
                VpnBlue
            }
            FilterChip(
                selected = selected == source,
                onClick = { onSelected(source) },
                modifier = Modifier.weight(1f),
                label = {
                    Text(
                        text = when (source) {
                            StatsServerSource.ALL -> stringResource(R.string.stats_source_all)
                            StatsServerSource.STANDARD -> stringResource(
                                R.string.stats_source_standard,
                            )
                            StatsServerSource.BASE_STATION_BYPASS -> stringResource(
                                R.string.stats_source_base_station_bypass,
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = accent.copy(alpha = 0.25f),
                    selectedLabelColor = accent,
                ),
            )
        }
    }
}

@Composable
private fun HeroStatsCard(
    totalBytes: Long,
    totalSessions: Int,
    totalSeconds: Long,
    /** The router's traffic in the chosen period; null without a router. */
    routerBytes: Long? = null,
    fadeKey: Any? = null,
    totalFadeKey: Any? = null,
    /** This device's traffic in the chosen period, for the average per session. */
    periodBytes: Long,
) {
    // Another period or servers: each number fades over to its new value
    // with the chart's fade (FadingText), never counted or jumped.
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
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                            color = secondaryContentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.stats_context_device),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
                            color = tertiaryContentColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                FadingText(
                    text = formatBytes(totalBytes),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.displaySmall),
                    fontWeight = FontWeight.Bold,
                    color = primaryContentColor,
                    fadeKey = totalFadeKey,
                )
                if (routerBytes != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(RouterTrafficColor))
                        Spacer(Modifier.width(6.dp))
                        FadingText(
                            text = stringResource(R.string.stats_router_period, formatBytes(routerBytes)),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            color = secondaryContentColor,
                            fadeKey = fadeKey,
                        )
                    }
                }
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
                        fadeKey = fadeKey,
                    )
                    HeroDivider(color = dividerColor)
                    HeroMetric(
                        icon = Icons.Default.Schedule,
                        label = stringResource(R.string.stats_metric_time),
                        value = formatTime(totalSeconds),
                        primaryContentColor = primaryContentColor,
                        secondaryContentColor = secondaryContentColor,
                        fadeKey = fadeKey,
                    )
                    HeroDivider(color = dividerColor)
                    HeroMetric(
                        icon = Icons.AutoMirrored.Filled.ShowChart,
                        label = stringResource(R.string.stats_metric_avg),
                        value = formatBytes(
                            if (totalSessions > 0) periodBytes / totalSessions else 0
                        ),
                        primaryContentColor = primaryContentColor,
                        secondaryContentColor = secondaryContentColor,
                        fadeKey = fadeKey,
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
    fadeKey: Any? = null,
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
        FadingText(
            text = value,
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
            fontWeight = FontWeight.SemiBold,
            color = primaryContentColor,
            fadeKey = fadeKey,
        )
        Text(
            text = label,
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
            color = secondaryContentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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

/** Ease in-out (cubic): starts gently, speeds up, and slows down as gently. */
private val GlideEasing = androidx.compose.animation.core.CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/**
 * Moves row [index] to the middle of the list, speeding up and slowing down
 * smoothly (ease in-out), however far it is. The list scrolls as far as it
 * can; the rest — a row near the top or bottom — is [pull], a shift of the
 * rows past the list's end, in the same motion. Rows are the same height, so
 * one out of sight is placed from the visible rows' size.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.glideToCenter(
    index: Int,
    pull: androidx.compose.runtime.MutableFloatState,
    /** False for the last row: it stops where the list ends, no room below it. */
    pullPastEnd: Boolean = true,
) {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return
    val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val target = visible.firstOrNull { it.index == index }
    // How far up the row has to move to stand in the middle (pull included).
    val delta = pull.floatValue + if (target != null) {
        target.offset + target.size / 2f - viewportCenter
    } else {
        val step = visible.map { it.size }.average().toFloat() + info.mainAxisItemSpacing
        val first = visible.first()
        first.offset + (index - first.index) * step + first.size / 2f - viewportCenter
    }
    if (kotlin.math.abs(delta) < 1f) return
    // Longer trips take a little longer, never abruptly fast or dragging.
    val duration = (450 + kotlin.math.abs(delta) / 3f).toInt().coerceIn(500, 1000)
    scroll {
        var done = 0f
        androidx.compose.animation.core.animate(0f, delta, animationSpec = tween(duration, easing = GlideEasing)) { value, _ ->
            var step = value - done
            done = value
            // A pull the other way is taken back first, then the list scrolls,
            // and what it cannot scroll becomes pull.
            val current = pull.floatValue
            if (current > 0f && step > 0f) {
                val back = minOf(current, step)
                pull.floatValue = current - back
                step -= back
            } else if (current < 0f && step < 0f) {
                val back = maxOf(current, step)
                pull.floatValue = current - back
                step -= back
            }
            if (step != 0f) {
                val consumed = scrollBy(step)
                val next = pull.floatValue - (step - consumed)
                if (pullPastEnd || next >= 0f) pull.floatValue = next
            }
        }
    }
}

@Composable
private fun EnhancedTrafficChart(
    stats: List<TrafficStat>,
    period: StatsPeriod,
    routerAt: (Long) -> Long = { 0L },
    /** The slot whose bar was tapped: the others dim, it is outlined. */
    selectedPeriod: Long? = null,
    /** A tap on a bar (anywhere in its column), with the slot's start. */
    onBarClick: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    // toArgb(), not hashCode(): Color.hashCode() is a hash of the packed
    // ULong, not an ARGB int — feeding it to android.graphics.Paint painted
    // the axis labels an arbitrary colour.
    val labelColorArgb = labelColor.toArgb()
    val scaleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    val scaleColorArgb = scaleColor.toArgb()
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    val weekPrefixFormat = stringResource(R.string.stats_week_short)

    // Build full slots (fill gaps with zero)
    val slots = buildSlots(stats, period)
    val routerBytes = slots.map { routerAt(it.period) }
    val maxBytes = slots.indices.maxOfOrNull { slots[it].totalBytes + routerBytes[it] }?.coerceAtLeast(1L) ?: 1L
    val maxIndex = if (routerBytes.any { it > 0 }) -1 else slots.indexOfFirst { it.totalBytes == maxBytes && maxBytes > 0 }

    // Grow-in animation — re-runs when data or period changes
    // Grows in once per period (each period is its own content), not again
    // with every minute of new traffic.
    val animProgress = remember(period) { Animatable(0f) }
    LaunchedEffect(period) {
        animProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        )
    }
    val progress = animProgress.value
    // The bars other than the picked one step back.
    val dim by androidx.compose.animation.core.animateFloatAsState(
        if (selectedPeriod != null) 1f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "stats-bars-dim",
    )

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
                    .padding(top = 16.dp, start = 12.dp, end = 16.dp, bottom = 28.dp)
                    // The same geometry as the bars below: a tap anywhere in a
                    // bar's column (its spacing included) picks that bar.
                    .pointerInput(slots) {
                        detectTapGestures { tap ->
                            if (slots.isEmpty()) return@detectTapGestures
                            val chartLeft = yLabelWidthPx
                            val chartWidth = size.width - chartLeft
                            val spacing = chartWidth * 0.22f / (slots.size + 1)
                            val barWidth = (chartWidth - chartWidth * 0.22f) / slots.size
                            val index = ((tap.x - chartLeft - spacing / 2) / (barWidth + spacing)).toInt()
                            if (tap.x >= chartLeft && index in slots.indices) onBarClick(slots[index].period)
                        }
                    },
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
                    val barAlpha = if (selectedPeriod != null && slot.period != selectedPeriod) 1f - 0.6f * dim else 1f
                    val frac = slot.totalBytes.toFloat() / maxBytes
                    val barHeight = frac * usableHeight * progress
                    val x = chartLeft + spacing + index * (barWidth + spacing)

                    if (barHeight > 0.5f) {
                        val isMax = index == maxIndex
                        val topColor = (if (isMax) VpnGreen else VpnBlue).copy(alpha = barAlpha)
                        val bottomColor = if (isMax) {
                            VpnGreen.copy(alpha = 0.2f * barAlpha)
                        } else {
                            VpnBlue.copy(alpha = 0.18f * barAlpha)
                        }
                        val barTop = chartHeight - barHeight

                        // Glow below the bar
                        drawRoundRect(
                            color = topColor.copy(alpha = 0.12f * barAlpha),
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
                                color = VpnGreen.copy(alpha = barAlpha),
                                topLeft = Offset(x, barTop),
                                size = Size(barWidth, barHeight),
                                cornerRadius = corner,
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }

                    // The picked bar: outlined over its whole height.
                    if (slot.period == selectedPeriod && progress > 0.95f) {
                        val total = barHeight + routerBytes[index].toFloat() / maxBytes * usableHeight * progress
                        if (total > 0.5f) {
                            drawRoundRect(
                                color = VpnBlue.copy(alpha = dim),
                                topLeft = Offset(x - 1.5.dp.toPx(), chartHeight - total - 1.5.dp.toPx()),
                                size = Size(barWidth + 3.dp.toPx(), total + 1.5.dp.toPx()),
                                cornerRadius = CornerRadius(5.dp.toPx()),
                                style = Stroke(width = 1.5.dp.toPx()),
                            )
                        }
                    }

                    // The router's part, stacked on this phone's (as on PC).
                    val routerHeight = routerBytes[index].toFloat() / maxBytes * usableHeight * progress
                    if (routerHeight > 0.5f) {
                        val bottom = chartHeight - barHeight.coerceAtLeast(0f)
                        val top = bottom - routerHeight
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(RouterTrafficColor.copy(alpha = barAlpha), RouterTrafficColor.copy(alpha = 0.45f * barAlpha)),
                                startY = top,
                                endY = bottom,
                            ),
                            topLeft = Offset(x, top),
                            // A gap of up to 1dp above this phone's part, never more than
                            // the router's part itself (a negative height draws upside down).
                            size = Size(barWidth, (routerHeight - if (barHeight > 0.5f) 1.dp.toPx() else 0f).coerceAtLeast(0.5f)),
                            cornerRadius = corner,
                        )
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
            // Must match StatsViewModel's week window: without pinning
            // firstDayOfWeek, Sunday-first locales resolve MONDAY to
            // tomorrow on Sundays and every slot lookup misses.
            cal.firstDayOfWeek = Calendar.MONDAY
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
                // No traffic of this phone this month: the month's weeks, so
                // the router's traffic still has its rows.
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val monthStart = cal.timeInMillis / 1000
                cal.add(Calendar.MONTH, 1)
                val monthEnd = cal.timeInMillis / 1000
                generateSequence(monthStart) { it + 7 * 86400L }
                    .takeWhile { it < monthEnd }
                    .map { TrafficStat(it, 0, 0, 0) }
                    .toList()
            } else {
                stats
            }
        }
    }
}

/** This month's weeks (from the 1st, 7 days each), with this phone's rows summed into them. */
private fun monthWeeks(stats: List<TrafficStat>): List<TrafficStat> {
    val cal = Calendar.getInstance(TimeZone.getDefault())
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    val monthStart = cal.timeInMillis / 1000
    cal.add(Calendar.MONTH, 1)
    val monthEnd = cal.timeInMillis / 1000
    val week = 7 * 86400L
    val starts = generateSequence(monthStart) { it + week }.takeWhile { it < monthEnd }.toList()
    return starts.mapIndexed { index, start ->
        val inWeek = stats.filter { ((it.period - monthStart) / week).toInt().coerceIn(0, starts.lastIndex) == index }
        TrafficStat(
            period = start,
            totalBytes = inWeek.sumOf { it.totalBytes },
            totalSeconds = inWeek.sumOf { it.totalSeconds },
            sessions = inWeek.sumOf { it.sessions },
        )
    }
}

/** How long one slot of the period is (a month's rows are weeks). */
private fun slotSeconds(period: StatsPeriod): Long = when (period) {
    StatsPeriod.DAY -> 3600L
    StatsPeriod.WEEK -> 86400L
    StatsPeriod.MONTH -> 7 * 86400L
}

/** The router's colour, as on the Router screen and on PC. */
private val RouterTrafficColor = Color(0xFF9C6BFF)

private fun slotLabel(
    slot: TrafficStat,
    period: StatsPeriod,
    index: Int,
    total: Int,
    weekPrefixFormat: String,
): String {
    return when (period) {
        StatsPeriod.DAY -> {
            // Show every 3rd hour. The slot index IS the local hour (slots
            // are built from local midnight); deriving the hour from the
            // epoch value instead would print UTC hours — off by the
            // timezone offset for everyone east/west of Greenwich.
            if (index % 3 == 0) "${index}:00" else ""
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
    routerBytes: Long = 0L,
    /** The row of the bar tapped on the chart: lit up. */
    highlighted: Boolean = false,
    /** The outline's opacity: the picked row's. */
    outline: Float = 0f,
    /** A tap on the row: it is picked as its bar would be. */
    onClick: () -> Unit = {},
) {
    val fraction = ((stat.totalBytes + routerBytes).toFloat() / maxBytes).coerceIn(0f, 1f)
    val container by androidx.compose.animation.animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(300),
        label = "stats-row-highlight",
    )
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (outline > 0.01f) BorderStroke(1.5.dp, VpnBlue.copy(alpha = outline)) else null,
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
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = listOfNotNull(
                            if (stat.sessions > 0 || stat.totalSeconds > 0) {
                                stringResource(R.string.stats_sessions_short, stat.sessions) +
                                    "  •  " + formatTime(stat.totalSeconds)
                            } else {
                                null
                            },
                            if (routerBytes > 0) stringResource(R.string.stats_router_short, formatBytes(routerBytes)) else null,
                        ).joinToString("  •  "),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = formatBytes(stat.totalBytes + routerBytes),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.Bold,
                    color = VpnGreen,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
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

/** What the lower part of the screen shows for one period and set of servers. */
private data class StatsSection(
    val period: StatsPeriod,
    val source: StatsServerSource?,
    val slots: List<TrafficStat>,
    val rows: List<TrafficStat>,
    val routerAt: (Long) -> Long,
    val loaded: Boolean = true,
)
