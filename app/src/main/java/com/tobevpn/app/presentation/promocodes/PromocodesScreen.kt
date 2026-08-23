package com.tobevpn.app.presentation.promocodes

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.tobevpn.app.data.remote.dto.PromocodeActivationResultDto
import com.tobevpn.app.data.remote.dto.PromocodeHistoryItemDto
import com.tobevpn.app.data.remote.dto.PromocodePlanSnapshotDto
import com.tobevpn.app.presentation.components.SpinningRefreshIcon
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.theme.AppAlertDialog
import com.tobevpn.app.presentation.theme.BrandSoftCardFill
import com.tobevpn.app.presentation.theme.VpnBlue
import com.tobevpn.app.presentation.theme.VpnGreen
import com.tobevpn.app.presentation.theme.VpnOrange
import com.tobevpn.app.presentation.theme.responsiveMaxWidth
import java.text.DateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromocodesScreen(
    onBack: () -> Unit,
    onNavigateToAuth: () -> Unit,
    viewModel: PromocodesViewModel = hiltViewModel(),
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
                        text = stringResource(R.string.promocodes_title),
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
                            enabled = !uiState.isActivating,
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
            !uiState.isAuthResolved -> PromocodeLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            !uiState.isAuthenticated -> PromocodeAuthRequired(
                onNavigateToAuth = onNavigateToAuth,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            uiState.isInitialLoading && uiState.history == null -> PromocodeLoading(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            uiState.history == null -> PromocodeFullScreenError(
                error = uiState.loadError ?: PromocodeLoadError.UNKNOWN,
                onRetry = viewModel::refresh,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )

            else -> PromocodeContent(
                items = uiState.history?.promocodes.orEmpty(),
                total = uiState.history?.total ?: 0,
                effectiveDiscountPercent = uiState.effectiveDiscountPercent,
                isActivating = uiState.isActivating,
                isRefreshing = uiState.isRefreshing,
                isLoadingMore = uiState.isLoadingMore,
                loadError = uiState.loadError,
                activationError = uiState.activationError,
                activationResult = uiState.activationResult,
                onActivate = viewModel::activate,
                onLoadMore = viewModel::loadMore,
                onRetry = viewModel::refresh,
                onClearActivationError = viewModel::clearActivationError,
                onDismissActivationResult = viewModel::dismissActivationResult,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            )
        }
    }
}

@Composable
private fun PromocodeContent(
    items: List<PromocodeHistoryItemDto>,
    total: Int,
    effectiveDiscountPercent: Int,
    isActivating: Boolean,
    isRefreshing: Boolean,
    isLoadingMore: Boolean,
    loadError: PromocodeLoadError?,
    activationError: PromocodeActivationError?,
    activationResult: PromocodeActivationResultDto?,
    onActivate: (String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onClearActivationError: () -> Unit,
    onDismissActivationResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var codeInput by rememberSaveable { mutableStateOf("") }
    val trimmedCode = codeInput.trim()
    val pageMaxWidth = responsiveMaxWidth(640.dp)
    val refreshPlaceholderAlpha = if (isRefreshing) {
        promocodePlaceholderAlpha()
    } else {
        1f
    }

    LaunchedEffect(activationResult) {
        if (activationResult != null) {
            codeInput = ""
            focusManager.clearFocus()
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = pageMaxWidth),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PromocodeActivationCard(
                    value = codeInput,
                    isActivating = isActivating,
                    error = activationError,
                    onValueChange = { rawValue ->
                        codeInput = rawValue
                            .replace("\n", "")
                            .replace("\r", "")
                            .take(MAX_PROMOCODE_LENGTH)
                        onClearActivationError()
                    },
                    onActivate = {
                        if (trimmedCode.isNotEmpty()) {
                            focusManager.clearFocus()
                            onActivate(trimmedCode)
                        }
                    },
                )
            }

            if (isRefreshing) {
                if (effectiveDiscountPercent > 0) {
                    item {
                        PromocodeDiscountLoadingCard(alpha = refreshPlaceholderAlpha)
                    }
                }
                item {
                    PromocodeHistoryHeaderLoading(alpha = refreshPlaceholderAlpha)
                }
                repeat(items.size.coerceIn(2, 3)) { index ->
                    item(key = "promocode-refresh-placeholder-$index") {
                        PromocodeHistoryLoadingCard(alpha = refreshPlaceholderAlpha)
                    }
                }
            } else {
                if (effectiveDiscountPercent > 0) {
                    item {
                        CurrentDiscountCard(discountPercent = effectiveDiscountPercent)
                    }
                }

                item {
                    PromocodeHistoryHeader(total = total)
                }

                if (items.isEmpty()) {
                    item { PromocodeEmptyHistoryCard() }
                } else {
                    items(
                        items = items,
                        key = { item ->
                            item.activationId
                                ?: "${item.promocodeId}:${item.code}:${item.activatedAt}"
                        },
                    ) { item ->
                        PromocodeHistoryCard(item = item)
                    }
                }

                if (loadError != null) {
                    item {
                        PromocodeInlineError(
                            error = loadError,
                            onRetry = onRetry,
                        )
                    }
                }

                if (items.size < total) {
                    item {
                        PromocodeLoadMoreButton(
                            loading = isLoadingMore,
                            onClick = onLoadMore,
                        )
                    }
                }
            }
        }
    }

    activationResult?.let { result ->
        PromocodeSuccessDialog(
            result = result,
            onDismiss = onDismissActivationResult,
        )
    }
}

@Composable
private fun PromocodeActivationCard(
    value: String,
    isActivating: Boolean,
    error: PromocodeActivationError?,
    onValueChange: (String) -> Unit,
    onActivate: () -> Unit,
) {
    val darkTheme = isSystemInDarkTheme()
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
                            PromocodeAccent.copy(alpha = if (darkTheme) 0.16f else 0.10f),
                            PromocodeGradientEnd.copy(alpha = if (darkTheme) 0.10f else 0.06f),
                        ),
                    ),
                )
                .padding(18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(PromocodeAccent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.LocalOffer,
                        contentDescription = null,
                        tint = PromocodeAccent,
                        modifier = Modifier.size(27.dp),
                    )
                }
                Spacer(modifier = Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.promocodes_activate_title),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.promocodes_activate_description),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isActivating,
                singleLine = true,
                isError = error != null,
                label = {
                    Text(
                        text = stringResource(R.string.promocodes_code_label),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    )
                },
                placeholder = {
                    Text(
                        text = stringResource(R.string.promocodes_code_placeholder),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Filled.LocalOffer,
                        contentDescription = null,
                    )
                },
                trailingIcon = if (value.isNotEmpty() && !isActivating) {
                    {
                        IconButton(onClick = { onValueChange("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.promocodes_clear_code),
                            )
                        }
                    }
                } else {
                    null
                },
                supportingText = error?.let {
                    {
                        Text(
                            text = promocodeActivationErrorText(it),
                            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (value.isNotBlank() && !isActivating) onActivate()
                    },
                ),
                shape = RoundedCornerShape(14.dp),
                colors = promocodeTextFieldColors(),
            )

            Spacer(modifier = Modifier.height(if (error == null) 14.dp else 8.dp))

            Button(
                onClick = onActivate,
                enabled = value.isNotBlank() && !isActivating,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = promocodePrimaryButtonColors(),
            ) {
                if (isActivating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = if (darkTheme) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            Color.White
                        },
                    )
                    Spacer(modifier = Modifier.width(9.dp))
                }
                Text(
                    text = if (isActivating) {
                        stringResource(R.string.promocodes_activating)
                    } else {
                        stringResource(R.string.promocodes_activate_button)
                    },
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PromocodeDiscountLoadingCard(alpha: Float) {
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = VpnGreen.copy(alpha = if (isSystemInDarkTheme()) 0.16f else 0.10f),
        ),
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
                verticalArrangement = Arrangement.spacedBy(7.dp),
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
                        .fillMaxWidth(0.82f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .width(54.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(placeholderColor),
            )
        }
    }
}

@Composable
private fun PromocodeHistoryHeaderLoading(alpha: Float) {
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Column(
        modifier = Modifier
            .padding(top = 4.dp)
            .alpha(alpha),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(21.dp)
                    .clip(CircleShape)
                    .background(placeholderColor),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(placeholderColor),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .height(14.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(placeholderColor),
        )
    }
}

@Composable
private fun PromocodeHistoryLoadingCard(alpha: Float) {
    val darkTheme = isSystemInDarkTheme()
    val placeholderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = if (darkTheme) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(containerColor = BrandSoftCardFill)
        },
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
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(placeholderColor),
            )
            Spacer(modifier = Modifier.width(13.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.55f)
                        .height(18.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.78f)
                        .height(15.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.42f)
                        .height(13.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(placeholderColor),
                )
            }
        }
    }
}

@Composable
private fun CurrentDiscountCard(discountPercent: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = VpnGreen.copy(alpha = if (isSystemInDarkTheme()) 0.16f else 0.10f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(VpnGreen.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Percent,
                    contentDescription = null,
                    tint = VpnGreen,
                    modifier = Modifier.size(23.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.promocodes_current_discount_title),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.promocodes_current_discount_description),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = VpnGreen.copy(alpha = 0.18f),
                contentColor = VpnGreen,
            ) {
                Text(
                    text = stringResource(
                        R.string.promocodes_discount_value,
                        discountPercent.coerceIn(0, 100),
                    ),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun PromocodeHistoryHeader(total: Int) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(21.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.promocodes_history_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            if (total > 0) {
                Text(
                    text = total.toString(),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = stringResource(R.string.promocodes_history_description),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PromocodeHistoryCard(item: PromocodeHistoryItemDto) {
    val darkTheme = isSystemInDarkTheme()
    val type = item.rewardType.orEmpty().uppercase(Locale.ROOT)
    val accent = promocodeRewardAccent(type)
    val formattedDate = remember(item.activatedAt) {
        formatPromocodeDate(item.activatedAt)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = if (darkTheme) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                // Applied promocodes use a lighter neutral card than the
                // settings grid so history does not look visually heavy.
                containerColor = BrandSoftCardFill,
                contentColor = Color.Black,
            )
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = promocodeRewardIcon(type),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(modifier = Modifier.width(13.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.code
                        ?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.promocodes_unknown_code),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                    fontWeight = FontWeight.Bold,
                    color = if (darkTheme) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        Color.Black
                    },
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = promocodeRewardText(
                        rewardType = type,
                        reward = item.reward,
                        planSnapshot = item.planSnapshot,
                    ),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (formattedDate != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = stringResource(R.string.promocodes_activated_at, formattedDate),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PromocodeEmptyHistoryCard() {
    val darkTheme = isSystemInDarkTheme()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = if (darkTheme) {
            CardDefaults.cardColors()
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = Color.Black,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.LocalOffer,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.promocodes_empty_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.promocodes_empty_description),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PromocodeInlineError(
    error: PromocodeLoadError,
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
                text = promocodeLoadErrorText(error),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onRetry,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                border = BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f),
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
private fun PromocodeLoadMoreButton(
    loading: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = !loading,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = promocodeOutlinedButtonColors(),
        border = promocodeOutlinedButtonBorder(),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(17.dp),
                strokeWidth = 2.dp,
                color = promocodeButtonContentColor(),
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = if (loading) {
                stringResource(R.string.promocodes_loading_more)
            } else {
                stringResource(R.string.promocodes_load_more)
            },
            style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PromocodeSuccessDialog(
    result: PromocodeActivationResultDto,
    onDismiss: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    val borderColor = if (isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.16f)
    } else {
        Color.Black.copy(alpha = 0.16f)
    }
    AppAlertDialog(
        modifier = Modifier.border(
            width = 1.dp,
            color = borderColor,
            shape = shape,
        ),
        onDismissRequest = onDismiss,
        shape = shape,
        icon = {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(VpnGreen.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.LocalOffer,
                    contentDescription = null,
                    tint = VpnGreen,
                    modifier = Modifier.size(29.dp),
                )
            }
        },
        title = {
            Text(
                text = stringResource(R.string.promocodes_success_title),
                style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                result.code?.takeIf { it.isNotBlank() }?.let { code ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = code,
                            style = fixedLayoutTextStyle(MaterialTheme.typography.titleMedium),
                            fontWeight = FontWeight.Bold,
                            color = if (isSystemInDarkTheme()) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Black
                            },
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Text(
                    text = promocodeRewardText(
                        rewardType = result.rewardType.orEmpty().uppercase(Locale.ROOT),
                        reward = result.reward,
                        planSnapshot = result.planSnapshot,
                    ),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyLarge),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.promocodes_success_description),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = promocodePrimaryButtonColors(),
            ) {
                Text(
                    text = stringResource(R.string.done),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {},
    )
}

@Composable
private fun PromocodeLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = PromocodeAccent)
    }
}

@Composable
private fun PromocodeAuthRequired(
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
                .background(PromocodeAccent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.LocalOffer,
                contentDescription = null,
                tint = PromocodeAccent,
                modifier = Modifier.size(38.dp),
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.promocodes_auth_title),
            style = fixedLayoutTextStyle(MaterialTheme.typography.headlineSmall),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.promocodes_auth_description),
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
            colors = promocodePrimaryButtonColors(),
        ) {
            Text(
                text = stringResource(R.string.login_action),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PromocodeFullScreenError(
    error: PromocodeLoadError,
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
            text = stringResource(R.string.promocodes_error_title),
            style = fixedLayoutTextStyle(MaterialTheme.typography.titleLarge),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = promocodeLoadErrorText(error),
            style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(18.dp))
        Button(
            onClick = onRetry,
            colors = promocodePrimaryButtonColors(),
        ) {
            Text(
                text = stringResource(R.string.retry),
                style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
            )
        }
    }
}

@Composable
private fun promocodeRewardText(
    rewardType: String,
    reward: Int?,
    planSnapshot: PromocodePlanSnapshotDto?,
): String = when (rewardType) {
    "DURATION" -> when {
        reward == 0 -> stringResource(R.string.promocodes_reward_duration_unlimited)
        reward != null -> pluralStringResource(
            R.plurals.promocodes_reward_duration_days,
            reward,
            reward,
        )
        else -> stringResource(R.string.promocodes_reward_applied)
    }
    "TRAFFIC" -> when {
        reward == 0 -> stringResource(R.string.promocodes_reward_traffic_unlimited)
        reward != null -> stringResource(R.string.promocodes_reward_traffic, reward)
        else -> stringResource(R.string.promocodes_reward_applied)
    }
    "DEVICES" -> when {
        reward == 0 -> stringResource(R.string.promocodes_reward_devices_unlimited)
        reward != null -> pluralStringResource(
            R.plurals.promocodes_reward_devices,
            reward,
            reward,
        )
        else -> stringResource(R.string.promocodes_reward_applied)
    }
    "SUBSCRIPTION" -> {
        val planName = planSnapshot?.name?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.promocodes_reward_subscription_unknown)
        when (val duration = planSnapshot?.duration) {
            null -> stringResource(R.string.promocodes_reward_subscription, planName)
            0 -> stringResource(R.string.promocodes_reward_subscription_unlimited, planName)
            else -> stringResource(
                R.string.promocodes_reward_subscription_duration,
                planName,
                pluralStringResource(R.plurals.promocodes_days, duration, duration),
            )
        }
    }
    "PERSONAL_DISCOUNT" -> stringResource(
        R.string.promocodes_reward_personal_discount,
        reward ?: 0,
    )
    "PURCHASE_DISCOUNT" -> stringResource(
        R.string.promocodes_reward_purchase_discount,
        reward ?: 0,
    )
    else -> stringResource(R.string.promocodes_reward_applied)
}

@Composable
private fun promocodeActivationErrorText(error: PromocodeActivationError): String = when (error) {
    PromocodeActivationError.NETWORK -> stringResource(R.string.promocodes_activation_error_network)
    PromocodeActivationError.NOT_FOUND -> stringResource(R.string.promocodes_activation_error_not_found)
    PromocodeActivationError.EXPIRED -> stringResource(R.string.promocodes_activation_error_expired)
    PromocodeActivationError.ALREADY_ACTIVATED ->
        stringResource(R.string.promocodes_activation_error_already_activated)
    PromocodeActivationError.ACTIVE_SUBSCRIPTION_REQUIRED ->
        stringResource(R.string.promocodes_activation_error_active_subscription)
    PromocodeActivationError.ALREADY_UNLIMITED ->
        stringResource(R.string.promocodes_activation_error_already_unlimited)
    PromocodeActivationError.ACTIVATION_LIMIT_REACHED ->
        stringResource(R.string.promocodes_activation_error_limit)
    PromocodeActivationError.NEW_USERS_ONLY ->
        stringResource(R.string.promocodes_activation_error_new_users)
    PromocodeActivationError.EXISTING_USERS_ONLY ->
        stringResource(R.string.promocodes_activation_error_existing_users)
    PromocodeActivationError.INVITED_USERS_ONLY ->
        stringResource(R.string.promocodes_activation_error_invited_users)
    PromocodeActivationError.NOT_AVAILABLE ->
        stringResource(R.string.promocodes_activation_error_not_available)
    PromocodeActivationError.AUTH_REQUIRED ->
        stringResource(R.string.promocodes_activation_error_auth)
    PromocodeActivationError.TOO_MANY_REQUESTS ->
        stringResource(R.string.promocodes_activation_error_too_many)
    PromocodeActivationError.UNKNOWN -> stringResource(R.string.promocodes_activation_error_unknown)
}

@Composable
private fun promocodeLoadErrorText(error: PromocodeLoadError): String = when (error) {
    PromocodeLoadError.NETWORK -> stringResource(R.string.promocodes_load_error_network)
    PromocodeLoadError.AUTH_REQUIRED -> stringResource(R.string.promocodes_load_error_auth)
    PromocodeLoadError.UNAVAILABLE -> stringResource(R.string.promocodes_load_error_unavailable)
    PromocodeLoadError.UNKNOWN -> stringResource(R.string.promocodes_load_error_unknown)
}

private fun promocodeRewardIcon(type: String): ImageVector = when (type) {
    "DURATION" -> Icons.Filled.Schedule
    "TRAFFIC" -> Icons.Filled.DataUsage
    "DEVICES" -> Icons.Filled.Devices
    "SUBSCRIPTION" -> Icons.Filled.CardGiftcard
    "PERSONAL_DISCOUNT", "PURCHASE_DISCOUNT" -> Icons.Filled.Percent
    else -> Icons.Filled.LocalOffer
}

private fun promocodeRewardAccent(type: String): Color = when (type) {
    "DURATION" -> VpnGreen
    "TRAFFIC" -> VpnBlue
    "DEVICES" -> Color(0xFF8B7CF6)
    "SUBSCRIPTION" -> VpnOrange
    "PERSONAL_DISCOUNT" -> Color(0xFFE65C9C)
    else -> PromocodeAccent
}

private fun formatPromocodeDate(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val instant = runCatching { OffsetDateTime.parse(raw).toInstant() }
        .recoverCatching { Instant.parse(raw) }
        .getOrNull()
        ?: return null
    return DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(instant))
}

@Composable
private fun promocodePrimaryButtonColors() = if (isSystemInDarkTheme()) {
    ButtonDefaults.buttonColors()
} else {
    ButtonDefaults.buttonColors(
        containerColor = LightPrimaryAction,
        contentColor = Color.White,
    )
}

@Composable
private fun promocodeOutlinedButtonColors() = if (isSystemInDarkTheme()) {
    ButtonDefaults.outlinedButtonColors()
} else {
    ButtonDefaults.outlinedButtonColors(
        contentColor = LightPrimaryAction,
        disabledContentColor = LightDisabledContent,
    )
}

@Composable
private fun promocodeOutlinedButtonBorder(): BorderStroke = BorderStroke(
    width = 1.dp,
    color = if (isSystemInDarkTheme()) {
        MaterialTheme.colorScheme.outline
    } else {
        LightOutline
    },
)

@Composable
private fun promocodeButtonContentColor(): Color = if (isSystemInDarkTheme()) {
    MaterialTheme.colorScheme.primary
} else {
    LightPrimaryAction
}

@Composable
private fun promocodeTextFieldColors() = if (isSystemInDarkTheme()) {
    OutlinedTextFieldDefaults.colors()
} else {
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = LightPrimaryAction,
        focusedLabelColor = LightPrimaryAction,
        cursorColor = LightPrimaryAction,
        focusedTextColor = Color.Black,
        unfocusedTextColor = Color.Black,
        focusedTrailingIconColor = LightPrimaryAction,
        unfocusedTrailingIconColor = Color(0xFF5F5F5F),
    )
}

@Composable
private fun promocodePlaceholderAlpha(): Float {
    val transition = rememberInfiniteTransition(label = "promocode-refresh")
    val alpha by transition.animateFloat(
        initialValue = 0.42f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 720),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "promocode-placeholder-alpha",
    )
    return alpha
}

private val PromocodeAccent = Color(0xFFE09A2D)
private val PromocodeGradientEnd = Color(0xFFE57373)
private val LightPrimaryAction = Color(0xFF3F3F3F)
private val LightDisabledContent = Color(0xFF777777)
private val LightOutline = Color(0xFF6D6D6D)
private const val MAX_PROMOCODE_LENGTH = 128
