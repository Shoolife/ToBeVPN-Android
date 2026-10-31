package com.tobevpn.app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackDeviceRefTest {

    // The PHP proxy's decoding: 8-4-4-4-12 dashes back in, case kept.
    private fun decode(ref: String): String {
        val hex = ref.removePrefix("u1.")
        return listOf(hex.substring(0, 8), hex.substring(8, 12), hex.substring(12, 16),
            hex.substring(16, 20), hex.substring(20)).joinToString("-")
    }

    @Test
    fun `encodes uuid v4 and v3 reversibly keeping case`() {
        listOf(
            "550e8400-e29b-41d4-a716-446655440000",
            "69abca52-2987-5c04-a44a-0123456789ab",
            "6FA459EA-EE8A-3CA4-894E-DB77E160355E",
        ).forEach { id ->
            val ref = encodeFallbackDeviceRef(id)!!
            assertEquals("u1." + id.replace("-", ""), ref)
            assertEquals(35, ref.length)
            assertEquals(id, decode(ref))
        }
    }

    @Test
    fun `rejects ids that are not uuids`() {
        listOf("", "abc", "550e8400e29b41d4a716446655440000", "550e8400-e29b-41d4-a716-44665544000g",
            " 550e8400-e29b-41d4-a716-446655440000").forEach { assertNull(encodeFallbackDeviceRef(it)) }
    }

    @Test
    fun `fallback request carries only x-device-ref and keeps the primary untouched`() {
        val id = "550e8400-e29b-41d4-a716-446655440000"
        val primary = Request.Builder()
            .url("https://primary.example/KEY")
            .header("x-hwid", id)
            .header("x-device-os", "Android")
            .header("x-ver-os", "16")
            .header("x-device-model", "Pixel")
            .header("User-Agent", "ToBeVPN/1.0")
            .build()
        val fallback = asFallbackRequest(
            primary,
            "https://cdn.example/files/sub/KEY".toHttpUrl(),
            encodeFallbackDeviceRef(id)!!,
        )

        assertEquals("https://cdn.example/files/sub/KEY", fallback.url.toString())
        assertEquals(emptyList<String>(), fallback.headers("x-hwid"))
        assertEquals(listOf("u1.550e8400e29b41d4a716446655440000"), fallback.headers("x-device-ref"))
        listOf("x-device-os", "x-ver-os", "x-device-model", "User-Agent").forEach {
            assertEquals(primary.header(it), fallback.header(it))
        }
        // The primary request (used again for a retry) still has x-hwid only.
        assertEquals(listOf(id), primary.headers("x-hwid"))
        assertEquals(emptyList<String>(), primary.headers("x-device-ref"))
    }

    @Test
    fun `a stale x-device-ref is replaced, never duplicated`() {
        val source = Request.Builder().url("https://a.example/")
            .header("x-device-ref", "u1.old").header("x-hwid", "x").build()
        val fallback = asFallbackRequest(source, "https://b.example/".toHttpUrl(), "u1.new")
        assertEquals(listOf("u1.new"), fallback.headers("x-device-ref"))
        assertEquals(emptyList<String>(), fallback.headers("x-hwid"))
    }

    @Test
    fun `files sub template builds the key once`() {
        assertEquals(
            "https://nabicidepeenup.begetcdn.cloud/files/sub/AbC_12",
            buildSubscriptionUrlByKey("https://nabicidepeenup.begetcdn.cloud/files/sub/{key}", "AbC_12").toString(),
        )
    }

    @Test
    fun `every allowed extension builds exactly key dot ext`() {
        val template = "https://cdn.example/files/sub/{key}.{ext}"
        assertEquals(10, FALLBACK_SUBS_EXTENSIONS.size)
        FALLBACK_SUBS_EXTENSIONS.forEach { ext ->
            val url = buildSubscriptionUrlByKey(resolveFallbackExtension(template) { ext }, "AbC_12")!!
            assertEquals("https://cdn.example/files/sub/AbC_12.$ext", url.toString())
        }
    }

    @Test
    fun `random extension comes from the list and php is never offered`() {
        val template = "https://cdn.example/files/sub/{key}.{ext}"
        repeat(200) {
            val ext = resolveFallbackExtension(template).substringAfterLast("/{key}.")
            assertTrue(ext in FALLBACK_SUBS_EXTENSIONS)
        }
        assertFalse("php" in FALLBACK_SUBS_EXTENSIONS)
    }

    @Test
    fun `template without ext is left as is`() {
        assertEquals(
            "https://cdn.example/files/sub/{key}",
            resolveFallbackExtension("https://cdn.example/files/sub/{key}") { "svg" },
        )
    }
}
