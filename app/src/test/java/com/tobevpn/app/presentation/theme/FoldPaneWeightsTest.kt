package com.tobevpn.app.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FoldPaneWeightsTest {
    @Test
    fun centeredHingeRespectsOuterPadding() {
        val weights = foldPaneWeights(
            windowWidthDp = 1000,
            horizontalPaddingDp = 20f,
            foldStartFraction = 0.49f,
            foldEndFraction = 0.51f,
        )!!

        assertEquals(470f, weights.left, 0.001f)
        assertEquals(20f, weights.hinge, 0.001f)
        assertEquals(470f, weights.right, 0.001f)
    }

    @Test
    fun offCenterHingeKeepsUnequalPaneWidths() {
        val weights = foldPaneWeights(
            windowWidthDp = 1000,
            horizontalPaddingDp = 20f,
            foldStartFraction = 0.4f,
            foldEndFraction = 0.42f,
        )!!

        assertEquals(380f, weights.left, 0.001f)
        assertEquals(20f, weights.hinge, 0.001f)
        assertEquals(560f, weights.right, 0.001f)
    }

    @Test
    fun unusablyNarrowPaneRejectsHingeLayout() {
        assertNull(foldPaneWeights(800, 20f, 0.2f, 0.22f))
        assertNull(foldPaneWeights(800, 20f, 0.8f, 0.82f))
    }
}
