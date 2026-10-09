package com.tobevpn.app.presentation.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.sqrt

// The app icon (onboarding_logo.xml) drawn with its own paths, so it can be
// animated: the startup splash and the loading indicator use it.

private val ShieldStart = Color(0xFF00DEAC)
private val ShieldEnd = Color(0xFF0FA2ED)
private val ChevronMain = Color(0xFFFEFEFE)
private val ChevronTrail = Color(0xFF7EE1E5)

// The app icon is drawn in its 2048-unit space, framed with 176 units of margin
// on every side (2400 per box side), as on the desktop.
private const val ICON_FRAME = 2400f
private const val ICON_MARGIN = 176f
// Slide distances in icon units: the chevrons start further left.
private const val CHEVRON_MAIN_SLIDE = -960f
private const val CHEVRON_TRAIL_SLIDE = -576f

private val ShieldPath = PathParser().parsePathString("M1020.62,114.132c5.15,-1.058 11.58,1.784 16.72,3.698 48.85,18.194 97.69,36.573 146.49,54.87l291.69,109.375 148.85,55.858c22.61,8.47 62.9,22.22 83.09,32.606 2.6,9.345 4.48,38.251 5.55,49.565 3.71,37.306 6.91,74.662 9.58,112.058 17.95,249.523 25.16,638.098 -73.79,866.698 -86.26,199.28 -277.42,347.51 -460.86,453.51a1593,1593 0,0 1,-121.21 63.59c-12.68,5.92 -26.86,12.9 -39.78,18 -6.35,3.18 -43.234,-16.67 -51.296,-20.53a1684,1684 0,0 1,-85.397 -43.57c-188.935,-103.19 -405.597,-268.34 -491.104,-470.94 -51.14,-121.18 -72.569,-281.31 -79.821,-412.507 -0.532,-12.659 -2.996,-26.079 -3.165,-38.648 -0.443,-33.112 -0.164,-65.467 -3.572,-98.502 -1.354,-13.131 -0.543,-29.009 -0.674,-42.335 -0.223,-22.862 3.079,-46.157 3.116,-69.117 0.491,-24.979 0.45,-49.81 0.826,-74.77 0.162,-10.73 2.43,-22.414 2.758,-33.076 1.24,-40.362 4.397,-80.241 7.64,-120.467l7.994,-90.383c1.108,-11.615 2.528,-39.21 6.222,-48.438 8.059,-6.148 47.52,-19.617 59.299,-24.04l112.175,-42.013z").toPath()
private val ChevronMainPath = PathParser().parsePathString("M824.792,618.376c3.726,0.264 7.433,0.74 11.104,1.427 11.614,2.133 19.989,6.167 29.146,13.557 19.951,16.102 39.586,33.512 59.087,50.203l118.851,101.926 148.78,127.424c15.78,13.506 76.14,62.944 85.27,76.884a63.23,63.23 0,0 1,8.31 48.593c-2.57,11.13 -9.11,22.97 -17.44,30.65 -24.71,22.76 -51.58,44.87 -77.11,66.73l-151.22,129.69 -120.74,103.43c-16.468,14.11 -47.033,42.33 -63.987,52.77 -45.527,23.33 -96.587,-10.76 -93.643,-61.13 1.973,-33.75 33.521,-53.64 57.303,-73.94l64.424,-55.15 241.873,-207.19c-24.35,-23.17 -62.11,-53.521 -88.32,-75.985L865.073,801.251l-52.177,-44.493c-22.363,-18.954 -47.921,-35.776 -51.561,-67.076a61.88,61.88 0,0 1,13.329 -46.088c13.53,-17.028 29.239,-22.964 50.128,-25.218").toPath()
private val ChevronTrailPath = PathParser().parsePathString("M590.122,726.17c12.033,-0.873 21.129,-0.744 31.257,7.116 24.701,19.171 48.739,39.299 72.952,59.101l136.731,111.927 73.019,59.656c20.632,16.838 51.4,34.59 48.577,64.32a40.1,40.1 0,0 1,-10.801 23.66c-8.411,9 -27.246,23.53 -37.496,31.86l-64.466,52.65c-71.099,58.29 -143.662,119.01 -215.462,176.22 -10.466,7.15 -20.336,11.31 -33.338,9.37a43,43 0,0 1,-28.656 -17.84 41.21,41.21 0,0 1,-6.693 -32.41c2.036,-9.78 4.542,-14.39 11.568,-21.09 15.167,-14.48 32.737,-27.95 49.007,-41.25l89.945,-73.63 88.104,-72.05c12.784,-10.46 36.531,-31 49.305,-39.27L657.674,872.397l-56.325,-46.004c-11.302,-9.223 -23.61,-18.454 -33.879,-28.723 -25.091,-25.092 -11.201,-63.934 22.652,-71.5").toPath()
private val ShieldBrush = Brush.linearGradient(
    colors = listOf(ShieldStart, ShieldEnd),
    start = Offset(549.891f, 218.152f),
    end = Offset(1560.96f, 1587.11f),
)

/** Soft radial glow behind the shield, pulsing like the desktop one. */
internal fun DrawScope.drawBrandGlow(color: Color, pulse: Float) {
    val radius = size.minDimension / 2f * sqrt(2f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = color.alpha * pulse),
            0.65f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** The app icon: gradient shield, then the trailing and main chevrons. */
internal fun DrawScope.drawBrandIcon(chevronShift: Float, chevronAlpha: Float) {
    val unit = size.minDimension / ICON_FRAME
    withTransform({
        scale(unit, unit, pivot = Offset.Zero)
        translate(ICON_MARGIN, ICON_MARGIN)
    }) {
        drawPath(ShieldPath, ShieldBrush)
        if (chevronAlpha > 0f) {
            translate(left = CHEVRON_TRAIL_SLIDE * chevronShift) {
                drawPath(ChevronTrailPath, ChevronTrail, alpha = chevronAlpha)
            }
            translate(left = CHEVRON_MAIN_SLIDE * chevronShift) {
                drawPath(ChevronMainPath, ChevronMain, alpha = chevronAlpha)
            }
        }
    }
}
