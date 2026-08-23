package com.tobevpn.app.presentation.components

internal enum class SubscriptionExpiryUrgency {
    NORMAL,
    WARNING,
    CRITICAL,
}

/**
 * Shared expiry thresholds for every subscription date in the app.
 *
 * More than seven days is intentionally neutral. The warning colour starts at
 * seven days, while the critical colour and the renewal reminder both start
 * at exactly 72 hours.
 */
internal fun subscriptionExpiryUrgency(
    expiresAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): SubscriptionExpiryUrgency {
    val millisLeft = subscriptionExpiryMillisLeft(
        expiresAtMillis = expiresAtMillis,
        nowMillis = nowMillis,
    )
    return when {
        millisLeft <= SUBSCRIPTION_CRITICAL_THRESHOLD_MS -> SubscriptionExpiryUrgency.CRITICAL
        millisLeft <= SUBSCRIPTION_WARNING_THRESHOLD_MS -> SubscriptionExpiryUrgency.WARNING
        else -> SubscriptionExpiryUrgency.NORMAL
    }
}

/** Returns the real remaining subscription time. */
internal fun subscriptionExpiryMillisLeft(
    expiresAtMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
): Long = expiresAtMillis - nowMillis

private const val SUBSCRIPTION_DAY_MS = 86_400_000L
private const val SUBSCRIPTION_CRITICAL_THRESHOLD_MS = 3L * SUBSCRIPTION_DAY_MS
private const val SUBSCRIPTION_WARNING_THRESHOLD_MS = 7L * SUBSCRIPTION_DAY_MS
