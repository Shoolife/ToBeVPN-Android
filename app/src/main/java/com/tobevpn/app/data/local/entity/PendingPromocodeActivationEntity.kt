package com.tobevpn.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A promocode request whose outcome may still be unknown to the client.
 *
 * The row lives in the SQLCipher-backed Room database rather than DataStore:
 * promocodes can carry value, while DataStore is intentionally included in
 * Android Auto Backup. Keeping one row per account and normalized code also
 * lets a retry after process death reuse the exact UUID required by the
 * backend's idempotency contract.
 */
@Entity(
    tableName = "pending_promocode_activations",
    primaryKeys = ["telegramId", "code"],
    indices = [Index(value = ["requestId"], unique = true)],
)
data class PendingPromocodeActivationEntity(
    val telegramId: Long,
    val code: String,
    val requestId: String,
    val createdAt: Long,
)
