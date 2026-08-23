package com.tobevpn.app.data.repository

import android.content.Context
import android.os.Build
import com.tobevpn.app.data.device.DeviceIdProvider
import com.tobevpn.app.data.device.DeviceFingerprintProvider
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.SessionStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.SessionEntity
import com.tobevpn.app.data.remote.BootstrapManager
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.SubscriptionPinger
import com.tobevpn.app.data.remote.dto.AuthRequestDto
import com.tobevpn.app.data.remote.dto.CurrentPlanDto
import com.tobevpn.app.data.remote.dto.DeviceRegisterRequestDto
import com.tobevpn.app.data.remote.dto.DeviceUnlinkRequestDto
import com.tobevpn.app.data.remote.dto.DeviceUnlinkResponseDto
import com.tobevpn.app.data.remote.dto.EnsureUserRequestDto
import com.tobevpn.app.data.remote.dto.SaveEmailRequestDto
import com.tobevpn.app.data.remote.dto.TvPairCreateResponseDto
import com.tobevpn.app.data.remote.dto.TvPairCreateRequestDto
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DevicePairingPollResult {
    data object Pending : DevicePairingPollResult
    data object Expired : DevicePairingPollResult
    data object Completed : DevicePairingPollResult
}

enum class TelegramAuthPollResult {
    PENDING,
    COMPLETED,
    INVALID,
    RETRYABLE_ERROR,
}

data class CurrentSubscriptionPlanInfo(
    val displayName: String?,
    val trafficLimitBytes: Long?,
    val deviceLimit: Int?,
    val expiresAtMillis: Long?,
    val isActive: Boolean?,
    val isExpired: Boolean?,
    val isTrial: Boolean?,
    val isUnlimited: Boolean?,
    val isAdmin: Boolean,
    val subscriptionUrl: String?,
    val renewalUrl: String?,
    val hasPlanData: Boolean,
)

@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionDao: SessionDao,
    private val sessionStore: SessionStore,
    private val prefsDataStore: PrefsDataStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val fingerprintProvider: DeviceFingerprintProvider,
    private val botApi: BotApi,
    private val usageRepository: UsageRepository,
    private val subscriptionPinger: SubscriptionPinger,
    private val bootstrapManager: BootstrapManager,
    private val vpnRepository: VpnRepository,
    private val baseStationBypassRepository: BaseStationBypassRepository,
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    private val _subscriptionResetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val subscriptionResetEvents: SharedFlow<Unit> = _subscriptionResetEvents.asSharedFlow()

    // The auth screen and the application-wide session coordinator may observe
    // the same confirmation. Complete it once so profile refresh and VPN
    // recovery cannot run twice in parallel.
    private val authCompletionMutex = Mutex()

    // Avatar (GET /api/user/avatar) is rate-limited, so we fetch it at most once
    // per app process unless a re-auth forces a refresh.
    @Volatile
    private var avatarFetchedThisSession = false

    private val _avatarLoading = MutableStateFlow(false)
    /** True while an avatar download is in flight (drives a loading spinner). */
    val avatarLoading: StateFlow<Boolean> = _avatarLoading.asStateFlow()

    private fun avatarDir(): File = File(context.filesDir, "avatars").apply { mkdirs() }

    /**
     * Fetches the avatar on app entry only when we don't already have one
     * cached — a photo persisted from a previous run is reused as-is (no repeat
     * download). Re-auth still forces a fresh download via [refreshAvatar].
     */
    suspend fun refreshAvatarOnce() {
        if (avatarFetchedThisSession) return
        avatarFetchedThisSession = true
        val cached = sessionDao.getSession()?.photoUrl
        if (cached != null && File(cached).exists()) return
        refreshAvatar()
    }

    /**
     * Downloads the current user's Telegram avatar (binary JPEG) and caches it
     * on disk, pointing the session at the file. 404 clears the cached photo;
     * other errors (401/403/429/502) leave the current one for a later retry.
     * Call sparingly — the endpoint is rate-limited (20/min per device).
     */
    suspend fun refreshAvatar() {
        _avatarLoading.value = true
        try {
            // All of this runs off the main thread: with @Streaming, reading the
            // response bytes performs the actual network download on the calling
            // thread, and the file writes are blocking I/O — doing it on main
            // froze the Settings screen.
            withContext(Dispatchers.IO) {
                val response = botApi.getUserAvatar()
                if (response.isSuccessful) {
                    val bytes = response.body()?.byteStream()?.use { it.readBytes() }
                    if (bytes != null && bytes.isNotEmpty()) {
                        val dir = avatarDir()
                        // A fresh filename each time avoids a stale Coil cache.
                        val file = File(dir, "tg_avatar_${System.currentTimeMillis()}.jpg")
                        file.writeBytes(bytes)
                        dir.listFiles()?.forEach { if (it != file) it.delete() }
                        sessionStore.update { it.copy(photoUrl = file.absolutePath) }
                    }
                } else {
                    response.errorBody()?.close()
                    if (response.code() == 404) {
                        avatarDir().listFiles()?.forEach { it.delete() }
                        sessionStore.update { it.copy(photoUrl = null) }
                    }
                }
            }
        } catch (error: Exception) {
            SafeDiagnostics.warn(TAG, "Avatar refresh failed: ${SafeDiagnostics.failureCategory(error)}")
        } finally {
            _avatarLoading.value = false
        }
    }

    // Serialises concurrent syncSubscription() callers (MainViewModel.init,
    // ServerListViewModel.init and onResume() can fire near-simultaneously
    // on a fresh app launch). Without this each one independently hits the
    // backend; with it, the second caller suspends, then sees the throttle
    // window updated by the first and exits without a network call.
    private val syncMutex = Mutex()

    private fun planFromString(plan: String): UserPlan {
        return runCatching { UserPlan.valueOf(plan) }.getOrDefault(UserPlan.FREE_TRIAL)
    }

    /**
     * Extracts the Telegram name / @username the bot stores in the panel user's
     * description as "name: <full name>\nusername: <handle>". Returns the value
     * or null when a field is absent; the username keeps no leading '@'.
     */
    private fun parseTelegramProfile(description: String?): Pair<String?, String?> {
        if (description.isNullOrBlank()) return null to null
        var name: String? = null
        var username: String? = null
        description.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("name:", ignoreCase = true) ->
                    name = line.substringAfter(':').trim().takeIf { it.isNotBlank() }
                line.startsWith("username:", ignoreCase = true) ->
                    username = line.substringAfter(':').trim().removePrefix("@")
                        .takeIf { it.isNotBlank() }
            }
        }
        return name to username
    }

    private fun sessionToAuthState(session: SessionEntity?): AuthState {
        return if (session?.authState == "AUTHENTICATED" && session.telegramId != null) {
            AuthState.Authenticated(
                telegramId = session.telegramId,
                plan = planFromString(session.userPlan),
                planExpiresAt = session.planExpiresAt,
                planDisplayName = session.planDisplayName,
                isAdminProfile = session.isAdminProfile,
                username = session.telegramUsername,
                name = session.telegramName,
                photoUrl = session.photoUrl,
            )
        } else {
            AuthState.Anonymous
        }
    }

    /** Fetches remote config from the backend. Call once on app start. */
    suspend fun fetchRemoteConfig() {
        try {
            botApi.getConfig()
        } catch (_: Exception) {
            // Use defaults
        }
    }

    fun observeAuthState(): Flow<AuthState> {
        // Suppress redundant emissions — sessionStore.update writes the whole
        // row even when only fields outside AuthState change (email, tokens,
        // shortUuid). Without this the UI gets recomposition churn for no
        // visible state change.
        return sessionDao.observeSession()
            .map { session -> sessionToAuthState(session) }
            .distinctUntilChanged()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSubscriptionUsageBlocked(): Flow<Boolean> {
        return sessionDao.observeSession()
            .map { it?.shortUuid }
            .distinctUntilChanged()
            .flatMapLatest { shortUuid ->
                if (shortUuid == null) {
                    flowOf(false)
                } else {
                    prefsDataStore.observeSubscriptionUsageBlocked(shortUuid)
                }
            }
            .distinctUntilChanged()
    }

    suspend fun getOrCreateDeviceId(): String {
        val deviceId = deviceIdProvider.getOrCreate()
        // Race-safe seed of the session row — concurrent writers (bootstrap,
        // syncSubscription) all funnel through SessionStore now.
        sessionStore.updateOrCreate(deviceId) { it }
        return deviceId
    }

    suspend fun getCurrentDeviceAliases(): Set<String> {
        val aliases = linkedSetOf<String>()
        getOrCreateDeviceId().trim().takeIf { it.isNotBlank() }?.let { aliases += it }
        fingerprintProvider.get().hwid.trim().takeIf { it.isNotBlank() }?.let { hwid ->
            aliases += hwid
            aliases += hwid.lowercase(Locale.ROOT)
        }
        return aliases
    }

    /**
     * Returns the pending auth token persisted from a previous `requestTelegramAuth`,
     * or `null` if none. Used by the deep-link callback path on cold start to recover
     * the token that the in-memory `AuthViewModel.currentAuthToken` doesn't have yet.
     */
    suspend fun getPendingAuthToken(): String? {
        return sessionDao.getSession()?.pendingAuthToken?.takeIf { it.isNotBlank() }
    }

    fun observePendingAuthToken(): Flow<String?> {
        return sessionDao.observeSession()
            .map { session -> session?.pendingAuthToken?.takeIf { it.isNotBlank() } }
            .distinctUntilChanged()
    }

    suspend fun clearPendingAuthToken(expectedToken: String) {
        sessionStore.update { current ->
            if (current.pendingAuthToken == expectedToken) {
                current.copy(pendingAuthToken = null)
            } else {
                current
            }
        }
    }

    suspend fun getAuthStateSnapshot(): AuthState {
        return sessionToAuthState(sessionDao.getSession())
    }

    private fun currentDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            model.isEmpty() && manufacturer.isEmpty() -> "Android phone"
            manufacturer.isEmpty() -> model
            model.isEmpty() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }

    private suspend fun syncUsageFromServer(
        serverBytesUsed: Long,
        isAnonymous: Boolean,
    ) {
        val currentUsage = usageRepository.getUsage()
        val mergedBytesUsed = if (isAnonymous) {
            val previousServerBytes = prefsDataStore.getAnonymousServerBytes()
            val pendingBytes = prefsDataStore.getAnonymousPendingBytes()
            val effectiveServerBytes = serverBytesUsed.coerceAtLeast(previousServerBytes)
            val acknowledgedBytes = (effectiveServerBytes - previousServerBytes).coerceAtLeast(0L)
            val remainingPendingBytes = (pendingBytes - acknowledgedBytes).coerceAtLeast(0L)
            prefsDataStore.setAnonymousUsageState(
                serverBytes = effectiveServerBytes,
                pendingBytes = remainingPendingBytes,
            )
            effectiveServerBytes + remainingPendingBytes
        } else {
            prefsDataStore.clearAnonymousUsageState()
            serverBytesUsed
        }
        usageRepository.updateUsage(mergedBytesUsed, currentUsage.timeUsedSeconds)
    }

    suspend fun registerCurrentDevice(): Result<Unit> {
        return try {
            val request = DeviceRegisterRequestDto(
                deviceName = currentDeviceName(),
                deviceType = "phone",
                platform = "Android",
            )
            val response = withFreshDeviceSessionRetry {
                botApi.registerDevice(request)
            }
            if (response.success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(response.message ?: "Could not register device"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPanelUserByTelegramId(
        telegramId: Long,
    ): com.tobevpn.app.data.remote.dto.PanelUserDto? {
        return withFreshDeviceSessionRetry {
            val users = botApi.getUserByTelegramId(telegramId).response
            val session = sessionDao.getSession()
            selectBestPanelUser(
                users = users,
                preferredPanelUserUuid = session?.panelUserUuid,
                preferredShortUuid = session?.shortUuid,
            )
        }
    }

    suspend fun getCurrentSubscriptionPlan(): CurrentSubscriptionPlanInfo? {
        return try {
            withFreshDeviceSessionRetry {
                val response = botApi.getCurrentPlan()
                if (response.success) {
                    response.data?.toCurrentSubscriptionPlanInfo()
                } else {
                    null
                }
            }
        } catch (error: Exception) {
            SafeDiagnostics.warn(TAG, "Current plan request failed: ${SafeDiagnostics.failureCategory(error)}")
            null
        }
    }

    private suspend fun <T> withFreshDeviceSessionRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (error: Exception) {
            if (!error.isDeviceSessionAuthFailure()) throw error
            runCatching { bootstrapManager.bootstrap() }.getOrElse { throw error }
            block()
        }
    }

    private fun Throwable.isDeviceSessionAuthFailure(): Boolean {
        return this is HttpException && (code() == 401 || code() == 403)
    }

    suspend fun unlinkDevice(deviceId: String): Result<Unit> {
        return try {
            val currentDeviceAliases = getCurrentDeviceAliases()
            val response = withFreshDeviceSessionRetry {
                botApi.unlinkDevice(DeviceUnlinkRequestDto(deviceId = deviceId))
            }
            if (!response.success) {
                Result.failure(IllegalStateException(response.message ?: "Could not unlink device"))
            } else {
                val isCurrentDevice = currentDeviceAliases.any { it.equals(deviceId, ignoreCase = true) }
                if (isCurrentDevice) {
                    clearLinkedIdentity()
                } else {
                    refreshAfterDeviceUnlink(response.data)
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun refreshAfterDeviceUnlink(data: DeviceUnlinkResponseDto?) {
        val oldShortUuid = sessionDao.getSession()?.shortUuid
        runCatching { bootstrapManager.bootstrap() }

        val planInfo = data
            ?.takeIf { it.currentPlan != null || it.subscription != null }
            ?.let {
                CurrentPlanDto(
                    currentPlan = it.currentPlan,
                    subscription = it.subscription,
                ).toCurrentSubscriptionPlanInfo()
            }
        sessionStore.update { current ->
            val resolvedPlan = planInfo?.let {
                resolvePlanFromCurrentPlan(current.userPlan, it)
            }
            current.copy(
                subscriptionUrl = data?.subscriptionUrl
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: planInfo?.subscriptionUrl
                    ?: current.subscriptionUrl,
                userPlan = resolvedPlan ?: current.userPlan,
                planDisplayName = planInfo?.displayName ?: current.planDisplayName,
                planExpiresAt = planInfo?.expiresAtMillis ?: current.planExpiresAt,
                isAdminProfile = planInfo?.isAdmin ?: current.isAdminProfile,
            )
        }
        planInfo?.trafficLimitBytes?.let { usageRepository.updateLimits(it, 0) }

        val newShortUuid = sessionDao.getSession()?.shortUuid
        if (oldShortUuid != newShortUuid || !newShortUuid.isNullOrBlank()) {
            vpnRepository.clearCachedServers()
        }
        prefsDataStore.clearSubscriptionSyncTimestamp()
        runCatching { syncSubscription(force = true) }
        runCatching { vpnRepository.refreshServers(forceRefresh = true) }
        _subscriptionResetEvents.tryEmit(Unit)
    }

    suspend fun syncDeviceSessionState(): Result<Boolean> {
        val session = sessionDao.getSession()
        val hadLinkedIdentity = session?.authState == "AUTHENTICATED" &&
            session.telegramId != null &&
            session.isLinked
        if (!hadLinkedIdentity) return Result.success(false)

        return try {
            val response = botApi.getCurrentPlan()
            if (response.success) {
                runCatching { applyCurrentPlanHeartbeat(response.data) }
                Result.success(true)
            } else {
                Result.failure(
                    IllegalStateException(response.message ?: "Could not verify device session"),
                )
            }
        } catch (error: Exception) {
            if (error.isRemoteDeviceUnlinkedError()) {
                Result.success(false)
            } else {
                Result.failure(error)
            }
        }
    }

    private suspend fun applyCurrentPlanHeartbeat(data: CurrentPlanDto?) {
        val planInfo = data?.toCurrentSubscriptionPlanInfo() ?: return
        val sessionBefore = sessionDao.getSession() ?: return
        val nextUrl = planInfo.subscriptionUrl
        val subscriptionUrlChanged = !nextUrl.isNullOrBlank() &&
            nextUrl != sessionBefore.subscriptionUrl

        if (subscriptionUrlChanged) {
            runCatching { bootstrapManager.bootstrap() }
        }
        sessionStore.update { current ->
            val resolvedPlan = resolvePlanFromCurrentPlan(current.userPlan, planInfo)
            current.copy(
                userPlan = resolvedPlan,
                planDisplayName = if (resolvedPlan == "EXPIRED") {
                    null
                } else {
                    planInfo.displayName ?: current.planDisplayName
                },
                planExpiresAt = planInfo.expiresAtMillis,
                subscriptionUrl = nextUrl ?: current.subscriptionUrl,
                isAdminProfile = planInfo.isAdmin,
            )
        }
        planInfo.trafficLimitBytes?.let { usageRepository.updateLimits(it, 0) }

        if (subscriptionUrlChanged) {
            prefsDataStore.clearSubscriptionSyncTimestamp()
            vpnRepository.clearCachedServers()
            runCatching { syncSubscription(force = true) }
            runCatching { vpnRepository.refreshServers(forceRefresh = true) }
            _subscriptionResetEvents.tryEmit(Unit)
        }
    }

    suspend fun clearRemoteUnlinkedSession() {
        clearLinkedIdentity()
    }

    private fun Throwable.isRemoteDeviceUnlinkedError(): Boolean {
        if (this is HttpException && code() == 401) return true
        if (this is HttpException && code() !in setOf(400, 403)) return false
        val body = if (this is HttpException) {
            runCatching { response()?.errorBody()?.string() }.getOrDefault("")
        } else {
            ""
        }
        val text = listOfNotNull(message, body)
            .joinToString("\n")
            .lowercase(Locale.US)
        return (text.contains("current device") && text.contains("not linked")) ||
            (text.contains("telegram_id") && text.contains("not authenticated")) ||
            (text.contains("telegram_id") && text.contains("required")) ||
            (text.contains("invalid or expired") && text.contains("access token"))
    }

    private suspend fun clearLinkedIdentity() {
        val session = sessionDao.getSession() ?: return
        val hadAccountUsage = session.userPlan == "PAID" || session.userPlan == "ADMIN"
        sessionStore.update { current ->
            current.copy(
                authState = "ANONYMOUS",
                telegramId = null,
                planExpiresAt = null,
                accessToken = null,
                refreshToken = null,
                accessExpiresAt = null,
                refreshExpiresAt = null,
                isLinked = false,
                shortUuid = null,
                panelUserUuid = null,
                userPlan = "FREE_TRIAL",
                planDisplayName = null,
                email = null,
                isAdminProfile = false,
                pendingAuthToken = null,
                subscriptionUrl = null,
            )
        }
        if (hadAccountUsage) {
            prefsDataStore.clearAnonymousUsageState()
            usageRepository.resetSession()
        }
        prefsDataStore.clearPendingPurchase()
        prefsDataStore.clearSubscriptionSyncTimestamp()
        vpnRepository.clearCachedServers()
        baseStationBypassRepository.clearCachedServers()
        bootstrapManager.clear()
        runCatching { bootstrapManager.ensureBootstrapped() }
        runCatching { ensurePanelUser() }
        runCatching { syncSubscription(overwriteUsage = true, force = true) }
    }

    /**
     * Ensures a panel user exists for the current device.
     * Server handles lookup by username and creation with free trial params.
     */
    suspend fun ensurePanelUser(): Result<String> {
        val session = sessionDao.getSession()
        if (session?.shortUuid != null) {
            return Result.success(session.shortUuid)
        }

        return try {
            val deviceId = getOrCreateDeviceId()

            val response = withFreshDeviceSessionRetry {
                botApi.ensureUser(
                    EnsureUserRequestDto(
                        deviceId = deviceId,
                    )
                )
            }
            if (!response.success || response.data == null) {
                return Result.failure(IllegalStateException(response.message ?: "Failed to ensure user"))
            }

            val data = response.data
            val isAuthenticated = !data.isAnonymous && data.telegramId != null
            sessionStore.updateOrCreate(deviceId) { current ->
                current.copy(
                    shortUuid = data.shortUuid,
                    panelUserUuid = data.panelUserUuid,
                    authState = if (isAuthenticated) "AUTHENTICATED" else "ANONYMOUS",
                    telegramId = if (isAuthenticated) data.telegramId else null,
                    isLinked = isAuthenticated,
                    pendingAuthToken = if (isAuthenticated) null else current.pendingAuthToken,
                    userPlan = if (isAuthenticated) current.userPlan else "FREE_TRIAL",
                    planExpiresAt = if (isAuthenticated) current.planExpiresAt else null,
                    isAdminProfile = if (isAuthenticated) current.isAdminProfile else false,
                    subscriptionUrl = if (isAuthenticated) {
                        data.subscriptionUrl ?: current.subscriptionUrl
                    } else {
                        data.subscriptionUrl
                    },
                )
            }

            // Panel is source of truth for limits. For anonymous users, keep the
            // local counter monotonic on this device_id until the backend catches up.
            usageRepository.updateLimits(data.trafficLimitBytes, 0)
            val trafficUsedBytes = if (isAuthenticated) {
                data.trafficUsedBytes
            } else {
                data.anonTrafficBytes ?: data.trafficUsedBytes
            }
            syncUsageFromServer(
                serverBytesUsed = trafficUsedBytes,
                isAnonymous = !isAuthenticated,
            )

            Result.success(data.shortUuid)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Requests Telegram auth via the backend.
     * Returns Result with server-generated auth token on success.
     */
    suspend fun requestTelegramAuth(): Result<String> {
        val pendingToken = getPendingAuthToken()
        if (pendingToken != null) {
            when (pollTelegramAuthStatus(pendingToken)) {
                TelegramAuthPollResult.COMPLETED,
                TelegramAuthPollResult.PENDING,
                TelegramAuthPollResult.RETRYABLE_ERROR,
                -> return Result.success(pendingToken)
                TelegramAuthPollResult.INVALID -> clearPendingAuthToken(pendingToken)
            }
        }

        val session = sessionDao.getSession()
        val deviceId = getOrCreateDeviceId()
        val request = AuthRequestDto(
            deviceId = deviceId,
            panelUserUuid = session?.panelUserUuid,
        )

        return try {
            val response = botApi.requestAuth(request)
            if (!response.success) {
                return Result.failure(IllegalStateException(response.message ?: "Failed to request auth"))
            }
            val authToken = response.data?.authToken
                ?: return Result.failure(IllegalStateException("Auth token missing from response"))
            sessionStore.updateOrCreate(deviceId) { it.copy(pendingAuthToken = authToken) }
            Result.success(authToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestDevicePairing(): Result<TvPairCreateResponseDto> {
        return try {
            val response = botApi.createTvPairing(TvPairCreateRequestDto())
            val data = response.data
            if (response.success && data != null) {
                Result.success(data)
            } else {
                Result.failure(IllegalStateException(response.message ?: "Could not create pairing code"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun planForPanelUser(panelUser: com.tobevpn.app.data.remote.dto.PanelUserDto): String {
        val squads = panelUser.activeInternalSquads.orEmpty().map { it.name.uppercase(Locale.ROOT) }
        return when {
            "ADMINS" in squads -> "ADMIN"
            "STANDART" in squads -> "PAID"
            else -> "FREE_TRIAL"
        }
    }

    private fun CurrentPlanDto.toCurrentSubscriptionPlanInfo(): CurrentSubscriptionPlanInfo {
        val snapshot = currentPlan ?: planSnapshot
        val subscriptionStatus = subscription?.status
            ?: subscription?.storedStatus
            ?: status
        val expiresAtMillis = epochTimestampToMillis(subscription?.expireAtTs)
            ?: parsePanelExpireAtMillisOrNull(subscription?.expireAt)
            ?: parsePanelExpireAtMillisOrNull(subscription?.expiresAt)
            ?: parsePanelExpireAtMillisOrNull(expireAt)
            ?: parsePanelExpireAtMillisOrNull(expiresAt)
        val isExpired = subscription?.isExpired
            ?: subscriptionStatus?.equals("EXPIRED", ignoreCase = true)
            ?: expiresAtMillis?.let { it <= System.currentTimeMillis() }
        val isActive = subscription?.isActive
            ?: subscriptionStatus?.let { it.equals("ACTIVE", ignoreCase = true) }
        val hasPlanData = snapshot != null || subscription != null ||
            !planName.isNullOrBlank() || !name.isNullOrBlank()
        return CurrentSubscriptionPlanInfo(
            displayName = snapshot?.name?.trim()?.takeIf { it.isNotBlank() }
                ?: planName?.trim()?.takeIf { it.isNotBlank() }
                ?: name?.trim()?.takeIf { it.isNotBlank() },
            trafficLimitBytes = normalizeTrafficLimitBytes(
                trafficLimitBytes = subscription?.trafficLimitBytes,
                trafficLimit = subscription?.trafficLimit,
            ) ?: normalizeTrafficLimitBytes(
                trafficLimitBytes = snapshot?.trafficLimitBytes,
                trafficLimit = snapshot?.trafficLimit,
            ),
            deviceLimit = subscription?.deviceLimit ?: snapshot?.deviceLimit,
            expiresAtMillis = expiresAtMillis,
            isActive = isActive,
            isExpired = isExpired,
            isTrial = subscription?.isTrial ?: snapshot?.isTrial,
            isUnlimited = subscription?.isUnlimited
                ?: snapshot?.type?.equals("UNLIMITED", ignoreCase = true),
            isAdmin = isAdmin == true,
            subscriptionUrl = subscription?.url?.trim()?.takeIf { it.isNotBlank() },
            renewalUrl = renewalUrl?.trim()?.takeIf { it.isNotBlank() },
            hasPlanData = hasPlanData,
        )
    }

    private fun normalizeTrafficLimitBytes(trafficLimitBytes: Long?, trafficLimit: Long?): Long? {
        if (trafficLimitBytes != null) return trafficLimitBytes
        return normalizePlanTrafficLimit(trafficLimit)
    }

    private fun normalizePlanTrafficLimit(value: Long?): Long? {
        val raw = value ?: return null
        if (raw <= 0) return 0
        // plan_snapshot.traffic_limit is GB on the server. If a future
        // response already sends bytes, keep it as-is.
        return if (raw > 1024L * 1024L) raw else raw * 1024L * 1024L * 1024L
    }

    private fun epochTimestampToMillis(value: Long?): Long? {
        val timestamp = value?.takeIf { it > 0 } ?: return null
        return if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
    }

    private fun selectBestPanelUser(
        users: List<com.tobevpn.app.data.remote.dto.PanelUserDto>,
        preferredPanelUserUuid: String?,
        preferredShortUuid: String?,
    ): com.tobevpn.app.data.remote.dto.PanelUserDto? {
        fun planRank(user: com.tobevpn.app.data.remote.dto.PanelUserDto): Int = when (planForPanelUser(user)) {
            "ADMIN" -> 3
            "PAID" -> 2
            else -> 1
        }

        return users.maxWithOrNull(
            compareBy<com.tobevpn.app.data.remote.dto.PanelUserDto>(
                { if (it.status?.uppercase(Locale.ROOT) == "ACTIVE") 1 else 0 },
                { planRank(it) },
                { if (it.trafficLimitStrategy?.uppercase(Locale.ROOT) == "MONTH") 1 else 0 },
                {
                    if (it.uuid == preferredPanelUserUuid || it.shortUuid == preferredShortUuid) {
                        1
                    } else {
                        0
                    }
                },
                { parsePanelExpireAtMillis(it.expireAt) },
            )
        )
    }

    private fun parsePanelExpireAtMillis(value: String?): Long {
        if (value.isNullOrBlank()) return Long.MIN_VALUE
        val trimmed = value.trim()
        // Try full ISO forms first so explicit offsets ("+03:00", "-05:00",
        // "Z") are honoured; the old regex-based cleanup silently treated
        // offset timestamps as UTC and failed outright on negative offsets.
        return runCatching {
            OffsetDateTime.parse(trimmed).toInstant().toEpochMilli()
        }.recoverCatching {
            Instant.parse(trimmed).toEpochMilli()
        }.recoverCatching {
            // Offset-less "2026-07-19T10:00:00[.fraction]" — panel sends UTC.
            LocalDateTime.parse(trimmed.substringBefore('.'))
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        }.getOrDefault(Long.MIN_VALUE)
    }

    private fun parsePanelExpireAtMillisOrNull(value: String?): Long? {
        return parsePanelExpireAtMillis(value).takeIf { it != Long.MIN_VALUE }
    }

    /**
     * Polls the backend for auth completion.
     * Returns true when Telegram auth is confirmed.
     */
    suspend fun checkAuthStatus(authToken: String): Boolean {
        return pollTelegramAuthStatus(authToken) == TelegramAuthPollResult.COMPLETED
    }

    suspend fun pollTelegramAuthStatus(authToken: String): TelegramAuthPollResult {
        if (authToken.isBlank()) return TelegramAuthPollResult.INVALID
        return authCompletionMutex.withLock {
            val localSession = sessionDao.getSession()
            if (localSession?.pendingAuthToken != authToken &&
                localSession?.authState == "AUTHENTICATED" &&
                localSession.telegramId != null
            ) {
                return@withLock TelegramAuthPollResult.COMPLETED
            }

            try {
                val response = botApi.checkAuthStatus(authToken)
                if (!response.success) {
                    val invalid = response.message
                        ?.lowercase(Locale.ROOT)
                        ?.let { message ->
                            "not found" in message ||
                                "expired" in message ||
                                "rejected" in message
                        }
                        ?: false
                    return@withLock if (invalid) {
                        TelegramAuthPollResult.INVALID
                    } else {
                        TelegramAuthPollResult.RETRYABLE_ERROR
                    }
                }

                val status = response.data
                    ?: return@withLock TelegramAuthPollResult.RETRYABLE_ERROR
                when (status.status.lowercase(Locale.ROOT)) {
                    "completed" -> {
                        val telegramId = status.telegramId
                            ?: return@withLock TelegramAuthPollResult.RETRYABLE_ERROR
                        // The backend has already linked the device and may
                        // have deleted its anonymous panel user. Navigation
                        // away from the screen must not cancel the local half
                        // of that irreversible transition.
                        withContext(NonCancellable) {
                            applyAuthenticatedDevice(
                                telegramId = telegramId,
                                shortUuid = status.shortUuid,
                                panelUserUuid = status.panelUserUuid,
                                clearPendingAuthToken = true,
                            )
                        }
                        SafeDiagnostics.info(TAG, "Pending Telegram authentication completed")
                        TelegramAuthPollResult.COMPLETED
                    }
                    "expired", "rejected" -> TelegramAuthPollResult.INVALID
                    else -> TelegramAuthPollResult.PENDING
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeDiagnostics.trace(
                    TAG,
                    "Pending Telegram authentication check deferred: " +
                        SafeDiagnostics.failureCategory(error),
                )
                TelegramAuthPollResult.RETRYABLE_ERROR
            }
        }
    }

    /**
     * Repairs the split state produced by older clients when the backend had
     * already linked a QR login but the local screen stopped polling first.
     */
    suspend fun reconcileLinkedIdentity(): Boolean {
        return authCompletionMutex.withLock {
            val before = sessionDao.getSession() ?: run {
                getOrCreateDeviceId()
                sessionDao.getSession()
            } ?: return@withLock false
            if (before.authState == "AUTHENTICATED" &&
                before.telegramId != null &&
                !before.shortUuid.isNullOrBlank() &&
                prefsDataStore.isServerCacheOwner(before.shortUuid)
            ) {
                return@withLock false
            }

            try {
                val tokens = bootstrapManager.bootstrap()
                if (!tokens.isLinked || tokens.telegramId == null) {
                    return@withLock false
                }
                refreshAuthenticatedClientState(
                    previousShortUuid = before.shortUuid,
                    forceProfileReset = true,
                    reason = "SERVER_IDENTITY_RECONCILIATION",
                )
                SafeDiagnostics.info(TAG, "Linked device identity reconciled from server")
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeDiagnostics.trace(
                    TAG,
                    "Linked device identity reconciliation deferred: " +
                        SafeDiagnostics.failureCategory(error),
                )
                false
            }
        }
    }

    suspend fun checkDevicePairingStatus(code: String): Result<DevicePairingPollResult> {
        return try {
            val response = botApi.checkTvPairingStatus(code)
            if (!response.success) {
                return Result.failure(
                    IllegalStateException(response.message ?: "Could not check pairing status")
                )
            }
            val status = response.data ?: return Result.success(DevicePairingPollResult.Pending)
            when (status.status) {
                "completed" -> {
                    val telegramId = status.telegramId ?: return Result.failure(
                        IllegalStateException("Pairing completed without telegram_id")
                    )
                    withContext(NonCancellable) {
                        applyAuthenticatedDevice(
                            telegramId = telegramId,
                            shortUuid = status.shortUuid,
                            panelUserUuid = status.panelUserUuid,
                            clearPendingAuthToken = false,
                        )
                    }
                    Result.success(DevicePairingPollResult.Completed)
                }
                "expired" -> Result.success(DevicePairingPollResult.Expired)
                "rejected" -> Result.failure(
                    IllegalStateException(response.message ?: "Pairing was rejected")
                )
                else -> Result.success(DevicePairingPollResult.Pending)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun applyAuthenticatedDevice(
        telegramId: Long,
        shortUuid: String?,
        panelUserUuid: String?,
        clearPendingAuthToken: Boolean,
    ) {
        val previousSession = sessionDao.getSession()
        val previousShortUuid = previousSession?.shortUuid
        val deviceId = getOrCreateDeviceId()
        sessionStore.updateOrCreate(deviceId) { current ->
            current.copy(
                authState = "AUTHENTICATED",
                telegramId = telegramId,
                shortUuid = shortUuid ?: current.shortUuid,
                panelUserUuid = panelUserUuid ?: current.panelUserUuid,
                isLinked = true,
                pendingAuthToken = if (clearPendingAuthToken) null else current.pendingAuthToken,
            )
        }
        // Server has just bound this device row. Open a fresh session so
        // bearer-token claims include telegram_id/panel_user_uuid.
        runCatching { bootstrapManager.bootstrap() }

        try {
            val panelUser = getPanelUserByTelegramId(telegramId)
            if (panelUser != null) {
                val (tgName, tgUsername) = parseTelegramProfile(panelUser.description)
                sessionStore.update { current ->
                    current.copy(
                        panelUserUuid = panelUser.uuid,
                        shortUuid = panelUser.shortUuid,
                        email = panelUser.email ?: current.email,
                        telegramName = tgName ?: current.telegramName,
                        telegramUsername = tgUsername ?: current.telegramUsername,
                    )
                }
            }
            // Fresh login / re-auth — pull the Telegram avatar once.
            refreshAvatar()
        } catch (_: Exception) {
        }

        val currentPlanInfo = getCurrentSubscriptionPlan()
        val currentSession = sessionDao.getSession()
        if (currentSession != null) {
            val resolvedPlan = resolvePlanFromCurrentPlan(currentSession.userPlan, currentPlanInfo)
            sessionStore.update { current ->
                current.copy(
                    userPlan = resolvedPlan,
                    planDisplayName = currentPlanInfo
                        ?.displayName
                        ?.takeIf { resolvedPlan != "EXPIRED" },
                    planExpiresAt = currentPlanInfo?.expiresAtMillis,
                )
            }
            currentPlanInfo?.trafficLimitBytes?.let { usageRepository.updateLimits(it, 0) }
            if (resolvedPlan != "FREE_TRIAL") {
                prefsDataStore.clearAnonymousUsageState()
                usageRepository.resetSession()
            }
        }

        registerCurrentDevice()
        refreshAuthenticatedClientState(
            previousShortUuid = previousShortUuid,
            forceProfileReset = previousSession?.authState != "AUTHENTICATED" ||
                previousSession.telegramId == null,
            reason = "AUTH_COMPLETED",
        )
    }

    private suspend fun refreshAuthenticatedClientState(
        previousShortUuid: String?,
        forceProfileReset: Boolean,
        reason: String,
    ) {
        val currentShortUuid = sessionDao.getSession()?.shortUuid
        val identityChanged = forceProfileReset || previousShortUuid != currentShortUuid

        // The server transfers and deletes the anonymous panel user during QR
        // login. Keeping its cached profile would create a tunnel that looks
        // connected but cannot pass traffic.
        if (identityChanged) {
            vpnRepository.clearCachedServers()
        }
        prefsDataStore.clearSubscriptionSyncTimestamp()
        syncSubscription(force = true)
        if (vpnRepository.getServers().isEmpty()) {
            vpnRepository.refreshServers(forceRefresh = true)
        }
        val serverCount = vpnRepository.getServers().size
        SafeDiagnostics.info(
            TAG,
            "Authenticated client state refreshed: reason=$reason " +
                "identity_changed=$identityChanged servers=$serverCount",
        )
        _subscriptionResetEvents.tryEmit(Unit)
    }

    /**
     * Syncs subscription info through the public subscription route. Linked
     * sessions use the full URL returned by the backend; anonymous sessions
     * use their subscription key with the configured fallback.
     *
     * @param overwriteUsage when true, writes server traffic usage into local usage.
     *                       Pass false during an active VPN session to avoid clobbering
     *                       the live local counter with a stale panel value.
     * @param force          when true, bypass the panel-recommended refresh
     *                       cadence (`profile-update-interval`, default 12h).
     *                       Set this for explicit user-initiated refreshes
     *                       (the Refresh button on the server list); leave it
     *                       false for ambient triggers like onResume / screen
     *                       open so we don't hammer the panel every time the
     *                       user tabs away and back.
     */
    suspend fun syncSubscription(
        overwriteUsage: Boolean = true,
        force: Boolean = false,
    ) {
        syncMutex.withLock {
            if (!force) {
                val (lastSyncAt, intervalMs) = prefsDataStore.getSubscriptionSyncState()
                if (lastSyncAt > 0 && System.currentTimeMillis() - lastSyncAt < intervalMs) {
                    return@withLock
                }
            }
            runSyncSubscription(overwriteUsage, forceRefresh = force)
        }
    }

    private fun resolvePlanFromCurrentPlan(
        cachedPlan: String,
        currentPlanInfo: CurrentSubscriptionPlanInfo?,
    ): String {
        if (currentPlanInfo == null) return cachedPlan
        if (!currentPlanInfo.hasPlanData) return "FREE_TRIAL"
        return when {
            currentPlanInfo.isExpired == true || currentPlanInfo.isActive == false -> "EXPIRED"
            currentPlanInfo.isTrial == true -> "FREE_TRIAL"
            else -> "PAID"
        }
    }

    private suspend fun runSyncSubscription(
        overwriteUsage: Boolean,
        forceRefresh: Boolean,
    ) {
        try {
            var session = sessionDao.getSession() ?: return
            var panelUser: com.tobevpn.app.data.remote.dto.PanelUserDto? = null
            var currentPlanInfo: CurrentSubscriptionPlanInfo? = null

            if (session.authState == "AUTHENTICATED" && session.telegramId != null) {
                try {
                    panelUser = getPanelUserByTelegramId(session.telegramId)
                    if (panelUser != null) {
                        val (tgName, tgUsername) = parseTelegramProfile(panelUser.description)
                        val updated = sessionStore.update { current ->
                            current.copy(
                                shortUuid = panelUser.shortUuid,
                                panelUserUuid = panelUser.uuid,
                                subscriptionUrl = panelUser.subscriptionUrl,
                                telegramName = tgName ?: current.telegramName,
                                telegramUsername = tgUsername ?: current.telegramUsername,
                            )
                        }
                        if (updated != null) session = updated
                        if (!panelUser.email.isNullOrBlank()) {
                            sessionStore.update { it.copy(email = panelUser.email) }
                        }
                    }
                } catch (error: Exception) {
                    SafeDiagnostics.warn(TAG, "Panel user refresh failed: ${SafeDiagnostics.failureCategory(error)}")
                }

                currentPlanInfo = getCurrentSubscriptionPlan()
                val resolvedPlan = resolvePlanFromCurrentPlan(session.userPlan, currentPlanInfo)
                val updated = sessionStore.update { current ->
                    current.copy(
                        userPlan = resolvedPlan,
                        planDisplayName = currentPlanInfo
                            ?.displayName
                            ?.takeIf { resolvedPlan != "EXPIRED" },
                        planExpiresAt = currentPlanInfo?.expiresAtMillis,
                        subscriptionUrl = currentPlanInfo?.subscriptionUrl ?: current.subscriptionUrl,
                        isAdminProfile = currentPlanInfo?.isAdmin ?: current.isAdminProfile,
                    )
                }
                if (updated != null) session = updated

                currentPlanInfo?.trafficLimitBytes?.let { trafficLimitBytes ->
                    usageRepository.updateLimits(trafficLimitBytes, 0)
                }
            }

            val shortUuid = session.shortUuid
            if (shortUuid == null) {
                if (session.authState == "AUTHENTICATED") {
                    registerCurrentDevice()
                }
                return
            }

            val effectiveUrl = panelUser?.subscriptionUrl
                ?: currentPlanInfo?.subscriptionUrl
                ?: session.subscriptionUrl

            var profileTrafficUsedBytes: Long? = null
            val profileUrl = effectiveUrl
            val intervalMs = runCatching {
                if (!profileUrl.isNullOrBlank()) {
                    sessionStore.update { it.copy(subscriptionUrl = profileUrl) }
                }
                val profile = subscriptionPinger.fetchProfile(
                    subscriptionUrl = profileUrl,
                    subscriptionKey = shortUuid,
                )
                if (profile != null) {
                    prefsDataStore.setSubscriptionUsageBlocked(shortUuid, profile.isUsageBlocked)
                    prefsDataStore.setUpdateRequired(profile.isUpdateRequired)
                    profile.trafficLimitBytes?.let { trafficLimitBytes ->
                        usageRepository.updateLimits(trafficLimitBytes, 0)
                    }
                    profileTrafficUsedBytes = profile.trafficUsedBytes
                    if (profile.links.isNotEmpty()) {
                        vpnRepository.updateServersFromLinks(shortUuid, profile.links)
                    } else if (profile.isSuccessful || profile.isUsageBlocked) {
                        vpnRepository.updateServersFromLinks(shortUuid, emptyList())
                    }
                }
                profile?.intervalMs
            }.onFailure { error ->
                SafeDiagnostics.warn(TAG, "Subscription profile refresh failed: ${SafeDiagnostics.failureCategory(error)}")
            }.getOrNull()
            persistSyncState(intervalMs)

            if (overwriteUsage && profileTrafficUsedBytes != null) {
                val keepDeviceScopedUsage = session.authState != "AUTHENTICATED" || session.userPlan == "FREE_TRIAL"
                val subscriptionTrafficUsedBytes = profileTrafficUsedBytes
                val trafficUsedBytes = if (keepDeviceScopedUsage) {
                    try {
                        val resp = botApi.getDeviceTraffic()
                        maxOf(resp.data?.anonTrafficBytes ?: 0, subscriptionTrafficUsedBytes)
                    } catch (_: Exception) {
                        subscriptionTrafficUsedBytes
                    }
                } else {
                    subscriptionTrafficUsedBytes
                }
                syncUsageFromServer(
                    serverBytesUsed = trafficUsedBytes,
                    isAnonymous = keepDeviceScopedUsage,
                )
            }

            if (session.authState == "AUTHENTICATED") {
                registerCurrentDevice()
            }
        } catch (error: Exception) {
            SafeDiagnostics.warn(TAG, "Subscription sync failed: ${SafeDiagnostics.failureCategory(error)}")
        }
    }

    /**
     * Updates the throttle bookkeeping after a real network sync. The
     * interval comes from the panel's `profile-update-interval` header
     * when the ping leg succeeded; otherwise we keep whatever interval
     * was previously cached (or the 12h default on first launch).
     */
    private suspend fun persistSyncState(intervalMsFromPanel: Long?) {
        val (_, cachedInterval) = prefsDataStore.getSubscriptionSyncState()
        val intervalMs = intervalMsFromPanel ?: cachedInterval
        prefsDataStore.setSubscriptionSyncState(System.currentTimeMillis(), intervalMs)
    }

    /**
     * Bare HWID-marker ping — used by the connect path so the subscription
     * service registers the device on every VPN start.
     */
    suspend fun pingHwidOnly(): Boolean {
        val session = try {
            sessionDao.getSession()
        } catch (_: Exception) {
            null
        } ?: return false
        val shortUuid = session.shortUuid ?: return false
        val wasBlocked = runCatching {
            prefsDataStore.isSubscriptionUsageBlocked(shortUuid)
        }.getOrDefault(false)
        return try {
            val result = subscriptionPinger.ping(
                subscriptionUrl = session.subscriptionUrl,
                subscriptionKey = shortUuid,
            ) ?: return wasBlocked
            prefsDataStore.setSubscriptionUsageBlocked(shortUuid, result.isUsageBlocked)
            prefsDataStore.setUpdateRequired(result.isUpdateRequired)
            result.isUsageBlocked
        } catch (_: Exception) {
            wasBlocked
        }
    }

    suspend fun saveEmail(email: String): Result<Unit> {
        return try {
            val session = sessionDao.getSession() ?: return Result.failure(Exception("No session"))
            val panelUserUuid = session.panelUserUuid ?: return Result.failure(Exception("No panel user"))
            val response = botApi.saveEmail(
                SaveEmailRequestDto(panelUserUuid = panelUserUuid, email = email)
            )
            if (response.success) {
                prefsDataStore.setEmailPromptShown()
                sessionStore.update { it.copy(email = email) }
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(response.message ?: "Failed to save email"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markEmailPromptShown() {
        prefsDataStore.setEmailPromptShown()
    }

    suspend fun logout() {
        val session = sessionDao.getSession() ?: return

        // The backend keeps the device linked to the user independently from the
        // access/refresh session. If we only call logout, the next bootstrap for
        // the same device_id comes back already linked and the app logs in again.
        // Unlink this device first, then terminate the current session.
        try {
            botApi.unlinkDevice(DeviceUnlinkRequestDto(deviceId = session.deviceId))
        } catch (_: Exception) {
            // Best effort — still attempt to terminate the session below.
        }
        try {
            botApi.logoutDevice()
        } catch (_: Exception) {
            // Best effort — proceed with local cleanup either way.
        }

        sessionStore.update { current ->
            current.copy(
                authState = "ANONYMOUS",
                telegramId = null,
                planExpiresAt = null,
                accessToken = null,
                refreshToken = null,
                accessExpiresAt = null,
                refreshExpiresAt = null,
                isLinked = false,
                shortUuid = null,
                panelUserUuid = null,
                userPlan = "FREE_TRIAL",
                planDisplayName = null,
                email = null,
                isAdminProfile = false,
                pendingAuthToken = null,
                subscriptionUrl = null,
            )
        }
        val wasUnlimited = session.userPlan == "PAID" || session.userPlan == "ADMIN"
        // Paid/Admin counters belong to the linked account. Trial counters are
        // device-scoped and must survive logout, otherwise logout/login can
        // make the same trial look unused locally.
        if (wasUnlimited) {
            prefsDataStore.clearAnonymousUsageState()
            usageRepository.resetSession()
        }
        prefsDataStore.clearPendingPurchase()
        prefsDataStore.clearSubscriptionSyncTimestamp()
        vpnRepository.clearCachedServers()
        baseStationBypassRepository.clearCachedServers()
        bootstrapManager.clear()
        // Restore an anonymous device-session immediately so the UI can fetch
        // anon traffic/limits and server list without requiring an app restart.
        // After unlink + logout the backend now returns an unlinked bootstrap.
        runCatching { bootstrapManager.ensureBootstrapped() }
        runCatching { ensurePanelUser() }
        // Force after identity change — the throttle window applies to the
        // previous user's data, not the new anonymous one.
        runCatching { syncSubscription(overwriteUsage = true, force = true) }
    }
}
