package com.tobevpn.app.data.repository

import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseStationBypassProfileParserTest {

    @Test
    fun `parses direct vless lines and preserves unencoded display name`() {
        val servers = BaseStationBypassProfileParser.parse(
            """
            # beta profile
            vless://user-one@one.example:443?type=xhttp&security=reality#Обход БС 1

            ignored text
            vless://user-two@two.example:8443?type=ws&security=tls#Обход%20БС%202
            """.trimIndent(),
        )

        assertEquals(2, servers.size)
        assertEquals("Обход БС 1", servers[0].name)
        assertEquals("Обход БС 2", servers[1].name)
        assertTrue(servers.all { it.source == ServerSource.BASE_STATION_BYPASS })
        assertTrue(servers.all { it.id.startsWith("bs:") })
    }

    @Test
    fun `deduplicates identical configurations and ignores malformed links`() {
        val link = "vless://same-user@node.example:443?type=tcp#Node"

        val servers = BaseStationBypassProfileParser.parse(
            listOf(link, link, "vless://broken", "https://example.org").joinToString("\n"),
        )

        assertEquals(1, servers.size)
    }

    @Test
    fun `stable id changes with vpn configuration and does not expose uuid`() {
        val first = BaseStationBypassProfileParser.parse(
            "vless://private-user-one@node.example:443?type=tcp#Node",
        ).single()
        val second = BaseStationBypassProfileParser.parse(
            "vless://private-user-two@node.example:443?type=tcp#Node",
        ).single()

        assertNotEquals(first.id, second.id)
        assertFalse(first.id.contains("private-user-one"))
        assertEquals(35, first.id.length)
    }

    @Test
    fun `sanitizes remote display names that could distort or reorder the UI`() {
        val excessiveMarks = "A" + "\u0301".repeat(120)
        val bidiControl = "\u202EResreverS"
        val longName = "x".repeat(120)

        val servers = BaseStationBypassProfileParser.parse(
            listOf(
                "vless://one@one.example:443?type=tcp#$excessiveMarks",
                "vless://two@two.example:443?type=tcp#$bidiControl",
                "vless://three@three.example:443?type=tcp#$longName",
            ).joinToString("\n"),
        )

        assertEquals("Á", servers[0].name)
        assertFalse(servers[1].name.contains('\u202E'))
        assertEquals("ResreverS", servers[1].name)
        assertEquals(80, servers[2].name.codePointCount(0, servers[2].name.length))
    }

    @Test
    fun `removes decorative symbols that can increase a single line height`() {
        val server = BaseStationBypassProfileParser.parse(
            "vless://one@one.example:443?type=tcp#✣🛸🌪 | [*CIDR]",
        ).single()

        assertEquals("[*CIDR]", server.name)
    }

    @Test
    fun `preserves country from a flag before sanitizing the display name`() {
        val server = BaseStationBypassProfileParser.parse(
            "vless://one@one.example:443?type=tcp#🇩🇪 Germany | 🌐 [*CIDR]",
        ).single()

        assertEquals("DE", server.country)
        assertEquals("Germany | [*CIDR]", server.name)
    }

    @Test
    fun `uses first country flag for an anycast entry with several flags`() {
        val server = BaseStationBypassProfileParser.parse(
            "vless://one@one.example:443?type=tcp#🌐 Anycast-IP | 🇬🇧 🇷🇺 [*CIDR]",
        ).single()

        assertEquals("GB", server.country)
    }

    @Test
    fun `uses international flag code when profile gives no country`() {
        val server = BaseStationBypassProfileParser.parse(
            "vless://one@one.example:443?type=tcp#[*CIDR]",
        ).single()

        assertEquals("UN", server.country)
    }
}
