package com.tobevpn.app.presentation.components

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

private const val HINTS_PREFS = "tobevpn_one_time_hints"
private const val HINT_DELAY_MS = 900L
private const val HINT_VISIBLE_MS = 7_000L
private val HintBackground = Color(0xFF4B3A78)
private val HintText = Color(0xFFF1ECFF)

/**
 * A hint under a top-bar button, shown once ever per [id], as on PC: it
 * comes after a short pause, stays a few seconds and leaves by itself. Taps
 * pass through it. Place it next to the button inside the same Box.
 */
@Composable
fun OneTimeHint(id: String, text: String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(HINTS_PREFS, Context.MODE_PRIVATE) }
    if (remember { prefs.getBoolean(id, false) }) return
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(id) {
        delay(HINT_DELAY_MS)
        visible.targetState = true
        // Remembered as soon as it shows, so it never comes back.
        prefs.edit().putBoolean(id, true).apply()
        delay(HINT_VISIBLE_MS)
        visible.targetState = false
    }
    if (!visible.currentState && !visible.targetState && visible.isIdle) return
    val yOffset = with(LocalDensity.current) { 46.dp.roundToPx() }
    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(0, yOffset),
        properties = PopupProperties(focusable = false, clippingEnabled = false),
    ) {
        AnimatedVisibility(
            visibleState = visible,
            enter = fadeIn(tween(220)) + slideInVertically(tween(220)) { -it / 6 },
            exit = fadeOut(tween(220)),
        ) {
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 6.dp)) {
                Box(
                    modifier = Modifier
                        .padding(end = 14.dp)
                        .offset(y = 6.dp)
                        .size(12.dp)
                        .rotate(45f)
                        .background(HintBackground, RoundedCornerShape(2.dp)),
                )
                Text(
                    text = text,
                    color = HintText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .widthIn(max = 240.dp)
                        .shadow(8.dp, RoundedCornerShape(14.dp))
                        .background(HintBackground, RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}
