package com.tobevpn.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri

data class TelegramStartLink(
    val botUsername: String,
    val startParam: String,
)

sealed interface DeviceQrScanAction {
    data class OpenTelegramAuth(val link: TelegramStartLink) : DeviceQrScanAction
    data class LegacyPairingCode(val code: String) : DeviceQrScanAction
    data object Unsupported : DeviceQrScanAction
}

object TelegramLinks {
    const val DEFAULT_BOT_USERNAME = "meow_meow_vpn_bot"

    private val webHosts = setOf("t.me", "www.t.me", "telegram.me", "www.telegram.me")

    fun parseStartLink(
        raw: String,
        expectedBotUsername: String? = null,
    ): TelegramStartLink? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull() ?: return null
        val link = when (uri.scheme?.lowercase()) {
            "http", "https" -> parseWebLink(uri)
            "tg" -> parseTelegramSchemeLink(uri)
            else -> null
        } ?: return null

        if (expectedBotUsername == null) return link
        return link.takeIf { it.botUsername.equals(expectedBotUsername, ignoreCase = true) }
    }

    fun openStartLink(
        context: Context,
        startParam: String,
        botUsername: String = DEFAULT_BOT_USERNAME,
    ): Boolean = openStartLink(
        context = context,
        link = TelegramStartLink(botUsername = botUsername, startParam = startParam),
    )

    fun buildWebStartLink(
        startParam: String,
        botUsername: String = DEFAULT_BOT_USERNAME,
    ): String = createWebUri(
        TelegramStartLink(botUsername = botUsername, startParam = startParam),
    ).toString()

    fun openStartLink(context: Context, link: TelegramStartLink): Boolean {
        val tgIntent = Intent(Intent.ACTION_VIEW, createTelegramUri(link)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(tgIntent)
            true
        } catch (_: Exception) {
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, createWebUri(link)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(webIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun createTelegramUri(link: TelegramStartLink): Uri = Uri.Builder()
        .scheme("tg")
        .authority("resolve")
        .appendQueryParameter("domain", link.botUsername)
        .appendQueryParameter("start", link.startParam)
        .build()

    private fun createWebUri(link: TelegramStartLink): Uri = Uri.Builder()
        .scheme("https")
        .authority("t.me")
        .appendPath(link.botUsername)
        .appendQueryParameter("start", link.startParam)
        .build()

    private fun parseWebLink(uri: Uri): TelegramStartLink? {
        val host = uri.host?.lowercase() ?: return null
        if (host !in webHosts) return null
        val botUsername = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val startParam = uri.getQueryParameter("start")?.takeIf { it.isNotBlank() } ?: return null
        return TelegramStartLink(botUsername = botUsername, startParam = startParam)
    }

    private fun parseTelegramSchemeLink(uri: Uri): TelegramStartLink? {
        val isResolveLink = uri.host.equals("resolve", ignoreCase = true) ||
            uri.path.equals("/resolve", ignoreCase = true)
        if (!isResolveLink) return null

        val botUsername = uri.getQueryParameter("domain")?.takeIf { it.isNotBlank() } ?: return null
        val startParam = uri.getQueryParameter("start")?.takeIf { it.isNotBlank() } ?: return null
        return TelegramStartLink(botUsername = botUsername, startParam = startParam)
    }
}

object DeviceQrScanParser {
    fun parse(raw: String): DeviceQrScanAction {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return DeviceQrScanAction.Unsupported

        TelegramLinks.parseStartLink(
            raw = trimmed,
            expectedBotUsername = TelegramLinks.DEFAULT_BOT_USERNAME,
        )?.let { return DeviceQrScanAction.OpenTelegramAuth(it) }

        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
        if (uri != null) {
            DeepLinkBus.parsePairingCode(uri)?.let { code ->
                return DeviceQrScanAction.LegacyPairingCode(code)
            }
        }

        val scheme = uri?.scheme
        return if (scheme.isNullOrBlank()) {
            DeviceQrScanAction.LegacyPairingCode(trimmed)
        } else {
            DeviceQrScanAction.Unsupported
        }
    }
}
