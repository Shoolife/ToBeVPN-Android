package com.tobevpn.app.domain.model

enum class BaseStationBypassAccess {
    AUTH_REQUIRED,
    ACCESS_EXPIRED,
    ALLOWED,
}

fun AuthState.baseStationBypassAccess(): BaseStationBypassAccess = when (this) {
    AuthState.Anonymous -> BaseStationBypassAccess.AUTH_REQUIRED
    is AuthState.Authenticated -> when (plan) {
        UserPlan.FREE_TRIAL,
        UserPlan.PAID,
        UserPlan.ADMIN,
        -> BaseStationBypassAccess.ALLOWED

        UserPlan.EXPIRED -> BaseStationBypassAccess.ACCESS_EXPIRED
    }
}

fun AuthState.canUseBaseStationBypass(): Boolean =
    baseStationBypassAccess() == BaseStationBypassAccess.ALLOWED
