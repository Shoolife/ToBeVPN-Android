package com.tobevpn.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey
    val deviceId: String,
    val authState: String = "ANONYMOUS",
    val telegramId: Long? = null,
    val pendingAuthToken: String? = null,
    val userPlan: String = "FREE_TRIAL",
    val planDisplayName: String? = null,
    val planExpiresAt: Long? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessExpiresAt: Long? = null,
    val refreshExpiresAt: Long? = null,
    val isLinked: Boolean = false,
    val shortUuid: String? = null,
    val panelUserUuid: String? = null,
    val email: String? = null,
    val isAdminProfile: Boolean = false,
    // Telegram profile parsed from the panel user's description
    // ("name: ...\nusername: ..."). Shown on the account card.
    val telegramName: String? = null,
    val telegramUsername: String? = null,
    // Local file path of the cached Telegram avatar (fetched from
    // GET /api/user/avatar and saved to disk). Null when unknown / no photo.
    val photoUrl: String? = null,
    // Per-user subscription URL (https://<panel-host>/<KEY>). The path
    // segment is a secret token issued by the panel — anyone with the
    // URL can pull the user's VPN config. Lives here in the encrypted
    // SessionEntity so it never leaks into Auto Backup, which is allowed
    // to copy the plaintext PrefsDataStore file off-device.
    val subscriptionUrl: String? = null,
)
