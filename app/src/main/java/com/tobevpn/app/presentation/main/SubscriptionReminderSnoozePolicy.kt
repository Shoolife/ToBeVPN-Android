package com.tobevpn.app.presentation.main

/**
 * Calculates a persistent "Later" window for the renewal reminder.
 *
 * Active subscriptions are never snoozed beyond their expiry moment: if less
 * than 12 hours remain, the expired-state reminder can appear immediately once
 * access expires. A changed expiry timestamp represents a renewed/changed
 * subscription and invalidates the previous snooze.
 */
internal fun subscriptionReminderSnoozeUntil(
    nowMillis: Long,
    expiresAtMillis: Long?,
): Long {
    val regularSnoozeUntil = nowMillis + SUBSCRIPTION_REMINDER_SNOOZE_MS
    return expiresAtMillis
        ?.takeIf { it > nowMillis }
        ?.let { minOf(regularSnoozeUntil, it) }
        ?: regularSnoozeUntil
}

internal fun isSubscriptionReminderSnoozed(
    snoozedUntilMillis: Long,
    snoozedForExpiryMillis: Long?,
    currentExpiryMillis: Long?,
    nowMillis: Long,
): Boolean =
    snoozedUntilMillis > nowMillis &&
        snoozedForExpiryMillis == currentExpiryMillis

private const val SUBSCRIPTION_REMINDER_SNOOZE_MS = 12L * 60L * 60L * 1000L
