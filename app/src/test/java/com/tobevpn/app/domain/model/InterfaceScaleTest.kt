package com.tobevpn.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceScaleTest {

    @Test
    fun `Pixel reference scale stays unchanged`() {
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(1f), 0.0001f)
    }

    @Test
    fun `scale snaps to one tenth increments`() {
        assertEquals(0.8f, normalizeInterfaceScale(0.84f), 0.0001f)
        assertEquals(0.9f, normalizeInterfaceScale(0.86f), 0.0001f)
        assertEquals(1.2f, normalizeInterfaceScale(1.24f), 0.0001f)
        assertEquals(1.3f, normalizeInterfaceScale(1.26f), 0.0001f)
    }

    @Test
    fun `scale is clamped to supported range`() {
        assertEquals(MIN_INTERFACE_SCALE, normalizeInterfaceScale(0.2f), 0.0001f)
        assertEquals(MAX_INTERFACE_SCALE, normalizeInterfaceScale(2f), 0.0001f)
    }

    @Test
    fun `invalid values fall back to Pixel reference scale`() {
        assertEquals(DEFAULT_INTERFACE_SCALE, normalizeInterfaceScale(Float.NaN), 0.0001f)
        assertEquals(
            DEFAULT_INTERFACE_SCALE,
            normalizeInterfaceScale(Float.POSITIVE_INFINITY),
            0.0001f,
        )
    }

    @Test
    fun `font size is independent and uses the same seven steps`() {
        assertEquals(DEFAULT_FONT_SCALE, normalizeFontScale(1f), 0.0001f)
        assertEquals(0.7f, normalizeFontScale(0.69f), 0.0001f)
        assertEquals(1.1f, normalizeFontScale(1.14f), 0.0001f)
        assertEquals(1.3f, normalizeFontScale(1.31f), 0.0001f)
    }
}
