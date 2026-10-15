package com.tobevpn.app.data.remote.dto

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelV3CompatibilityTest {
    private val gson = Gson()

    @Test
    fun v2PanelUserKeepsUuid() {
        val user = gson.fromJson(
            """{"id":42,"uuid":"legacy-user","short_uuid":"own-key","username":"owner","subscription_url":"https://example.invalid/sub/own-key"}""",
            PanelUserDto::class.java,
        )
        assertEquals("legacy-user", user.uuid)
        assertEquals(42L, user.id)
    }

    @Test
    fun v3PanelUserCanBeCopiedWithoutUserUuidOrCredentialFields() {
        val user = gson.fromJson(
            """{"id":3145728000,"short_uuid":"own-key","username":"owner","subscription_url":"https://example.invalid/sub/own-key"}""",
            PanelUserDto::class.java,
        ).copy()
        assertNull(user.uuid)
        assertNull(user.vlessUuid)
        assertEquals(3145728000L, user.id)
        assertEquals("own-key", user.shortUuid)
    }

    @Test
    fun v3SessionReadsNumericIdWithExplicitNullUuid() {
        val session = gson.fromJson(
            """{"access_token":"test-access","refresh_token":"test-refresh","expires_in":60,"refresh_expires_in":120,"device_id":"test-device","panel_user_id":3145728000,"panel_user_uuid":null,"short_uuid":"own-key"}""",
            SessionTokensDto::class.java,
        )
        assertEquals(3145728000L, session.panelUserId)
        assertNull(session.panelUserUuid)
    }

    @Test
    fun ensureUserWorksWithoutUuid() {
        val user = gson.fromJson(
            """{"panel_user_id":42,"short_uuid":"own-key","traffic_limit_bytes":100,"traffic_used_bytes":0}""",
            EnsureUserResponseDto::class.java,
        ).copy()
        assertEquals(42L, user.panelUserId)
        assertNull(user.panelUserUuid)
    }

    @Test
    fun completedAuthAndPairingReadNumericIdentity() {
        val response = """{"status":"completed","telegram_id":7,"short_uuid":"own-key","panel_user_id":42}"""
        assertEquals(42L, gson.fromJson(response, AuthStatusDto::class.java).panelUserId)
        assertEquals(42L, gson.fromJson(response, TvPairStatusDto::class.java).panelUserId)
    }

    @Test
    fun numericAuthRequestDoesNotInventUuid() {
        val body = JsonParser.parseString(gson.toJson(AuthRequestDto(panelUserId = 42L))).asJsonObject
        assertEquals(42L, body["panel_user_id"].asLong)
        assertFalse(body.has("panel_user_uuid"))
    }

    @Test
    fun emailRequestUsesServerSessionWithoutClientIdentity() {
        val body = JsonParser.parseString(gson.toJson(SaveEmailRequestDto(email = "test@example.org"))).asJsonObject
        assertEquals("test@example.org", body["email"].asString)
        assertEquals(1, body.size())
    }

    @Test
    fun explicitNumericEmailIdentityCanStillBeSerialized() {
        val body = JsonParser.parseString(gson.toJson(
            SaveEmailRequestDto(panelUserId = 3145728000L, email = "test@example.org"),
        )).asJsonObject
        assertEquals(3145728000L, body["panel_user_id"].asLong)
        assertFalse(body.has("panel_user_uuid"))
    }

    @Test
    fun upgradeOfSameAccountKeepsExistingAlias() {
        assertEquals("legacy-user", mergePanelUserUuid("legacy-user", "own-key", null, "own-key"))
    }

    @Test
    fun missingIdentityFieldsOnRefreshKeepExistingAccount() {
        assertEquals("legacy-user", mergePanelUserUuid("legacy-user", "own-key", null, null))
    }

    @Test
    fun bindingNewV3AccountClearsAnonymousUuid() {
        assertNull(mergePanelUserUuid("anonymous-user", "anon-key", null, "own-key"))
    }

    @Test
    fun bindingV2AccountUsesItsRealUuid() {
        assertEquals("primary-user", mergePanelUserUuid("anonymous-user", "anon-key", "primary-user", "own-key"))
    }

    @Test
    fun freshV3AccountHasNoFakeUuid() {
        assertNull(mergePanelUserUuid(null, null, null, "own-key"))
    }

    @Test
    fun nullUuidDoesNotMatchEveryV3User() {
        assertFalse(matchesPreferredPanelUser(null, "other-key", null, "own-key"))
    }

    @Test
    fun preferredShortUuidSelectsV3User() {
        assertTrue(matchesPreferredPanelUser(null, "own-key", null, "own-key"))
    }

    @Test
    fun preferredLegacyUuidStillMatchesV2User() {
        assertTrue(matchesPreferredPanelUser("legacy-user", "own-key", "legacy-user", null))
    }

    @Test
    fun absentOrBlankPreferencesAreNotAccountMatches() {
        assertFalse(matchesPreferredPanelUser(null, null, null, null))
        assertFalse(matchesPreferredPanelUser("", "", "", ""))
    }
}
