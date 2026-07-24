package com.tobevpn.app.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FixedLayoutTextTest {

    @Test
    fun `wide Pixel layout keeps its original scale`() {
        assertEquals(
            1f,
            compactLayoutScale(smallestScreenWidthDp = 502),
            0.0001f,
        )
        assertEquals(
            1f,
            fixedLayoutTextScaleDivisor(
                smallestScreenWidthDp = 502,
                fontScale = 1.15f,
            ),
            0.0001f,
        )
    }

    @Test
    fun `S23 Plus layout scales the entire interface to eighty percent`() {
        assertEquals(
            0.8f,
            compactLayoutScale(smallestScreenWidthDp = 384),
            0.0001f,
        )
    }

    @Test
    fun `increased font scale is cancelled separately on compact layouts`() {
        assertEquals(
            0.85625f,
            compactLayoutScale(smallestScreenWidthDp = 411),
            0.0001f,
        )
        assertEquals(
            1.3f,
            fixedLayoutTextScaleDivisor(
                smallestScreenWidthDp = 411,
                fontScale = 1.3f,
            ),
            0.0001f,
        )
    }

    @Test
    fun `very narrow whole-interface scale is capped`() {
        assertEquals(
            0.77f,
            compactLayoutScale(smallestScreenWidthDp = 320),
            0.0001f,
        )
    }
}
