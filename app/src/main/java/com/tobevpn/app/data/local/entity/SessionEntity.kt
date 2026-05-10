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
    val planExpiresAt: Long? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val accessExpiresAt: Long? = null,
    val refreshExpiresAt: Long? = null,
    val isLinked: Boolean = false,
    val shortUuid: String? = null,
    val panelUserUuid: String? = null,
    val email: String? = null,
    // Per-user subscription URL (https://<panel-host>/<KEY>). The path
    // segment is a secret token issued by the panel — anyone with the
    // URL can pull the user's VPN config. Lives here in the encrypted
    // SessionEntity so it never leaks into Auto Backup, which is allowed
    // to copy the plaintext PrefsDataStore file off-device.
    val subscriptionUrl: String? = null,
)
