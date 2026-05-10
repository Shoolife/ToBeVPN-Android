package com.tobevpn.app.domain.model

/**
 * Per-app routing policy. Mirrors the three-state model exposed by
 * Android's [android.net.VpnService.Builder]:
 *   * [OFF]       — every app's traffic is tunnelled through the VPN
 *                   (Builder receives no allow/disallow calls).
 *   * [WHITELIST] — only apps in the selection set are tunnelled, every
 *                   other app uses the underlying network directly.
 *   * [BLACKLIST] — every app *except* those in the selection is tunnelled;
 *                   our own app id is always added to the disallow list to
 *                   prevent the VPN traffic from looping back through itself.
 */
enum class AppFilterMode { OFF, WHITELIST, BLACKLIST }

data class AppFilterState(
    val mode: AppFilterMode,
    val selectedPackages: Set<String>,
)
