package com.tobevpn.app.presentation.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tobevpn.app.R
import com.tobevpn.app.presentation.components.VerticalScrollEdgeArrow
import com.tobevpn.app.presentation.components.fixedLayoutTextStyle
import com.tobevpn.app.presentation.components.verticalFadingEdges
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val supportLink = stringResource(R.string.block_appeal_link)
    val openSupport: () -> Unit = {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(supportLink)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                },
            )
        }
    }

    // Question → answer string-resource pairs, rendered as an expandable list.
    val faq = listOf(
        R.string.faq_q_connect to R.string.faq_a_connect,
        R.string.faq_q_slow to R.string.faq_a_slow,
        R.string.faq_q_server to R.string.faq_a_server,
        R.string.faq_q_pay to R.string.faq_a_pay,
        R.string.faq_q_activate to R.string.faq_a_activate,
        R.string.faq_q_discount to R.string.faq_a_discount,
        R.string.faq_q_devices to R.string.faq_a_devices,
        R.string.faq_q_app_filter to R.string.faq_a_app_filter,
        R.string.faq_q_referrals to R.string.faq_a_referrals,
        R.string.faq_q_updates to R.string.faq_a_updates,
        R.string.faq_q_display_scale to R.string.faq_a_display_scale,
        R.string.faq_q_privacy to R.string.faq_a_privacy,
        R.string.faq_q_support_details to R.string.faq_a_support_details,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_support),
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Fixed intro — stays put above the scrolling list.
            Text(
                text = stringResource(R.string.support_faq_intro),
                style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Scrollable FAQ list — takes the remaining height above the pinned
            // button so the list scrolls while the button stays visible. The
            // top/bottom edges fade into the background and show ↑/↓ arrows when
            // there's more to scroll, mirroring the tariff tabs in Subscription.
            val scrollState = rememberScrollState()
            val topFadeAlpha by animateFloatAsState(
                targetValue = if (scrollState.value > 0) 1f else 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "faqTopFade",
            )
            val bottomFadeAlpha by animateFloatAsState(
                targetValue = if (scrollState.value < scrollState.maxValue) 1f else 0f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
                label = "faqBottomFade",
            )
            // Viewport metrics for centring an expanded item. When a card opens,
            // we scroll so its middle lines up with the viewport middle (as far
            // as the ends allow) — the same "pull the touched one to the centre"
            // behaviour as the tariff tabs, just vertical.
            val scope = rememberCoroutineScope()
            var viewportTopPx by remember { mutableFloatStateOf(0f) }
            var viewportHeightPx by remember { mutableIntStateOf(0) }
            val centerOnExpand: (Float, Int) -> Unit = { itemTopWindowPx, itemHeightPx ->
                if (viewportHeightPx > 0) {
                    val itemOffsetInContent =
                        (itemTopWindowPx - viewportTopPx) + scrollState.value
                    val target = (itemOffsetInContent + itemHeightPx / 2f - viewportHeightPx / 2f)
                        .toInt()
                        .coerceIn(0, scrollState.maxValue)
                    scope.launch {
                        scrollState.animateScrollTo(
                            value = target,
                            animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        viewportTopPx = it.positionInWindow().y
                        viewportHeightPx = it.size.height
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalFadingEdges(
                            topAlpha = topFadeAlpha,
                            bottomAlpha = bottomFadeAlpha,
                            fadeHeight = 38.dp,
                        ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp),
                    ) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            faq.forEach { (questionRes, answerRes) ->
                                FaqItem(
                                    questionRes = questionRes,
                                    answerRes = answerRes,
                                    onExpanded = centerOnExpand,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                VerticalScrollEdgeArrow(
                    alpha = topFadeAlpha,
                    isTop = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp),
                )
                VerticalScrollEdgeArrow(
                    alpha = bottomFadeAlpha,
                    isTop = false,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 2.dp),
                )
            }

            // Pinned "message support" button — always visible at the bottom,
            // on the screen background so scrolled cards don't show through.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                Button(
                    onClick = openSupport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = if (isSystemInDarkTheme()) {
                        ButtonDefaults.buttonColors()
                    } else {
                        ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F3F3F),
                            contentColor = Color.White,
                        )
                    },
                ) {
                    Icon(
                        imageVector = Icons.Filled.SupportAgent,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.support_contact_button),
                        style = fixedLayoutTextStyle(MaterialTheme.typography.labelLarge),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// A single expandable FAQ entry. Tapping the card toggles the answer with a
// smooth height animation; the chevron rotates to point up while open. On open
// it asks the list to centre it (onExpanded), reporting its window position and
// current height.
@Composable
private fun FaqItem(
    questionRes: Int,
    answerRes: Int,
    onExpanded: (itemTopWindowPx: Float, itemHeightPx: Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var itemTopWindowPx by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableIntStateOf(0) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "faqChevron",
    )
    // Wait for the answer to lay out (so itemHeightPx reflects the open card),
    // then centre it.
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(240)
            onExpanded(itemTopWindowPx, itemHeightPx)
        }
    }
    val titleColor = if (isSystemInDarkTheme()) MaterialTheme.colorScheme.onSurface else Color.Black
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned {
                itemTopWindowPx = it.positionInWindow().y
                itemHeightPx = it.size.height
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { expanded = !expanded },
            ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .animateContentSize(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(questionRes),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.titleSmall),
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = stringResource(answerRes),
                    style = fixedLayoutTextStyle(MaterialTheme.typography.bodyMedium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
