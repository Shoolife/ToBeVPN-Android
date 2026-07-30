package com.tobevpn.app.domain.model

import kotlin.math.roundToInt

const val MIN_INTERFACE_SCALE = 0.7f
const val DEFAULT_INTERFACE_SCALE = 1f
const val MAX_INTERFACE_SCALE = 1.3f
const val INTERFACE_SCALE_STEP = 0.1f
const val INTERFACE_SCALE_SLIDER_STEPS = 5
const val DEFAULT_FONT_SCALE = 1f

/**
 * Keeps the persisted interface scale on one of the seven supported values:
 * 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, or 1.3.
 */
fun normalizeInterfaceScale(value: Float): Float {
    if (!value.isFinite()) return DEFAULT_INTERFACE_SCALE

    val step = ((value - MIN_INTERFACE_SCALE) / INTERFACE_SCALE_STEP)
        .roundToInt()
    return (MIN_INTERFACE_SCALE + step * INTERFACE_SCALE_STEP)
        .coerceIn(MIN_INTERFACE_SCALE, MAX_INTERFACE_SCALE)
}

/**
 * Font size uses the same seven-step range as the display scale, but is
 * persisted independently so text can be enlarged without changing controls.
 */
fun normalizeFontScale(value: Float): Float = normalizeInterfaceScale(value)
