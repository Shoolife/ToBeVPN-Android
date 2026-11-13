package com.tobevpn.app.presentation.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val SHEET_MOTION_MS = 420
private const val SCRIM_ALPHA = 0.45f
/** Dragged down by more than this share of its height, the sheet closes. */
private const val DISMISS_FRACTION = 0.25f

/** Opens and closes an [InLayoutBottomSheet] from code. */
@Stable
class InLayoutSheetState internal constructor(private val scope: kotlinx.coroutines.CoroutineScope) {
    /** 0 = fully shown, 1 = fully below the screen. */
    internal val offset = Animatable(1f)
    internal var height by mutableFloatStateOf(0f)

    internal suspend fun show() = offset.animateTo(0f, tween(SHEET_MOTION_MS, easing = FastOutSlowInEasing))

    /** Slides the sheet away; returns when it is gone. */
    suspend fun hide() = offset.animateTo(1f, tween(SHEET_MOTION_MS, easing = FastOutSlowInEasing))

    internal fun hideThen(after: () -> Unit) {
        scope.launch {
            hide()
            after()
        }
    }
}

@Composable
fun rememberInLayoutSheetState(): InLayoutSheetState {
    val scope = rememberCoroutineScope()
    return remember { InLayoutSheetState(scope) }
}

/**
 * A bottom sheet drawn inside the screen rather than in a window of its
 * own, so it moves together with the screen in navigation transitions and
 * is simply there again on return. It slides in and out slowly and evenly,
 * over a fading scrim; Back, a tap on the scrim or a drag down closes it.
 * Shown again after a return (state restored), it does not animate in.
 */
@Composable
fun InLayoutBottomSheet(
    state: InLayoutSheetState,
    onDismiss: () -> Unit,
    shape: Shape,
    containerColor: Color,
    modifier: Modifier = Modifier,
    dragHandle: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var shownBefore by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (shownBefore) {
            state.offset.snapTo(0f)
        } else {
            shownBefore = true
            state.show()
        }
    }
    val close = { state.hideThen(onDismiss) }
    BackHandler(onBack = close)
    val scope = rememberCoroutineScope()
    val dragState = rememberDraggableState { delta ->
        val height = state.height
        if (height > 0f) {
            scope.launch { state.offset.snapTo((state.offset.value + delta / height).coerceIn(0f, 1f)) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA * (1f - state.offset.value)))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = close,
                ),
        )
        Surface(
            shape = shape,
            color = containerColor,
            modifier = modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { state.height = it.height.toFloat() }
                .offset { IntOffset(0, (state.offset.value * state.height).roundToInt()) }
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val fast = velocity > 1500f
                        if (fast || state.offset.value > DISMISS_FRACTION) {
                            state.hideThen(onDismiss)
                        } else {
                            state.show()
                        }
                    },
                ),
        ) {
            Column {
                dragHandle()
                content()
            }
        }
    }
}
