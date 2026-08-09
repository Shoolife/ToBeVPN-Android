package com.tobevpn.app.data.local

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.domain.model.DEFAULT_FONT_SCALE
import com.tobevpn.app.domain.model.DEFAULT_INTERFACE_SCALE
import com.tobevpn.app.domain.model.normalizeFontScale
import com.tobevpn.app.domain.model.normalizeInterfaceScale
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(
    name = "tobevpn_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
        val SELECTED_SERVER_KEY = stringPreferencesKey("selected_server_key")
        val AUTOMATIC_SERVER_SELECTION = booleanPreferencesKey("automatic_server_selection")
        val SERVER_QUALITY_STATE = stringPreferencesKey("server_quality_state")
        val EMAIL_PROMPT_SHOWN = booleanPreferencesKey("email_prompt_shown")
        val NOTIFICATION_PERMISSION_PROMPTED = booleanPreferencesKey("notification_permission_prompted")
        // USER_EMAIL was removed in v9 — email now lives in the encrypted
        // SessionEntity. We still scrub the legacy plaintext key on first launch
        // so it doesn't linger in the protobuf file. See clearLegacyEmail().
        val LEGACY_USER_EMAIL = stringPreferencesKey("user_email")
        val LANGUAGE = stringPreferencesKey("language")
        val PROFILE_NAME_DISPLAY = stringPreferencesKey("profile_name_display")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val INTERFACE_SCALE = doublePreferencesKey("interface_scale")
        val FONT_SCALE = doublePreferencesKey("font_scale")
        val BOLD_TEXT = booleanPreferencesKey("bold_text")
        val OUTLINED_TEXT = booleanPreferencesKey("outlined_text")
        val DIAGNOSTIC_MODE_ENABLED = booleanPreferencesKey("diagnostic_mode_enabled")
        val DIAGNOSTIC_LOGGING_ENABLED = booleanPreferencesKey("diagnostic_logging_enabled")
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
        val PURCHASE_PLANS_CACHE_OWNER = stringPreferencesKey("purchase_plans_cache_owner")
        val PURCHASE_PLANS_CACHE_JSON = stringPreferencesKey("purchase_plans_cache_json")
        val BLOCKED_SUBSCRIPTION_OWNER = stringPreferencesKey("blocked_subscription_owner")
        // Scope the persisted block to the installed build. After an update,
        // a block recorded by the previous version must not lock the new
        // version before it can read the current minimum-version header.
        val UPDATE_REQUIRED = booleanPreferencesKey(
            "minimum_version_update_required_${BuildConfig.VERSION_NAME}",
        )
        // Per-app VPN filter state. The selected package set is kept here as
        // the source of truth so it survives DB resets/destructive migrations
        // during app updates; the Room app_filter table is only a legacy mirror.
        val APP_FILTER_MODE = stringPreferencesKey("app_filter_mode")
        val APP_FILTER_PACKAGES = stringPreferencesKey("app_filter_packages")
    }

    val deviceId: Flow<String?> = context.dataStore.data.map { it[Keys.DEVICE_ID] }
    val onboardingSeen: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_SEEN] ?: false }
    val selectedServerId: Flow<String?> = context.dataStore.data.map { it[Keys.SELECTED_SERVER_ID] }
    val selectedServerKey: Flow<String?> = context.dataStore.data.map { it[Keys.SELECTED_SERVER_KEY] }
    val automaticServerSelection: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.AUTOMATIC_SERVER_SELECTION] ?: (it[Keys.SELECTED_SERVER_ID] == null)
    }
    val emailPromptShown: Flow<Boolean> = context.dataStore.data.map { it[Keys.EMAIL_PROMPT_SHOWN] ?: false }
    val notificationPermissionPrompted: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.NOTIFICATION_PERMISSION_PROMPTED] ?: false
    }
    val language: Flow<String?> = context.dataStore.data.map { it[Keys.LANGUAGE] }
    val profileNameDisplay: Flow<String?> =
        context.dataStore.data.map { it[Keys.PROFILE_NAME_DISPLAY] }

    suspend fun setProfileNameDisplay(value: String) {
        context.dataStore.edit { it[Keys.PROFILE_NAME_DISPLAY] = value }
    }

    val themeMode: Flow<String?> = context.dataStore.data.map { it[Keys.THEME_MODE] }

    suspend fun setThemeMode(value: String) {
        context.dataStore.edit { it[Keys.THEME_MODE] = value }
    }

    val interfaceScale: Flow<Float> = context.dataStore.data
        .map {
            normalizeInterfaceScale(
                (it[Keys.INTERFACE_SCALE] ?: DEFAULT_INTERFACE_SCALE.toDouble()).toFloat(),
            )
        }
        .distinctUntilChanged()

    suspend fun setInterfaceScale(value: Float) {
        val normalized = normalizeInterfaceScale(value)
        context.dataStore.edit { it[Keys.INTERFACE_SCALE] = normalized.toDouble() }
    }

    val fontScale: Flow<Float> = context.dataStore.data
        .map {
            normalizeFontScale(
                (it[Keys.FONT_SCALE] ?: DEFAULT_FONT_SCALE.toDouble()).toFloat(),
            )
        }
        .distinctUntilChanged()

    suspend fun setFontScale(value: Float) {
        val normalized = normalizeFontScale(value)
        context.dataStore.edit { it[Keys.FONT_SCALE] = normalized.toDouble() }
    }

    val boldText: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.BOLD_TEXT] ?: false }
        .distinctUntilChanged()

    suspend fun setBoldText(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BOLD_TEXT] = enabled }
    }

    val outlinedText: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.OUTLINED_TEXT] ?: false }
        .distinctUntilChanged()

    suspend fun setOutlinedText(enabled: Boolean) {
        context.dataStore.edit { it[Keys.OUTLINED_TEXT] = enabled }
    }

    suspend fun resetDisplayPreferences() {
        context.dataStore.edit {
            it.remove(Keys.INTERFACE_SCALE)
            it.remove(Keys.FONT_SCALE)
            it.remove(Keys.BOLD_TEXT)
            it.remove(Keys.OUTLINED_TEXT)
        }
    }

    /**
     * Hidden diagnostic mode and active log collection are separate switches:
     * revealing the diagnostic card must never start recording by itself.
     */
    suspend fun getDiagnosticSettings(): Pair<Boolean, Boolean> {
        val prefs = context.dataStore.data.first()
        val modeEnabled = prefs[Keys.DIAGNOSTIC_MODE_ENABLED] ?: false
        val loggingEnabled = modeEnabled &&
            (prefs[Keys.DIAGNOSTIC_LOGGING_ENABLED] ?: false)
        return modeEnabled to loggingEnabled
    }

    /**
     * Disabling the hidden mode atomically disables collection as well. This
     * prevents a background recorder from remaining active after its controls
     * disappear from the About screen.
     */
    suspend fun setDiagnosticModeEnabled(enabled: Boolean) {
        context.dataStore.edit {
            it[Keys.DIAGNOSTIC_MODE_ENABLED] = enabled
            if (!enabled) {
                it[Keys.DIAGNOSTIC_LOGGING_ENABLED] = false
            }
        }
    }

    suspend fun setDiagnosticLoggingEnabled(enabled: Boolean) {
        context.dataStore.edit {
            val modeEnabled = it[Keys.DIAGNOSTIC_MODE_ENABLED] ?: false
            it[Keys.DIAGNOSTIC_LOGGING_ENABLED] = enabled && modeEnabled
        }
    }

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
        context.dataStore.edit {
            val automatic = it[Keys.AUTOMATIC_SERVER_SELECTION] ?: (it[Keys.SELECTED_SERVER_ID] == null)
            it[Keys.SELECTED_SERVER_ID] = id
            it.remove(Keys.SELECTED_SERVER_KEY)
            it[Keys.AUTOMATIC_SERVER_SELECTION] = automatic
        }
    }

    suspend fun setSelectedServer(id: String, key: String) {
        context.dataStore.edit {
            val automatic = it[Keys.AUTOMATIC_SERVER_SELECTION] ?: (it[Keys.SELECTED_SERVER_ID] == null)
            it[Keys.SELECTED_SERVER_ID] = id
            it[Keys.SELECTED_SERVER_KEY] = key
            it[Keys.AUTOMATIC_SERVER_SELECTION] = automatic
        }
    }

    suspend fun setManualSelectedServer(id: String, key: String) {
        context.dataStore.edit {
            it[Keys.SELECTED_SERVER_ID] = id
            it[Keys.SELECTED_SERVER_KEY] = key
            it[Keys.AUTOMATIC_SERVER_SELECTION] = false
        }
    }

    suspend fun setAutomaticSelectedServer(id: String, key: String) {
        context.dataStore.edit {
            it[Keys.SELECTED_SERVER_ID] = id
            it[Keys.SELECTED_SERVER_KEY] = key
            it[Keys.AUTOMATIC_SERVER_SELECTION] = true
        }
    }

    suspend fun isAutomaticServerSelection(): Boolean {
        val prefs = context.dataStore.data.first()
        return prefs[Keys.AUTOMATIC_SERVER_SELECTION] ?: (prefs[Keys.SELECTED_SERVER_ID] == null)
    }

    suspend fun getSelectedServerId(): String? {
        return context.dataStore.data.first()[Keys.SELECTED_SERVER_ID]
    }

    suspend fun getServerQualityState(): String? {
        return context.dataStore.data.first()[Keys.SERVER_QUALITY_STATE]
    }

    suspend fun setServerQualityState(value: String) {
        context.dataStore.edit { it[Keys.SERVER_QUALITY_STATE] = value }
    }

    suspend fun setEmailPromptShown() {
        context.dataStore.edit { it[Keys.EMAIL_PROMPT_SHOWN] = true }
    }

    suspend fun setNotificationPermissionPrompted() {
        context.dataStore.edit { it[Keys.NOTIFICATION_PERMISSION_PROMPTED] = true }
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

    suspend fun setPurchasePlansCache(telegramId: Long, json: String) {
        val owner = cacheOwnerHash(telegramId.toString())
        context.dataStore.edit {
            it[Keys.PURCHASE_PLANS_CACHE_OWNER] = owner
            it[Keys.PURCHASE_PLANS_CACHE_JSON] = json
        }
    }

    suspend fun getPurchasePlansCache(telegramId: Long): String? {
        val prefs = context.dataStore.data.first()
        val owner = cacheOwnerHash(telegramId.toString())
        if (prefs[Keys.PURCHASE_PLANS_CACHE_OWNER] != owner) return null
        return prefs[Keys.PURCHASE_PLANS_CACHE_JSON]
    }

    suspend fun clearPurchasePlansCache() {
        context.dataStore.edit {
            it.remove(Keys.PURCHASE_PLANS_CACHE_OWNER)
            it.remove(Keys.PURCHASE_PLANS_CACHE_JSON)
        }
    }

    suspend fun setSubscriptionUsageBlocked(shortUuid: String, blocked: Boolean) {
        val owner = cacheOwnerHash(shortUuid)
        context.dataStore.edit {
            if (blocked) {
                it[Keys.BLOCKED_SUBSCRIPTION_OWNER] = owner
            } else if (it[Keys.BLOCKED_SUBSCRIPTION_OWNER] == owner) {
                it.remove(Keys.BLOCKED_SUBSCRIPTION_OWNER)
            }
        }
    }

    suspend fun isSubscriptionUsageBlocked(shortUuid: String): Boolean {
        return context.dataStore.data.first()[Keys.BLOCKED_SUBSCRIPTION_OWNER] == cacheOwnerHash(shortUuid)
    }

    fun observeSubscriptionUsageBlocked(shortUuid: String): Flow<Boolean> {
        val owner = cacheOwnerHash(shortUuid)
        return context.dataStore.data
            .map { it[Keys.BLOCKED_SUBSCRIPTION_OWNER] == owner }
            .distinctUntilChanged()
    }

    suspend fun setUpdateRequired(required: Boolean) {
        context.dataStore.edit {
            if (required) it[Keys.UPDATE_REQUIRED] = true
            else it.remove(Keys.UPDATE_REQUIRED)
        }
    }

    fun observeUpdateRequired(): Flow<Boolean> {
        return context.dataStore.data
            .map { it[Keys.UPDATE_REQUIRED] == true }
            .distinctUntilChanged()
    }

    suspend fun isUpdateRequired(): Boolean {
        return context.dataStore.data.first()[Keys.UPDATE_REQUIRED] == true
    }

    private fun cacheOwnerHash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
    }

    val appFilterMode: Flow<String?> = context.dataStore.data.map { it[Keys.APP_FILTER_MODE] }
    val appFilterPackages: Flow<Set<String>?> = context.dataStore.data.map {
        it[Keys.APP_FILTER_PACKAGES]?.let(::decodeAppFilterPackages)
    }

    suspend fun getAppFilterMode(): String? {
        return context.dataStore.data.first()[Keys.APP_FILTER_MODE]
    }

    suspend fun setAppFilterMode(mode: String) {
        context.dataStore.edit { it[Keys.APP_FILTER_MODE] = mode }
    }

    suspend fun getAppFilterPackages(): Set<String>? {
        return context.dataStore.data.first()[Keys.APP_FILTER_PACKAGES]?.let(::decodeAppFilterPackages)
    }

    suspend fun setAppFilterPackages(packageNames: Collection<String>) {
        context.dataStore.edit {
            it[Keys.APP_FILTER_PACKAGES] = encodeAppFilterPackages(packageNames)
        }
    }

    private fun encodeAppFilterPackages(packageNames: Collection<String>): String =
        packageNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(separator = "\n")

    private fun decodeAppFilterPackages(raw: String): Set<String> =
        raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    companion object {
        const val DEFAULT_SUB_INTERVAL_MS: Long = 12L * 60L * 60L * 1000L
    }
}
