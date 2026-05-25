package com.tobevpn.app.data.remote

import com.tobevpn.app.data.device.DeviceIdProvider
import com.tobevpn.app.data.device.DeviceFingerprintProvider
import com.tobevpn.app.data.local.SessionStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.SessionEntity
import com.tobevpn.app.data.remote.dto.BootstrapRequestDto
import com.tobevpn.app.data.remote.dto.RefreshRequestDto
import com.tobevpn.app.data.remote.dto.SessionTokensDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns per-device session auth.
 *
 * Backend flow: bootstrap → access (30 min) + refresh (90 days); access expires → refresh →
 * rotated pair; refresh invalid → bootstrap again.
 */
@Singleton
class BootstrapManager @Inject constructor(
    private val bootstrapApi: BootstrapApi,
    private val sessionDao: SessionDao,
    private val sessionStore: SessionStore,
    private val deviceIdProvider: DeviceIdProvider,
    private val fingerprintProvider: DeviceFingerprintProvider,
) {
    private val mutex = Mutex()
    private val tokensRef = AtomicReference<StoredTokens?>(null)

    @Volatile
    private var hydrated = false

    data class StoredTokens(
        val accessToken: String,
        val refreshToken: String,
        val accessExpiresAt: Long,
        val refreshExpiresAt: Long,
        val deviceId: String,
        val telegramId: Long?,
        val panelUserUuid: String?,
        val shortUuid: String?,
        val isLinked: Boolean,
    )

    fun currentAccessToken(): String? {
        val t = tokensRef.get() ?: return null
        // 5s grace to avoid sending a soon-to-expire token.
        return if (t.accessExpiresAt - System.currentTimeMillis() > 5_000) t.accessToken else null
    }

    suspend fun hydrateFromDb() {
        if (hydrated) return
        mutex.withLock {
            if (hydrated) return
            val session = sessionDao.getSession()
            if (session?.accessToken != null &&
                session.refreshToken != null &&
                session.accessExpiresAt != null &&
                session.refreshExpiresAt != null
            ) {
                tokensRef.set(
                    StoredTokens(
                        accessToken = session.accessToken,
                        refreshToken = session.refreshToken,
                        accessExpiresAt = session.accessExpiresAt,
                        refreshExpiresAt = session.refreshExpiresAt,
                        deviceId = session.deviceId,
                        telegramId = session.telegramId,
                        panelUserUuid = session.panelUserUuid,
                        shortUuid = session.shortUuid,
                        isLinked = session.isLinked,
                    )
                )
            }
            hydrated = true
        }
    }

    /** Called at app startup. Guarantees valid tokens are present or a bootstrap is attempted. */
    suspend fun ensureBootstrapped() {
        hydrateFromDb()
        val now = System.currentTimeMillis()
        val tokens = tokensRef.get()
        when {
            tokens == null -> runCatching { bootstrap() }
            tokens.refreshExpiresAt <= now -> runCatching { bootstrap() }
            tokens.accessExpiresAt <= now -> runCatching { refreshNow() }.onFailure {
                runCatching { bootstrap() }
            }
            else -> Unit
        }
    }

    suspend fun bootstrap(): StoredTokens = mutex.withLock {
        val deviceId = deviceIdProvider.getOrCreate()
        val fingerprint = fingerprintProvider.get()
        val response = bootstrapApi.bootstrap(
            BootstrapRequestDto(
                deviceId = deviceId,
                platform = "android",
                hwid = fingerprint.hwid,
                deviceOs = fingerprint.platform,
                osVersion = fingerprint.osVersion,
                deviceModel = fingerprint.model,
                userAgent = fingerprint.userAgent,
            )
        )
        val data = response.data
            ?: error(response.message ?: "Bootstrap failed")
        persist(data)
    }

    suspend fun refreshNow(): StoredTokens = mutex.withLock {
        val refresh = tokensRef.get()?.refreshToken
            ?: error("No refresh token")
        val response = bootstrapApi.refresh(RefreshRequestDto(refreshToken = refresh))
        val data = response.data
            ?: error(response.message ?: "Refresh failed")
        persist(data)
    }

    /** Called by TokenAuthenticator on 401. Returns a new access token or null. */
    suspend fun refreshOrBootstrapAfter401(oldAccessToken: String?): String? {
        val snapshot = tokensRef.get()
        if (snapshot != null && oldAccessToken != null && snapshot.accessToken != oldAccessToken) {
            // Another thread already refreshed — reuse.
            return snapshot.accessToken
        }
        return try {
            if (snapshot?.refreshToken != null &&
                snapshot.refreshExpiresAt > System.currentTimeMillis()
            ) {
                refreshNow().accessToken
            } else {
                bootstrap().accessToken
            }
        } catch (_: Exception) {
            // Refresh failed — try bootstrap once more.
            runCatching { bootstrap().accessToken }.getOrNull()
        }
    }

    suspend fun clear() = mutex.withLock {
        tokensRef.set(null)
    }

    private suspend fun persist(data: SessionTokensDto): StoredTokens {
        val now = System.currentTimeMillis()
        val stored = StoredTokens(
            accessToken = data.accessToken,
            refreshToken = data.refreshToken,
            accessExpiresAt = now + data.expiresIn * 1000,
            refreshExpiresAt = now + data.refreshExpiresIn * 1000,
            deviceId = data.deviceId,
            telegramId = data.telegramId,
            panelUserUuid = data.panelUserUuid,
            shortUuid = data.shortUuid,
            isLinked = data.isLinked,
        )
        tokensRef.set(stored)
        val responseSaysAuthenticated = stored.isLinked && stored.telegramId != null
        sessionStore.updateOrCreate(stored.deviceId) { session ->
            val wasAuthenticated = session.authState == "AUTHENTICATED" && session.telegramId != null
            when {
                responseSaysAuthenticated -> session.copy(
                    deviceId = stored.deviceId,
                    accessToken = stored.accessToken,
                    refreshToken = stored.refreshToken,
                    accessExpiresAt = stored.accessExpiresAt,
                    refreshExpiresAt = stored.refreshExpiresAt,
                    authState = "AUTHENTICATED",
                    isLinked = true,
                    telegramId = stored.telegramId,
                    panelUserUuid = stored.panelUserUuid ?: session.panelUserUuid,
                    shortUuid = stored.shortUuid ?: session.shortUuid,
                    pendingAuthToken = null,
                )
                // The /auth/refresh endpoint can echo back stale isLinked=false even
                // after link auth completed via /auth/check, because the original
                // session was opened anonymously. Without this guard, every token
                // refresh would silently downgrade the user to ANONYMOUS, then
                // ensurePanelUser would re-upgrade, then the next refresh downgrades
                // again — UI flickers between authed/anon. So persist() only ever
                // updates tokens here; identity downgrades happen exclusively via
                // explicit logout()/unlinkDevice().
                wasAuthenticated -> session.copy(
                    deviceId = stored.deviceId,
                    accessToken = stored.accessToken,
                    refreshToken = stored.refreshToken,
                    accessExpiresAt = stored.accessExpiresAt,
                    refreshExpiresAt = stored.refreshExpiresAt,
                )
                else -> session.copy(
                    deviceId = stored.deviceId,
                    accessToken = stored.accessToken,
                    refreshToken = stored.refreshToken,
                    accessExpiresAt = stored.accessExpiresAt,
                    refreshExpiresAt = stored.refreshExpiresAt,
                    authState = "ANONYMOUS",
                    isLinked = false,
                    telegramId = null,
                )
            }
        }
        return stored
    }
}
