package com.tobevpn.app.util

import java.time.LocalDate

/**
 * Defence-in-depth filtering for the user-exportable diagnostic journal.
 *
 * Call sites are required to submit only generic operational events. These
 * filters additionally strip common secret and identity formats if a future
 * call site accidentally includes one.
 */
internal object DiagnosticLogPolicy {
    private const val FILE_PREFIX = "ToBeVPN-diagnostic-"
    private const val FILE_SUFFIX = ".log"
    // Rich VPN state snapshots and four-attempt probe histories can exceed the
    // old 600-character ceiling. Keep the entry bounded, but large enough that
    // the decisive fields at the end are not silently discarded.
    private const val MAX_MESSAGE_LENGTH = 1_200

    private val lineBreaks = Regex("""[\r\n\t]+""")
    private val urls = Regex(
        """(?i)\b(?:https?|vless|vmess|trojan|ss|socks5?|tobevpn)://[^\s]+""",
    )
    private val authorizationValues = Regex(
        """(?i)\b(?:authorization|bearer|access[_ -]?token|refresh[_ -]?token)""" +
            """\s*[:=]?\s*[A-Za-z0-9._~+/=-]{8,}""",
    )
    private val emails = Regex(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""",
    )
    private val uuids = Regex(
        """(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5]?[0-9a-f]{3}-""" +
            """[89ab]?[0-9a-f]{3}-[0-9a-f]{12}\b""",
    )
    private val ipv4Addresses = Regex(
        """\b(?:\d{1,3}\.){3}\d{1,3}\b""",
    )
    private val ipv6Addresses = Regex(
        """(?i)(?<![0-9a-f:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f]{0,4}(?![0-9a-f:])""",
    )
    private val longNumericIds = Regex("""\b\d{7,}\b""")
    private val opaqueSecrets = Regex("""\b[A-Za-z0-9_-]{32,}\b""")
    private val invalidTagCharacters = Regex("""[^A-Za-z0-9_.-]""")

    fun fileName(date: LocalDate): String = "$FILE_PREFIX$date$FILE_SUFFIX"

    fun dateFromFileName(name: String): LocalDate? {
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
            return null
        }
        val encodedDate = name.substring(
            startIndex = FILE_PREFIX.length,
            endIndex = name.length - FILE_SUFFIX.length,
        )
        val date = runCatching { LocalDate.parse(encodedDate) }.getOrNull()
            ?: return null
        return date.takeIf { fileName(it) == name }
    }

    fun isDiagnosticLogFile(name: String): Boolean =
        dateFromFileName(name) != null

    fun filesBeyondHistoryLimit(
        names: Iterable<String>,
        maxFiles: Int,
    ): Set<String> {
        if (maxFiles < 1) {
            return names.filter(::isDiagnosticLogFile).toSet()
        }
        return names
            .mapNotNull { name ->
                dateFromFileName(name)?.let { date -> name to date }
            }
            .sortedWith(
                compareByDescending<Pair<String, LocalDate>> { it.second }
                    .thenByDescending { it.first },
            )
            .drop(maxFiles)
            .mapTo(linkedSetOf()) { it.first }
    }

    fun sanitizeTag(tag: String): String =
        tag.trim()
            .replace(invalidTagCharacters, "_")
            .take(48)
            .ifBlank { "App" }

    fun sanitizeMessage(message: String): String {
        return message
            .replace(lineBreaks, " ")
            .replace(urls, "<redacted-url>")
            .replace(authorizationValues, "<redacted-credential>")
            .replace(emails, "<redacted-email>")
            .replace(uuids, "<redacted-uuid>")
            .replace(ipv4Addresses, "<redacted-ip>")
            .replace(ipv6Addresses, "<redacted-ip>")
            .replace(longNumericIds, "<redacted-id>")
            .replace(opaqueSecrets, "<redacted-secret>")
            .trim()
            .take(MAX_MESSAGE_LENGTH)
            .ifBlank { "<empty>" }
    }
}
