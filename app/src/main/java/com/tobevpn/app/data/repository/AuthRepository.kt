package com.tobevpn.app.data.repository

import android.os.Build
import com.tobevpn.app.data.device.DeviceIdProvider
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.SessionStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.SessionEntity
import com.tobevpn.app.data.remote.BootstrapManager
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.SubscriptionPinger
import com.tobevpn.app.data.remote.dto.AuthRequestDto
import com.tobevpn.app.data.remote.dto.DeviceRegisterRequestDto
import com.tobevpn.app.data.remote.dto.DeviceUnlinkRequestDto
import com.tobevpn.app.data.remote.dto.EnsureUserRequestDto
import com.tobevpn.app.data.remote.dto.SaveEmailRequestDto
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.UserPlan
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val sessionStore: SessionStore,
    private val prefsDataStore: PrefsDataStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val botApi: BotApi,
    private val usageRepository: UsageRepository,
    private val subscriptionPinger: SubscriptionPinger,
    private val bootstrapManager: BootstrapManager,
) {
    companion object {
        // Fallback defaults if server is unreachable
        private const val DEFAULT_FREE_TRIAL_TRAFFIC_BYTES = 1_073_741_824L // 1 GB
    }

    /** Fetches remote config from backend. Call once on app start. */
    suspend fun fetchRemoteConfig() {
        try {
            botApi.getConfig()
        } catch (_: Exception) {
            // Use defaults
        }
    }

    fun observeAuthState(): Flow<AuthState> {
        return sessionDao.observeSession().map { session ->
            if (session?.authState == "AUTHENTICATED" && session.telegramId != null) {
                AuthState.Authenticated(
                    telegramId = session.telegramId,
                    plan = UserPlan.valueOf(session.userPlan),
                    planExpiresAt = session.planExpiresAt,
                )
            } else {
                AuthState.Anonymous
            }
        }
    }

    suspend fun getOrCreateDeviceId(): String {
        val deviceId = deviceIdProvider.getOrCreate()
        // Race-safe seed of the session row — concurrent writers (bootstrap,
        // syncSubscription) all funnel through SessionStore now.
        sessionStore.updateOrCreate(deviceId) { it }
        return deviceId
    }

    /**
     * Returns the pending auth token persisted from a previous `requestTelegramAuth`,
     * or `null` if none. Used by the deep-link callback path on cold start to recover
     * the token that the in-memory `AuthViewModel.currentAuthToken` doesn't have yet.
     */
    suspend fun getPendingAuthToken(): String? {
        return sessionDao.getSession()?.pendingAuthToken?.takeIf { it.isNotBlank() }
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
            val response = botApi.registerDevice(
                DeviceRegisterRequestDto(
                    deviceName = currentDeviceName(),
                    deviceType = "phone",
                    platform = "Android",
                )
            )
            if (response.success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(response.message ?: "Could not register device"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unlinkDevice(deviceId: String): Result<Unit> {
        return try {
            val response = botApi.unlinkDevice(DeviceUnlinkRequestDto(deviceId = deviceId))
            if (response.success) {
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(response.message ?: "Could not unlink device"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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

            val response = botApi.ensureUser()
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
     * Requests Telegram auth via backend.
     * Returns Result with server-generated auth token on success.
     */
    suspend fun requestTelegramAuth(): Result<String> {
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

    private fun planForPanelUser(panelUser: com.tobevpn.app.data.remote.dto.PanelUserDto): String {
        val squads = panelUser.activeInternalSquads.map { it.name }
        return when {
            "ADMINS" in squads -> "ADMIN"
            "STANDART" in squads -> "PAID"
            else -> "FREE_TRIAL"
        }
    }

    /**
     * Polls backend for auth completion.
     * Returns true when Telegram auth is confirmed.
     */
    suspend fun checkAuthStatus(authToken: String): Boolean {
        return try {
            val response = botApi.checkAuthStatus(authToken)
            val status = response.data ?: return false

            if (status.status == "completed" && status.telegramId != null) {
                val deviceId = getOrCreateDeviceId()
                sessionStore.updateOrCreate(deviceId) { current ->
                    current.copy(
                        authState = "AUTHENTICATED",
                        telegramId = status.telegramId,
                        shortUuid = status.shortUuid ?: current.shortUuid,
                        isLinked = true,
                        pendingAuthToken = null,
                    )
                }

                // Switching identity — clear local usage counters so the
                // anonymous user's accumulated bytes/time don't bleed into
                // the newly-authenticated user's stats. The subsequent panel
                // sync will repopulate bytes from the server.
                prefsDataStore.clearAnonymousUsageState()
                usageRepository.resetSession()

                // Sync panel user info via proxy
                try {
                    val panelUsers = botApi.getUserByTelegramId(status.telegramId).response
                    val panelUser = panelUsers.firstOrNull()
                    if (panelUser != null) {
                        sessionStore.update { current ->
                            current.copy(
                                panelUserUuid = panelUser.uuid,
                                shortUuid = panelUser.shortUuid,
                                userPlan = planForPanelUser(panelUser),
                            )
                        }
                    }
                } catch (_: Exception) {
                }

                registerCurrentDevice()

                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Syncs subscription info from panel via proxy.
     * @param overwriteUsage when true, writes panel's trafficUsedBytes into local usage.
     *                       Pass false during an active VPN session to avoid clobbering
     *                       the live local counter with a stale panel value.
     */
    suspend fun syncSubscription(overwriteUsage: Boolean = true) {
        try {
            var session = sessionDao.getSession() ?: return
            var panelUser: com.tobevpn.app.data.remote.dto.PanelUserDto? = null

            // For authenticated users, refresh panel user info by telegramId
            if (session.authState == "AUTHENTICATED" && session.telegramId != null) {
                try {
                    val panelUsers = botApi.getUserByTelegramId(session.telegramId).response
                    panelUser = panelUsers.firstOrNull()
                    if (panelUser != null) {
                        val updated = sessionStore.update { current ->
                            current.copy(
                                shortUuid = panelUser.shortUuid,
                                panelUserUuid = panelUser.uuid,
                            )
                        }
                        if (updated != null) session = updated
                        // Keep locally-stored email in sync with panel
                        if (!panelUser.email.isNullOrBlank()) {
                            sessionStore.update { it.copy(email = panelUser.email) }
                        }
                    }
                } catch (_: Exception) {
                    // Fall through — use existing shortUuid
                }
            }

            val shortUuid = session.shortUuid ?: return
            val subInfo = botApi.getSubscriptionInfo(shortUuid).response
            if (!subInfo.isFound || subInfo.user == null) return

            // Direct hit on the panel's public sub URL with HWID headers — only
            // request backend actually parses for HWID device tracking.
            subscriptionPinger.ping(panelUser?.subscriptionUrl ?: subInfo.subscriptionUrl)

            val sub = subInfo.user
            val isActive = sub.isActive && sub.userStatus == "ACTIVE"

            // Determine plan from panel data
            val plan = if (!isActive) {
                "EXPIRED"
            } else if (session.authState == "AUTHENTICATED" && panelUser != null) {
                planForPanelUser(panelUser)
            } else {
                if (sub.trafficLimitStrategy == "MONTH") "PAID" else "FREE_TRIAL"
            }

            // Parse expiry date
            val expiresAtMillis = try {
                val expiresStr = sub.expiresAt
                if (expiresStr != null) {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val clean = expiresStr.replace(Regex("[.+Z].*"), "")
                    sdf.parse(clean)?.time
                } else null
            } catch (_: Exception) {
                null
            }

            sessionStore.update { it.copy(userPlan = plan, planExpiresAt = expiresAtMillis) }

            // Sync traffic limits from panel (0 = unlimited)
            val trafficLimitBytes = sub.trafficLimitBytes.toLongOrNull() ?: 0
            usageRepository.updateLimits(trafficLimitBytes, 0)

            if (overwriteUsage) {
                val isAnonymous = session.authState != "AUTHENTICATED"
                val trafficUsedBytes = if (isAnonymous) {
                    // Anonymous: device-level traffic survives panel user re-creation
                    // across login/logout cycles, so use it as source of truth.
                    try {
                        val resp = botApi.getDeviceTraffic()
                        resp.data?.anonTrafficBytes ?: (sub.trafficUsedBytes.toLongOrNull() ?: 0)
                    } catch (_: Exception) {
                        sub.trafficUsedBytes.toLongOrNull() ?: 0
                    }
                } else {
                    sub.trafficUsedBytes.toLongOrNull() ?: 0
                }
                syncUsageFromServer(
                    serverBytesUsed = trafficUsedBytes,
                    isAnonymous = isAnonymous,
                )
            }

            if (session.authState == "AUTHENTICATED") {
                registerCurrentDevice()
            }
        } catch (_: Exception) {
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
                email = null,
            )
        }
        // Switching identity — drop the previous user's local usage counters
        // (bytes will be re-populated from panel by syncSubscription below).
        prefsDataStore.clearAnonymousUsageState()
        usageRepository.resetSession()
        bootstrapManager.clear()
        // Restore an anonymous device-session immediately so the UI can fetch
        // anon traffic/limits and server list without requiring an app restart.
        // After unlink + logout the backend now returns an unlinked bootstrap.
        runCatching { bootstrapManager.ensureBootstrapped() }
        runCatching { ensurePanelUser() }
        runCatching { syncSubscription(overwriteUsage = true) }
    }
}
