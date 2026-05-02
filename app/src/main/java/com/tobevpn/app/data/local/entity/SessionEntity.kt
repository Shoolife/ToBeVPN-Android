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
)
