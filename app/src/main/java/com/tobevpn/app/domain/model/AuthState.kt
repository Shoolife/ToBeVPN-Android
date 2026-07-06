package com.tobevpn.app.domain.model

sealed interface AuthState {
    data object Anonymous : AuthState
    data class Authenticated(
        val telegramId: Long,
        val plan: UserPlan,
        val planExpiresAt: Long? = null,
        val planDisplayName: String? = null,
        val isAdminProfile: Boolean = false,
        // Telegram profile photo URL. Null until the backend starts returning
        // it (see AuthRepository.sessionToAuthState / the DTO wiring TODO).
        // The Account card shows a placeholder avatar while this is null.
        val photoUrl: String? = null,
        // Telegram @username (handle without the leading @) and full display
        // name, parsed from the panel user's description. Null when unknown; the
        // Account card falls back to the Telegram ID.
        val username: String? = null,
        val name: String? = null,
    ) : AuthState
}

enum class UserPlan {
    FREE_TRIAL,
    PAID,
    ADMIN,
    EXPIRED,
}
