package com.tobevpn.app.data.remote.dto

// short_uuid survives the panel upgrade and identifies the same subscription.
// A v3 account can omit UUID; keep a cached alias only for the same account.
internal fun mergePanelUserUuid(
    previousUuid: String?,
    previousShortUuid: String?,
    incomingUuid: String?,
    incomingShortUuid: String?,
): String? {
    incomingUuid?.takeIf { it.isNotBlank() }?.let { return it }
    return previousUuid?.takeIf {
        incomingShortUuid.isNullOrBlank() || incomingShortUuid == previousShortUuid
    }
}

internal fun matchesPreferredPanelUser(
    uuid: String?,
    shortUuid: String?,
    preferredUuid: String?,
    preferredShortUuid: String?,
): Boolean =
    (!preferredUuid.isNullOrBlank() && uuid == preferredUuid) ||
        (!preferredShortUuid.isNullOrBlank() && shortUuid == preferredShortUuid)
