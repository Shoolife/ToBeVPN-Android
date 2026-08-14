package com.tobevpn.app.domain.model

import java.util.Locale

/**
 * Bounded browser-fingerprint order for REALITY startup validation.
 *
 * Keep the profile-declared value first, then try exactly one alternate
 * browser fingerprint. Chrome profiles fall back to Firefox; profiles already
 * declaring Firefox fall back to Chrome. A custom primary still receives the
 * requested Firefox fallback. This keeps startup bounded to one extra reload.
 */
internal object RealityFingerprintPolicy {
    const val CHROME = "chrome"
    const val FIREFOX = "firefox"

    fun candidates(server: Server): List<String> {
        if (!server.security.trim().equals("reality", ignoreCase = true)) return emptyList()
        val primary = canonicalBrowserName(
            server.effectiveFingerprint.trim().ifBlank { CHROME },
        )
        val fallback = if (normalize(primary) == FIREFOX) CHROME else FIREFOX
        return listOf(primary, fallback)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(::normalize)
    }

    fun primaryCandidate(server: Server): String? = candidates(server).firstOrNull()

    fun nextCandidate(server: Server, attempted: Set<String>): String? {
        val normalizedAttempts = attempted.mapTo(hashSetOf(), ::normalize)
        return candidates(server).firstOrNull { normalize(it) !in normalizedAttempts }
    }

    /** Resolve the exact value handed to Xray; non-REALITY profiles ignore the override. */
    fun fingerprintForConfig(server: Server, realityOverride: String?): String {
        if (!server.security.trim().equals("reality", ignoreCase = true)) {
            return server.effectiveFingerprint
        }
        val requested = realityOverride?.trim()?.takeIf(String::isNotEmpty)
            ?: server.effectiveFingerprint
        return canonicalBrowserName(requested)
    }

    fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)

    private fun canonicalBrowserName(value: String): String = when (normalize(value)) {
        CHROME -> CHROME
        FIREFOX -> FIREFOX
        else -> value.trim()
    }
}
