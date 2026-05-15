package com.tobevpn.app.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("tobevpn_prefs")

data class PendingPurchaseState(
    val startedAt: Long,
    val baselinePlan: String?,
    val baselineExpiresAt: Long?,
)

@Singleton
class PrefsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val SELECTED_SERVER_ID = stringPreferencesKey("selected_server_id")
        val EMAIL_PROMPT_SHOWN = booleanPreferencesKey("email_prompt_shown")
        // USER_EMAIL was removed in v9 — email now lives in the encrypted
        // SessionEntity. We still scrub the legacy plaintext key on first launch
        // so it doesn't linger in the protobuf file. See clearLegacyEmail().
        val LEGACY_USER_EMAIL = stringPreferencesKey("user_email")
        val LANGUAGE = stringPreferencesKey("language")
        val USD_RATE = doublePreferencesKey("usd_rate")
        val USD_RATE_TIMESTAMP = longPreferencesKey("usd_rate_timestamp")
        val ANON_SERVER_BYTES = longPreferencesKey("anon_server_bytes")
        val ANON_PENDING_BYTES = longPreferencesKey("anon_pending_bytes")
        // Subscription refresh throttling. Both values are written together
        // by AuthRepository.syncSubscription() — `LAST_SUB_SYNC_AT` is the
        // wall-clock instant of the last successful sync, and
        // `SUB_UPDATE_INTERVAL_MS` is the panel-recommended cadence taken
        // from the `profile-update-interval` HTTP header on the subscription
        // URL response. The default of 12h matches the subscription
        // auto-refresh cadence used by the backend.
        val LAST_SUB_SYNC_AT = longPreferencesKey("last_sub_sync_at")
        val SUB_UPDATE_INTERVAL_MS = longPreferencesKey("sub_update_interval_ms")
        val PENDING_PURCHASE_STARTED_AT = longPreferencesKey("pending_purchase_started_at")
        val PENDING_PURCHASE_BASELINE_PLAN = stringPreferencesKey("pending_purchase_baseline_plan")
        val PENDING_PURCHASE_BASELINE_EXPIRES_AT = longPreferencesKey("pending_purchase_baseline_expires_at")
        val SERVER_CACHE_OWNER = stringPreferencesKey("server_cache_owner")
        // Per-app VPN filter mode: "OFF", "WHITELIST" or "BLACKLIST".
        // The set of selected packages lives in the Room app_filter table —
        // we keep the mode in Prefs so a destructive DB migration doesn't
        // accidentally land us in WHITELIST with an empty selection (which
        // would block every app's traffic).
        val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[Keys.DEVICE_ID] }
    val onboardingSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_SEEN] ?: false }
    val selectedServerId: Flow<String?> = context.dataStore.data.map { it[Keys.SELECTED_SERVER_ID] }
    val emailPromptShown: Flow<Boolean> = context.dataStore.data.map { it[Keys.EMAIL_PROMPT_SHOWN] ?: false }
    val language: Flow<String?> = context.dataStore.data.map { it[Keys.LANGUAGE] }

    suspend fun getCachedUsdRate(): Pair<Double, Long>? {
        val prefs = context.dataStore.data.first()
        val rate = prefs[Keys.USD_RATE]
        val ts = prefs[Keys.USD_RATE_TIMESTAMP]
        return if (rate != null && ts != null) rate to ts else null
    }

    suspend fun setCachedUsdRate(rate: Double, timestamp: Long) {
        context.dataStore.edit {
            it[Keys.USD_RATE] = rate
            it[Keys.USD_RATE_TIMESTAMP] = timestamp
        }
    }

    suspend fun setDeviceId(id: String) {
        context.dataStore.edit { it[Keys.DEVICE_ID] = id }
    }

    suspend fun setOnboardingSeen() {
        context.dataStore.edit { it[Keys.ONBOARDING_SEEN] = true }
    }

    suspend fun setSelectedServerId(id: String) {
        context.dataStore.edit { it[Keys.SELECTED_SERVER_ID] = id }
    }

    suspend fun setEmailPromptShown() {
        context.dataStore.edit { it[Keys.EMAIL_PROMPT_SHOWN] = true }
    }

    suspend fun setLanguage(tag: String) {
        context.dataStore.edit { it[Keys.LANGUAGE] = tag }
    }

    /**
     * One-shot wipe of the legacy plaintext `user_email` key.
     *
     * Email moved into the encrypted SessionEntity in DB v9. For installs
     * upgrading from an older version, this clears the residual plaintext
     * value from the DataStore protobuf file.
     */
    suspend fun clearLegacyEmail() {
        context.dataStore.edit { it.remove(Keys.LEGACY_USER_EMAIL) }
    }

    /**
     * Returns the legacy plaintext email if one was persisted by an older
     * version of the app. Used during the v8 → v9 migration to seed the
     * encrypted session row before scrubbing the legacy entry.
     */
    suspend fun getLegacyEmail(): String? {
        return context.dataStore.data.first()[Keys.LEGACY_USER_EMAIL]
    }

    suspend fun getAnonymousServerBytes(): Long {
        return context.dataStore.data.first()[Keys.ANON_SERVER_BYTES] ?: 0L
    }

    suspend fun getAnonymousPendingBytes(): Long {
        return context.dataStore.data.first()[Keys.ANON_PENDING_BYTES] ?: 0L
    }

    suspend fun setAnonymousUsageState(serverBytes: Long, pendingBytes: Long) {
        context.dataStore.edit {
            it[Keys.ANON_SERVER_BYTES] = serverBytes
            it[Keys.ANON_PENDING_BYTES] = pendingBytes
        }
    }

    suspend fun addAnonymousPendingBytes(deltaBytes: Long) {
        if (deltaBytes <= 0L) return
        context.dataStore.edit {
            val currentPending = it[Keys.ANON_PENDING_BYTES] ?: 0L
            it[Keys.ANON_PENDING_BYTES] = currentPending + deltaBytes
        }
    }

    suspend fun clearAnonymousUsageState() {
        context.dataStore.edit {
            it.remove(Keys.ANON_SERVER_BYTES)
            it.remove(Keys.ANON_PENDING_BYTES)
        }
    }

    /** Returns (lastSyncAt, intervalMs). Defaults: 0L and 12 hours. */
    suspend fun getSubscriptionSyncState(): Pair<Long, Long> {
        val prefs = context.dataStore.data.first()
        val last = prefs[Keys.LAST_SUB_SYNC_AT] ?: 0L
        val interval = prefs[Keys.SUB_UPDATE_INTERVAL_MS] ?: DEFAULT_SUB_INTERVAL_MS
        return last to interval
    }

    suspend fun setSubscriptionSyncState(lastSyncAt: Long, intervalMs: Long) {
        context.dataStore.edit {
            it[Keys.LAST_SUB_SYNC_AT] = lastSyncAt
            it[Keys.SUB_UPDATE_INTERVAL_MS] = intervalMs
        }
    }

    suspend fun clearSubscriptionSyncTimestamp() {
        context.dataStore.edit { it.remove(Keys.LAST_SUB_SYNC_AT) }
    }

    suspend fun markPendingPurchaseStarted(
        startedAt: Long = System.currentTimeMillis(),
        baselinePlan: String? = null,
        baselineExpiresAt: Long? = null,
    ) {
        context.dataStore.edit {
            it[Keys.PENDING_PURCHASE_STARTED_AT] = startedAt
            if (baselinePlan != null) {
                it[Keys.PENDING_PURCHASE_BASELINE_PLAN] = baselinePlan
            } else {
                it.remove(Keys.PENDING_PURCHASE_BASELINE_PLAN)
            }
            if (baselineExpiresAt != null) {
                it[Keys.PENDING_PURCHASE_BASELINE_EXPIRES_AT] = baselineExpiresAt
            } else {
                it.remove(Keys.PENDING_PURCHASE_BASELINE_EXPIRES_AT)
            }
        }
    }

    suspend fun getPendingPurchaseState(): PendingPurchaseState? {
        val prefs = context.dataStore.data.first()
        val startedAt = prefs[Keys.PENDING_PURCHASE_STARTED_AT] ?: return null
        return PendingPurchaseState(
            startedAt = startedAt,
            baselinePlan = prefs[Keys.PENDING_PURCHASE_BASELINE_PLAN],
            baselineExpiresAt = prefs[Keys.PENDING_PURCHASE_BASELINE_EXPIRES_AT],
        )
    }

    suspend fun clearPendingPurchase() {
        context.dataStore.edit {
            it.remove(Keys.PENDING_PURCHASE_STARTED_AT)
            it.remove(Keys.PENDING_PURCHASE_BASELINE_PLAN)
            it.remove(Keys.PENDING_PURCHASE_BASELINE_EXPIRES_AT)
        }
    }

    suspend fun setServerCacheOwner(shortUuid: String) {
        context.dataStore.edit { it[Keys.SERVER_CACHE_OWNER] = cacheOwnerHash(shortUuid) }
    }

    suspend fun isServerCacheOwner(shortUuid: String): Boolean {
        return context.dataStore.data.first()[Keys.SERVER_CACHE_OWNER] == cacheOwnerHash(shortUuid)
    }

    suspend fun clearServerCacheOwner() {
        context.dataStore.edit { it.remove(Keys.SERVER_CACHE_OWNER) }
    }

    private fun cacheOwnerHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    val appFilterMode: Flow<String?> = context.dataStore.data.map { it[Keys.APP_FILTER_MODE] }

    suspend fun getAppFilterMode(): String? {
        return context.dataStore.data.first()[Keys.APP_FILTER_MODE]
    }

    suspend fun setAppFilterMode(mode: String) {
        context.dataStore.edit { it[Keys.APP_FILTER_MODE] = mode }
    }

    companion object {
        const val DEFAULT_SUB_INTERVAL_MS: Long = 12L * 60L * 60L * 1000L
    }
}
