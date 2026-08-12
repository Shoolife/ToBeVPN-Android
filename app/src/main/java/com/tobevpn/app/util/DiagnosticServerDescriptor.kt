package com.tobevpn.app.util

import com.tobevpn.app.domain.model.Server
import java.security.MessageDigest

/**
 * Correlates server events within an exported journal without exposing the
 * endpoint, port, SNI, UUID, public key, or other connection credentials.
 */
internal fun diagnosticServerDescriptor(server: Server): String {
    val fingerprintSource = buildString {
        append(server.address)
        append(':')
        append(server.port)
        append(':')
        append(server.sni)
        append(':')
        append(server.publicKey)
    }
    val reference = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(fingerprintSource.toByteArray(Charsets.UTF_8))
            .take(SERVER_REFERENCE_BYTES)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }.getOrElse {
        fingerprintSource.hashCode().toUInt().toString(radix = 16)
    }
    return buildString {
        append("server_ref=")
        append(reference)
        append(" country=")
        append(server.country.toDiagnosticToken())
        append(" transport=")
        append(server.network.toDiagnosticToken())
        append(" security=")
        append(server.security.toDiagnosticToken())
        // The uTLS camouflage is a public protocol parameter, not a
        // credential, and it is the field needed to tell one failing REALITY
        // configuration from another. Report the value actually handed to
        // Xray, plus the declared one whenever it had to be overridden.
        append(" fingerprint=")
        append(server.effectiveFingerprint.toDiagnosticToken())
        if (server.isFingerprintRepaired) {
            append(" declared_fingerprint=")
            append(server.fingerprint.toDiagnosticToken())
        }
        append(" source=")
        append(server.source.name.toDiagnosticToken())
        append(" panel_online=")
        append(server.isOnline)
    }
}

private fun String.toDiagnosticToken(): String =
    trim()
        .replace(NON_TOKEN_CHARACTERS, "_")
        .take(MAX_TOKEN_LENGTH)
        .ifBlank { "UNKNOWN" }

private const val SERVER_REFERENCE_BYTES = 6
private const val MAX_TOKEN_LENGTH = 20
private val NON_TOKEN_CHARACTERS = Regex("""[^A-Za-z0-9_-]""")
