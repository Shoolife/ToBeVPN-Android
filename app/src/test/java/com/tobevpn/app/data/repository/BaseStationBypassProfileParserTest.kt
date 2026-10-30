package com.tobevpn.app.data.repository

import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerProtocol
import com.tobevpn.app.domain.model.ServerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseStationBypassProfileParserTest {

    @Test
    fun `keeps hysteria2 CIDR entry with encoded flag and display name`() {
        // Same shape as the missing subscription entry; endpoint and auth are fixtures.
        val result = BaseStationBypassProfileParser.parseWithFilterSummary(
            "hysteria2://550e8400-e29b-41d4-a716-446655440000@203.0.113.5:443" +
                "?sni=hy2.example.com&fp=firefox" +
                "#%F0%9F%87%B5%F0%9F%87%B1%20Poland%20%7C%20%F0%9F%8C%90%20%5B%2ACIDR%5D",
        )

        val server = result.servers.single()
        assertEquals(ServerProtocol.HYSTERIA2, server.protocol)
        assertEquals(ServerSource.BASE_STATION_BYPASS, server.source)
        assertEquals("203.0.113.5", server.address)
        assertEquals("hy2.example.com", server.sni)
        assertEquals("Poland | [*CIDR]", server.name)
        assertEquals("PL", server.country)
        assertTrue(server.id.startsWith("bs:"))
        assertTrue(BaseStationBypassProfileParser.isSupportedProfile(server))
        assertEquals(
            "links=1 unparsable=0 sentinel=0 rejected=0 duplicates=0 kept=1",
            result.filterSummary,
        )
    }

    @Test
    fun `mixed subscription keeps vless and hysteria2 and deduplicates hy2 alias`() {
        val result = BaseStationBypassProfileParser.parseWithFilterSummary(
            listOf(
                "vless://pass@node.example:443?security=tls#VLESS",
                "hysteria2://pass@node.example:443#Hysteria2",
                "hy2://pass@node.example:443#Alias",
                "HYSTERIA2://other@other.example:443#Other",
                "hysteria2://broken",
                "hy2://@node.example:443",
            ).joinToString("\n"),
        )

        assertEquals(listOf("VLESS", "Hysteria2", "Other"), result.servers.map(Server::name))
        assertEquals(
            listOf(ServerProtocol.VLESS, ServerProtocol.HYSTERIA2, ServerProtocol.HYSTERIA2),
            result.servers.map(Server::protocol),
        )
        assertEquals(3, result.servers.map(Server::id).distinct().size)
        assertEquals(
            "links=6 unparsable=2 sentinel=0 rejected=0 duplicates=1 kept=3",
            result.filterSummary,
        )
    }

    @Test
    fun `hysteria2 config variants get separate ids`() {
        val link = "hysteria2://pass@node.example:443?sni=hy2.example"
        val servers = BaseStationBypassProfileParser.parse(
            listOf(
                link,
                "$link&obfs=salamander&obfs-password=secret",
                "$link&pinSHA256=${"ab".repeat(32)}",
                "$link&mport=20000-30000",
                "$link&ech=fixture-ech-config",
                "$link&alpn=h3,h2",
            ).joinToString("\n"),
        )

        assertEquals(6, servers.size)
        assertEquals(6, servers.map(Server::id).distinct().size)
        assertTrue(servers.all(BaseStationBypassProfileParser::hasCurrentStableId))
    }

    @Test
    fun `hysteria2 profile survives bypass cache round trip with complete vpn config`() {
        val server = BaseStationBypassProfileParser.parse(
            "hy2://user:pass@node.example:443,20000-30000?sni=hy2.example&alpn=h3,h2" +
                "&obfs=salamander&obfs-password=secret&pinSHA256=${"ab".repeat(32)}" +
                "&ech=fixture-ech-config#Poland",
        ).single()

        val entity = server.toBaseStationBypassEntity(sortOrder = 7)
        val restored = entity.toDomain()

        assertEquals(7, entity.sortOrder)
        assertEquals(ServerProtocol.HYSTERIA2, restored.protocol)
        assertEquals(server, restored)
        assertTrue(BaseStationBypassProfileParser.isSupportedProfile(restored))
        assertTrue(BaseStationBypassProfileParser.hasCurrentStableId(restored))
    }

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
    fun `xhttp null extra is accepted with the same identity as omitted extra`() {
        val link = "vless://same-user@node.example:443?type=xhttp&security=tls"
        val withNullExtra = BaseStationBypassProfileParser.parse(
            "$link&extra=null#Node",
        ).single()
        val withoutExtra = BaseStationBypassProfileParser.parse("$link#Node").single()

        assertEquals("", withNullExtra.extra)
        assertEquals(withoutExtra.id, withNullExtra.id)
        assertEquals(withoutExtra.probeKey, withNullExtra.probeKey)
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

    @Test
    fun `filters transports that the generated xray config cannot support`() {
        val servers = BaseStationBypassProfileParser.parse(
            listOf(
                "vless://tcp@one.example:443?type=tcp#TCP",
                "vless://grpc@two.example:443?type=grpc&serviceName=vpn#GRPC",
                "vless://grpc-invalid@invalid.example:443?type=grpc&mode=invalid#GRPC invalid",
                "vless://quic@three.example:443?type=quic#QUIC",
                "vless://ws@four.example:443?type=WS&host=cdn.example#WS",
                "vless://ws-reality@five.example:443?type=ws&security=reality#WS Reality",
                "vless://tcp-header@six.example:443?type=tcp&headerType=srtp#TCP SRTP",
                "vless://xhttp-mode@seven.example:443?type=xhttp&mode=invalid#XHTTP mode",
                "vless://xhttp-extra@eight.example:443?type=xhttp&extra=not-json#XHTTP extra",
                "vless://xhttp-array@array.example:443?type=xhttp&extra=%5B%5D#XHTTP array",
                "vless://xhttp-string@string.example:443?type=xhttp&extra=%22null%22#XHTTP string",
                "vless://reality-http@nine.example:443?type=tcp&security=reality" +
                    "&headerType=http#Reality HTTP",
            ).joinToString("\n"),
        )

        assertEquals(listOf("TCP", "GRPC", "WS"), servers.map { it.name })
        assertEquals(listOf("tcp", "grpc", "ws"), servers.map { it.network })
    }

    @Test
    fun `tls12-only android fingerprint is kept and repaired instead of dropped`() {
        val servers = BaseStationBypassProfileParser.parse(
            listOf(
                "vless://bad@one.example:443?type=tcp&security=reality&fp=android#Bad Reality",
                "vless://explicit@two.example:443?type=xhttp&security=reality" +
                    "&fp=HelloAndroid_11_OkHttp#Explicit Android",
                "vless://good@three.example:443?type=tcp&security=reality&fp=chrome#Good Reality",
                "vless://tls@four.example:443?type=ws&security=tls&fp=android#Android TLS",
            ).joinToString("\n"),
        )

        // All four survive: an unusable camouflage is repaired in VpnConfig,
        // dropping the entry would only remove a working server from the list.
        assertEquals(
            listOf("Bad Reality", "Explicit Android", "Good Reality", "Android TLS"),
            servers.map(Server::name),
        )
    }

    @Test
    fun `unused grpc service name does not change a supported profile id`() {
        val first = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=tcp&serviceName=one#Node",
        ).single()
        val second = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=tcp&serviceName=two#Node",
        ).single()

        assertEquals(first.id, second.id)
    }

    @Test
    fun `grpc service name and mode participate in exact profile id`() {
        val first = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=grpc&serviceName=one&mode=gun#Node",
        ).single()
        val second = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=grpc&serviceName=two&mode=multi#Node",
        ).single()

        assertNotEquals(first.id, second.id)
    }

    @Test
    fun `normalizes raw transport alias to tcp`() {
        val server = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=RAW&security=reality&fp=chrome#Node",
        ).single()

        assertEquals("tcp", server.network)
    }

    @Test
    fun `extended transport settings participate in the exact profile id`() {
        val first = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=ws&host=one.example&alpn=h2#Node",
        ).single()
        val second = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=ws&host=two.example&alpn=h2#Node",
        ).single()

        assertNotEquals(first.id, second.id)
        assertEquals(
            BaseStationBypassProfileParser.legacyStableId(first),
            BaseStationBypassProfileParser.legacyStableId(second),
        )
    }

    @Test
    fun `legacy persisted bypass id migrates to full transport profile id`() {
        val server = BaseStationBypassProfileParser.parse(
            "vless://same@node.example:443?type=xhttp&host=cdn.example" +
                "&extra=%7B%22noGRPCHeader%22%3Atrue%7D#Node",
        ).single()
        val legacyId = BaseStationBypassProfileParser.legacyStableId(server)

        val migrated = resolveBaseStationBypassSelectionMigration(
            servers = listOf(server),
            selectedId = legacyId,
            selectedKey = "bs:node",
        )

        assertEquals(server, migrated)
        assertNotEquals(legacyId, server.id)
    }

    @Test
    fun `fresh profile matches cached v19 id during in-flight connection`() {
        val refreshed = BaseStationBypassProfileParser.parse(
            "vless://user@example.com:443?security=tls&type=ws" +
                "&path=%2Fvpn&host=cdn.example&alpn=http%2F1.1#Node",
        ).single()
        val cachedId = BaseStationBypassProfileParser.legacyStableId(refreshed)

        assertTrue(matchesBaseStationBypassSelectionId(refreshed, cachedId))
        assertTrue(matchesBaseStationBypassSelectionId(refreshed, refreshed.id))
        assertTrue(BaseStationBypassProfileParser.hasCurrentStableId(refreshed))
        assertFalse(
            BaseStationBypassProfileParser.hasCurrentStableId(refreshed.copy(id = cachedId)),
        )
        assertFalse(matchesBaseStationBypassSelectionId(refreshed, "bs:other"))
    }
}
