package com.tobevpn.app.presentation.referrals

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tobevpn.app.R
import com.tobevpn.app.data.remote.dto.ReferralListItemDto
import com.tobevpn.app.data.remote.dto.ReferralsDto
import com.tobevpn.app.presentation.components.SpinningRefreshIcon
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import java.text.DateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferralsScreen(
    onBack: () -> Unit,
    onNavigateToAuth: () -> Unit,
    viewModel: ReferralsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val topBarContentColor = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.Black
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.referrals_title),
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
                actions = {
                    if (uiState.isAuthenticated) {
                        IconButton(
                            onClick = viewModel::refresh,
                            enabled = !uiState.isAssigningReferrer,
                        ) {
                            SpinningRefreshIcon(
                                spinning = uiState.isInitialLoading || uiState.isRefreshing,
                                contentDescription = stringResource(R.string.refresh),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = topBarContentColor,
                    navigationIconContentColor = topBarContentColor,
                    actionIconContentColor = topBarContentColor,
                ),
            )
        },
    ) { paddingValues ->
        when {
            !uiState.isAuthResolved -> ReferralLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            !uiState.isAuthenticated -> ReferralAuthRequired(
                onNavigateToAuth = onNavigateToAuth,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            uiState.isInitialLoading && uiState.data == null -> ReferralLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            uiState.data == null -> ReferralFullScreenError(
                error = uiState.error ?: ReferralLoadError.UNKNOWN,
                onRetry = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            else -> ReferralContent(
                data = requireNotNull(uiState.data),
                error = uiState.error,
                referrerAssignmentError = uiState.referrerAssignmentError,
                isRefreshing = uiState.isRefreshing,
                isLoadingMore = uiState.isLoadingMore,
                isAssigningReferrer = uiState.isAssigningReferrer,
                onRetry = viewModel::refresh,
                onLoadMore = viewModel::loadMore,
                onAssignReferrer = viewModel::assignReferrer,
                onClearReferrerAssignmentError = viewModel::clearReferrerAssignmentError,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }
}

@Composable
private fun ReferralContent(
    data: ReferralsDto,
    error: ReferralLoadError?,
    referrerAssignmentError: ReferrerAssignmentError?,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    isAssigningReferrer: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onAssignReferrer: (Long) -> Unit,
    onClearReferrerAssignmentError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var referrerIdInput by rememberSaveable { mutableStateOf("") }
    var pendingReferrerId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showInvitedFriendsSheet by rememberSaveable { mutableStateOf(false) }
    val referralUrl = data.referralUrl.orEmpty()
    val shareText = stringResource(R.string.referrals_share_text, referralUrl)
    val shareChooserTitle = stringResource(R.string.referrals_share_chooser)
    val clipboardLabel = stringResource(R.string.referrals_clipboard_label)
    val items = data.referrals.orEmpty()
    val refreshPlaceholderAlpha = if (isRefreshing) {
        referralPlaceholderAlpha()
    } else {
        1f
    }

    val copyLink: () -> Unit = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(clipboardLabel, referralUrl))
    }
    val shareLink: () -> Unit = {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        runCatching {
            context.startActivity(Intent.createChooser(sendIntent, shareChooserTitle))
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 640.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                ReferralHeroCard(
                    referralUrl = referralUrl,
                    enabled = referralUrl.isNotBlank(),
                    onCopy = copyLink,
                    onShare = shareLink,
                )
            }

            if (data.referrer == null) {
                item {
                    ReferrerInputCard(
                        value = referrerIdInput,
                        error = referrerAssignmentError,
                        isSubmitting = isAssigningReferrer,
                        onValueChange = { rawValue ->
                            referrerIdInput = rawValue
                                .filter(Char::isDigit)
                                .take(MAX_TELEGRAM_ID_DIGITS)
                            onClearReferrerAssignmentError()
                        },
                        onRequestSubmit = { referrerId ->
                            focusManager.clearFocus()
                            pendingReferrerId = referrerId
                        },
                    )
                }
            }

            data.referrer?.let { referrer ->
                item {
                    ReferrerCard(
                        displayName = referrer.displayName,
                        telegramId = referrer.telegramId,
                    )
                }
            }

            item {
                if (isRefreshing) {
                    ReferralSummaryLoadingCard(alpha = refreshPlaceholderAlpha)
                } else {
                    ReferralSummaryCard(
                        total = data.total,
                        onOpenList = { showInvitedFriendsSheet = true },
                    )
                }
            }

            if (error != null) {
                item {
                    ReferralInlineError(
                        error = error,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }

    if (showInvitedFriendsSheet) {
        InvitedFriendsBottomSheet(
            items = items,
            total = data.total,
            error = error,
            isRefreshing = isRefreshing,
            isLoadingMore = isLoadingMore,
            onRetry = onRetry,
            onLoadMore = onLoadMore,
            onDismiss = { showInvitedFriendsSheet = false },
        )
    }

    pendingReferrerId?.let { referrerId ->
        ReferrerConfirmationDialog(
            referrerId = referrerId,
            onConfirm = {
                pendingReferrerId = null
                onAssignReferrer(referrerId)
            },
            onDismiss = { pendingReferrerId = null },
        )
    }
}

@Composable
private fun ReferralHeroCard(
    referralUrl: String,
    enabled: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val primaryText = if (darkTheme) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryText = if (darkTheme) {
        Color.White.copy(alpha = 0.78f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            VpnGreen.copy(alpha = if (darkTheme) 0.32f else 0.22f),
                            VpnBlue.copy(alpha = if (darkTheme) 0.22f else 0.14f),
                        ),
                    ),
                )
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(VpnGreen.copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.CardGiftcard,
                        contentDescription = null,
                        tint = VpnGreen,
                        modifier = Modifier.size(27.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.referrals_hero_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                        color = primaryText,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.referrals_hero_description),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        color = secondaryText,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = if (darkTheme) 0.62f else 0.82f),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(19.dp),
                        )
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = stringResource(R.string.referrals_your_link),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(7.dp))
                    Text(
                        text = referralUrl.ifBlank {
                            stringResource(R.string.referrals_link_unavailable)
                        },
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReferralCopyButton(
                    onClick = onCopy,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
                ReferralShareButton(
                    onClick = onShare,
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ReferralCopyButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = referralSecondaryButtonColors(),
    ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
        )
        Spacer(modifier = Modifier.width(7.dp))
        ReferralActionText(text = stringResource(R.string.referrals_copy))
    }
}

@Composable
private fun ReferralShareButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(14.dp),
        colors = referralPrimaryButtonColors(),
    ) {
        Icon(
            Icons.Filled.Share,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
        )
        Spacer(modifier = Modifier.width(7.dp))
        ReferralActionText(text = stringResource(R.string.referrals_share))
    }
}

@Composable
private fun ReferralActionText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ReferralSummaryCard(
    total: Int,
    onOpenList: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(VpnBlue.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Groups,
                        contentDescription = null,
                        tint = VpnBlue,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column {
                    Text(
                        text = total.toString(),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.headlineMedium),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.referrals_invited_count,
                            total,
                            total,
                        ),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(
                onClick = onOpenList,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = referralOutlinedButtonColors(),
                border = referralOutlinedButtonBorder(enabled = true),
            ) {
                Text(
                    text = stringResource(R.string.referrals_open_list),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
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
private fun InvitedFriendsBottomSheet(
    items: List<ReferralListItemDto>,
    total: Int,
    error: ReferralLoadError?,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = isSystemInDarkTheme()
    val refreshPlaceholderAlpha = if (isRefreshing) {
        referralPlaceholderAlpha()
    } else {
        1f
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        containerColor = if (isDark) {
            BottomSheetDefaults.ContainerColor
        } else {
            Color.White
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
            ) {
                Text(
                    text = stringResource(R.string.referrals_invited_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pluralStringResource(
                        R.plurals.referrals_invited_count,
                        total,
                        total,
                    ),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 28.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (error != null) {
                    item {
                        ReferralInlineError(
                            error = error,
                            onRetry = onRetry,
                        )
                    }
                }

                if (isRefreshing) {
                    repeat(3) { index ->
                        item(key = "referral-sheet-refresh-placeholder-$index") {
                            ReferralListLoadingRow(alpha = refreshPlaceholderAlpha)
                        }
                    }
                } else if (items.isEmpty()) {
                    item {
                        ReferralEmptyList()
                    }
                } else {
                    itemsIndexed(
                        items = items,
                        key = { index, item ->
                            "${item.telegramId ?: "unknown"}:${item.createdAt.orEmpty()}:$index"
                        },
                    ) { _, item ->
                        ReferralListRow(item)
                    }
                }

                if (!isRefreshing && items.size < total) {
                    item {
                        OutlinedButton(
                            onClick = onLoadMore,
                            enabled = !isLoadingMore,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = referralOutlinedButtonColors(),
                            border = referralOutlinedButtonBorder(enabled = !isLoadingMore),
                        ) {
                            if (isLoadingMore) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = referralProgressColor(),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = stringResource(R.string.referrals_load_more),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferralSummaryLoadingCard(alpha: Float) {
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(placeholderColor),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .width(190.dp)
                        .height(17.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
            }
        }
    }
}

@Composable
private fun ReferrerInputCard(
    value: String,
    error: ReferrerAssignmentError?,
    isSubmitting: Boolean,
    onValueChange: (String) -> Unit,
    onRequestSubmit: (Long) -> Unit,
) {
    val referrerId = value.toLongOrNull()?.takeIf { it > 0 }
    val hasLocalError = value.isNotEmpty() && referrerId == null
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(VpnGreen.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PersonAdd,
                        contentDescription = null,
                        tint = VpnGreen,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Spacer(modifier = Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.referrals_referrer_input_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.referrals_referrer_input_description),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !isSubmitting,
                modifier = Modifier.fillMaxWidth(),
                textStyle = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                label = {
                    Text(
                        text = stringResource(R.string.referrals_referrer_id_label),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                    )
                },
                singleLine = true,
                isError = hasLocalError || error != null,
                supportingText = when {
                    hasLocalError -> {
                        {
                            Text(
                                text = stringResource(R.string.referrals_referrer_id_invalid),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            )
                        }
                    }

                    error != null -> {
                        {
                            Text(
                                text = referrerAssignmentErrorText(error),
                                style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            )
                        }
                    }

                    else -> null
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                colors = referralTextFieldColors(),
                shape = RoundedCornerShape(14.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = { referrerId?.let(onRequestSubmit) },
                enabled = referrerId != null && !isSubmitting,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp),
                colors = referralPrimaryButtonColors(),
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = referralProgressColor(),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(
                        if (isSubmitting) {
                            R.string.referrals_referrer_assigning
                        } else {
                            R.string.referrals_referrer_assign
                        },
                    ),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferrerCard(
    displayName: String?,
    telegramId: Long?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(VpnGreen.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = VpnGreen,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.referrals_referred_by),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: telegramId?.let {
                            stringResource(R.string.referrals_referrer_id_value, it)
                        }
                        ?: stringResource(R.string.referrals_unknown_user),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferrerConfirmationDialog(
    referrerId: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
    val dialogShape = RoundedCornerShape(28.dp)
    val dialogBorderColor = if (darkTheme) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.Black.copy(alpha = 0.18f)
    }
    val dialogContainerColor = if (darkTheme) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val idContainerColor = MaterialTheme.colorScheme.surfaceVariant
    val secondaryButtonBorderColor = if (darkTheme) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    } else {
        Color(0xFFD0D0D0)
    }

    AlertDialog(
        modifier = Modifier.border(
            width = 1.dp,
            color = dialogBorderColor,
            shape = dialogShape,
        ),
        onDismissRequest = onDismiss,
        shape = dialogShape,
        containerColor = dialogContainerColor,
        icon = {
            Icon(
                Icons.Filled.PersonAdd,
                contentDescription = null,
                tint = VpnGreen,
            )
        },
        title = {
            Text(
                text = stringResource(R.string.referrals_referrer_confirm_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.referrals_referrer_confirm_description),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = idContainerColor,
                    ) {
                        Text(
                            text = stringResource(R.string.referrals_referrer_id_value, referrerId),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.SemiBold,
                            color = if (darkTheme) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = referralSecondaryButtonColors(),
                    border = BorderStroke(1.dp, secondaryButtonBorderColor),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(13.dp),
                    colors = referralPrimaryButtonColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.referrals_referrer_confirm),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun ReferralListRow(item: ReferralListItemDto) {
    val formattedDate = remember(item.createdAt) {
        formatReferralDate(item.createdAt)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.displayName
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.referrals_unknown_user),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (formattedDate != null) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.referrals_joined, formattedDate),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = VpnGreen.copy(alpha = 0.14f),
                    contentColor = VpnGreen,
                ) {
                    Text(
                        text = stringResource(
                            R.string.referrals_level,
                            item.level.coerceAtLeast(1),
                        ),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelMedium),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReferralListLoadingRow(alpha: Float) {
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(placeholderColor),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.58f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.38f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Box(
                modifier = Modifier
                    .width(58.dp)
                    .height(27.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(placeholderColor),
            )
        }
    }
}

@Composable
private fun ReferralEmptyList() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.GroupAdd,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.referrals_empty_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.referrals_empty_description),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ReferralInlineError(
    error: ReferralLoadError,
    onRetry: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = referralErrorText(error),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRetry,
                colors = referralOutlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                border = referralOutlinedButtonBorder(
                    enabled = true,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(
                    text = stringResource(R.string.retry),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReferralLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = referralProgressColor())
    }
}

@Composable
private fun ReferralAuthRequired(
    onNavigateToAuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(VpnBlue.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.GroupAdd,
                contentDescription = null,
                tint = VpnBlue,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.referrals_auth_title),
            style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.referrals_auth_description),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(22.dp))
        Button(
            onClick = onNavigateToAuth,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = referralPrimaryButtonColors(),
        ) {
            Text(
                text = stringResource(R.string.login_via_telegram),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReferralFullScreenError(
    error: ReferralLoadError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(54.dp),
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.referrals_error_title),
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = referralErrorText(error),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onRetry,
            colors = referralPrimaryButtonColors(),
        ) {
            Text(
                text = stringResource(R.string.retry),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun referralErrorText(error: ReferralLoadError): String = when (error) {
    ReferralLoadError.NETWORK -> stringResource(R.string.referrals_error_network)
    ReferralLoadError.UNAVAILABLE -> stringResource(R.string.referrals_error_unavailable)
    ReferralLoadError.UNKNOWN -> stringResource(R.string.referrals_error_unknown)
}

private fun formatReferralDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { Instant.parse(raw) }
        .getOrNull()
        ?: return null
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(instant))
}

private val LightPrimaryAction = Color(0xFF3F3F3F)
private val LightSecondaryAction = Color(0xFFFFFFFF)
private val LightSecondaryActionContent = Color(0xFF242424)
private val LightDisabledAction = Color(0xFFD4D4D4)
private val LightDisabledContent = Color(0xFF777777)
private val LightOutline = Color(0xFF6D6D6D)
private const val MAX_TELEGRAM_ID_DIGITS = 19

@Composable
private fun referralPrimaryButtonColors(): ButtonColors {
    return if (isSystemInDarkTheme()) {
        ButtonDefaults.buttonColors()
    } else {
        ButtonDefaults.buttonColors(
            containerColor = LightPrimaryAction,
            contentColor = Color.White,
            disabledContainerColor = LightDisabledAction,
            disabledContentColor = LightDisabledContent,
        )
    }
}

@Composable
private fun referralSecondaryButtonColors(): ButtonColors {
    return if (isSystemInDarkTheme()) {
        ButtonDefaults.filledTonalButtonColors()
    } else {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = LightSecondaryAction,
            contentColor = LightSecondaryActionContent,
            disabledContainerColor = LightDisabledAction,
            disabledContentColor = LightDisabledContent,
        )
    }
}

@Composable
private fun referralOutlinedButtonColors(
    contentColor: Color? = null,
): ButtonColors {
    val resolvedContentColor = contentColor ?: if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.primary
    } else {
        LightPrimaryAction
    }
    return ButtonDefaults.outlinedButtonColors(
        contentColor = resolvedContentColor,
        disabledContentColor = if (isSystemInDarkTheme()) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        } else {
            LightDisabledContent
        },
    )
}

@Composable
private fun referralOutlinedButtonBorder(
    enabled: Boolean,
    color: Color? = null,
): BorderStroke {
    val resolvedColor = color ?: if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.outline
    } else {
        LightOutline
    }
    return BorderStroke(
        width = 1.dp,
        color = if (enabled) resolvedColor else resolvedColor.copy(alpha = 0.38f),
    )
}

@Composable
private fun referralTextFieldColors(): TextFieldColors {
    return if (isSystemInDarkTheme()) {
        OutlinedTextFieldDefaults.colors()
    } else {
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = LightPrimaryAction,
            unfocusedBorderColor = LightOutline,
            cursorColor = LightPrimaryAction,
            focusedLabelColor = LightPrimaryAction,
            focusedLeadingIconColor = LightPrimaryAction,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun referralProgressColor(): Color = if (isSystemInDarkTheme()) {
    MaterialTheme.colorScheme.primary
} else {
    LightPrimaryAction
}

@Composable
private fun referralPlaceholderAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "referral-refresh")
    val alpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "referral-placeholder-alpha",
    )
    return alpha
}

@Composable
private fun referrerAssignmentErrorText(error: ReferrerAssignmentError): String = when (error) {
    ReferrerAssignmentError.NETWORK ->
        stringResource(R.string.referrals_referrer_error_network)

    ReferrerAssignmentError.NOT_FOUND ->
        stringResource(R.string.referrals_referrer_error_not_found)

    ReferrerAssignmentError.CONFLICT ->
        stringResource(R.string.referrals_referrer_error_conflict)

    ReferrerAssignmentError.UNAVAILABLE ->
        stringResource(R.string.referrals_referrer_error_unavailable)

    ReferrerAssignmentError.UNKNOWN ->
        stringResource(R.string.referrals_referrer_error_unknown)
}
