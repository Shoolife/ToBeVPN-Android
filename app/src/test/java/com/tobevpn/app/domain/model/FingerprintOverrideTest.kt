package com.tobevpn.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintOverrideTest {

    private val reality = Server(
        id = "r", name = "r", address = "r.example", port = 443, uuid = "u",
        security = "reality", fingerprint = "firefox", source = ServerSource.BASE_STATION_BYPASS,
    )
    private val tls = reality.copy(id = "t", security = "tls", fingerprint = "chrome")

    @Test
    fun emptyKeepsProfiles() {
        val out = FingerprintOverride.apply(listOf(reality, tls), FingerprintOverride.FROM_PROFILE)
        assertEquals(listOf("firefox", "chrome"), out.map { it.fingerprint })
    }

    @Test
    fun compatibleValueGoesEverywhere() {
        val out = FingerprintOverride.apply(listOf(reality, tls), "safari")
        assertEquals(listOf("safari", "safari"), out.map { it.fingerprint })
    }

    @Test
    fun oldTemplateSkipsRealityServers() {
        val out = FingerprintOverride.apply(listOf(reality, tls), "qq")
        assertEquals(listOf("firefox", "qq"), out.map { it.fingerprint })
    }

    @Test
    fun unknownValuesAreDropped() {
        assertEquals("", FingerprintOverride.normalize("netscape"))
        assertEquals("ios", FingerprintOverride.normalize(" IOS "))
    }
}
