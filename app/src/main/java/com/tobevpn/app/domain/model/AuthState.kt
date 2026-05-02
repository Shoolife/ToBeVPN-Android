package com.tobevpn.app.domain.model

sealed interface AuthState {
    data object Anonymous : AuthState
    data class Authenticated(
        val telegramId: Long,
        val plan: UserPlan,
        val planExpiresAt: Long? = null,
    ) : AuthState
}

enum class UserPlan {
    FREE_TRIAL,
    PAID,
    ADMIN,
    EXPIRED,
}
