package com.tobevpn.app.presentation.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.remote.dto.NotificationItemDto
import com.tobevpn.app.notifications.NotificationsError
import com.tobevpn.app.presentation.components.BrandLoader
import com.tobevpn.app.presentation.components.SpinningRefreshIcon
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.BrandSoftCardFill
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.responsiveMaxWidth
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The announcements feed (bell on Home). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenNotification: (String) -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val items = feed.items

    Scaffold(
        topBar = {
            NotificationsTopBar(
                title = stringResource(R.string.notifications_title),
                onBack = onBack,
                actions = {
                    // Not while the full-screen loader runs.
                    if (items != null || feed.error != null) {
                        IconButton(onClick = viewModel::refresh, enabled = !feed.loading) {
                            SpinningRefreshIcon(
                                spinning = feed.loading,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        when {
            items == null && feed.error != null -> NotificationsMessage(
                icon = Icons.Filled.CloudOff,
                title = stringResource(R.string.notifications_error_title),
                text = notificationsErrorText(feed.error),
                actionLabel = stringResource(R.string.retry),
                onAction = viewModel::refresh,
                modifier = modifier,
            )

            items == null -> BrandLoader(modifier = modifier)

            items.isEmpty() -> NotificationsMessage(
                icon = Icons.Outlined.NotificationsOff,
                title = stringResource(R.string.notifications_empty_title),
                text = stringResource(R.string.notifications_empty_text),
                modifier = modifier,
            )

            else -> Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier
                        .widthIn(max = responsiveMaxWidth(560.dp))
                        .fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // A failed refresh keeps the list it had and says so on top.
                    feed.error?.let { error ->
                        item(key = "error") {
                            Text(
                                text = notificationsErrorText(error),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    items(items, key = { it.id }) { item ->
                        NotificationCard(item = item, onClick = { onOpenNotification(item.id) })
                    }
                }
            }
        }
    }
}

/** One announcement with its full text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDetailScreen(
    onBack: () -> Unit,
    onOpenPlans: () -> Unit,
    viewModel: NotificationDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            NotificationsTopBar(
                title = stringResource(R.string.notifications_title),
                onBack = onBack,
            )
        },
    ) { paddingValues ->
        val modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        when (val current = state) {
            NotificationDetailUiState.Loading -> BrandLoader(modifier = modifier)

            is NotificationDetailUiState.Failed -> if (current.error == NotificationsError.NotFound) {
                NotificationsMessage(
                    icon = Icons.Outlined.NotificationsOff,
                    title = stringResource(R.string.notification_unavailable_title),
                    text = stringResource(R.string.notification_unavailable_text),
                    actionLabel = stringResource(R.string.notification_back_to_feed),
                    onAction = onBack,
                    modifier = modifier,
                )
            } else {
                NotificationsMessage(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(R.string.notification_detail_error_title),
                    text = notificationsErrorText(current.error),
                    actionLabel = stringResource(R.string.retry),
                    onAction = viewModel::load,
                    modifier = modifier,
                )
            }

            is NotificationDetailUiState.Loaded -> {
                val detail = current.detail
                // The full text is drawn: this is when it counts as read.
                LaunchedEffect(detail.id) { viewModel.onShown() }
                Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
                    Column(
                        modifier = Modifier
                            .widthIn(max = responsiveMaxWidth(560.dp))
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    ) {
                        Text(
                            text = detail.title.orEmpty(),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                            fontWeight = FontWeight.Bold,
                        )
                        formatPublishedAt(detail.publishedAt)?.let { date ->
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = date,
                                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        // Plain text, line breaks kept; selectable for copying.
                        SelectionContainer {
                            Text(
                                text = detail.body ?: detail.preview.orEmpty(),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                            )
                        }
                        if (detail.action == ACTION_PLANS) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = onOpenPlans,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VpnBlue,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text(
                                    text = stringResource(R.string.notification_open_plans),
                                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationsTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    val contentColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black
    TopAppBar(
        title = {
            Text(
                text = title,
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
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = contentColor,
            navigationIconContentColor = contentColor,
            actionIconContentColor = contentColor,
        ),
    )
}

@Composable
private fun NotificationCard(item: NotificationItemDto, onClick: () -> Unit) {
    val unread = item.readAt == null
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = if (isSystemInDarkTheme()) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = BrandSoftCardFill, contentColor = Color.Black)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(VpnBlue.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Notifications,
                    contentDescription = null,
                    tint = VpnBlue,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title.orEmpty(),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (unread) {
                        Spacer(modifier = Modifier.width(8.dp))
                        NewBadge()
                    }
                }
                if (!item.preview.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = item.preview,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                formatPublishedAt(item.publishedAt)?.let { date ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = date,
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NewBadge() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(VpnBlue)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = stringResource(R.string.notification_new),
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelSmall),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun NotificationsMessage(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(54.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = title,
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = text,
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null) {
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedButton(onClick = onAction) {
                Text(
                    text = actionLabel,
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                )
            }
        }
    }
}

@Composable
private fun notificationsErrorText(error: NotificationsError?): String = when (error) {
    is NotificationsError.RateLimited -> error.retryAfterSeconds
        ?.takeIf { it > 0 }
        ?.let { stringResource(R.string.notifications_error_rate_limited_seconds, it.toInt()) }
        ?: stringResource(R.string.notifications_error_rate_limited)
    NotificationsError.Forbidden -> stringResource(R.string.notifications_error_forbidden)
    else -> stringResource(R.string.notifications_error_network)
}

/** "23 ноября, 12:00" in the user's zone; the year only when it is not this one. */
private fun formatPublishedAt(value: String?): String? {
    if (value.isNullOrBlank()) return null
    val instant = runCatching { OffsetDateTime.parse(value).toInstant() }
        .recoverCatching { Instant.parse(value) }
        .getOrNull() ?: return null
    val zone = ZoneId.systemDefault()
    val date = instant.atZone(zone)
    val pattern = if (date.year == Instant.now().atZone(zone).year) "d MMMM, HH:mm" else "d MMMM yyyy, HH:mm"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
}

private const val ACTION_PLANS = "plans"
