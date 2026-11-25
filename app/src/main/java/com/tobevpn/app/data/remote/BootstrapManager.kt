package com.tobevpn.app.data.remote

import com.tobevpn.app.data.device.DeviceIdProvider
import com.tobevpn.app.data.local.SessionStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.SessionEntity
import com.tobevpn.app.data.remote.dto.BootstrapRequestDto
import com.tobevpn.app.data.remote.dto.RefreshRequestDto
import com.tobevpn.app.data.remote.dto.SessionTokensDto
import com.tobevpn.app.data.remote.dto.mergePanelUserUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import java.io.IOException
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
) {
    private val mutex = Mutex()
    private val tokensRef = AtomicReference<StoredTokens?>(null)

    @Volatile
    private var hydrated = false

    private val _newSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * A bootstrap created a new server session (a refresh keeps the old one):
     * what the server kept per session, such as the push registration, is gone.
     */
    val newSessions: SharedFlow<Unit> = _newSessions.asSharedFlow()

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

    /**
     * A refresh token that has not expired is still held. After a 401 this
     * tells a refresh that failed for a transient reason (network, bot down:
     * the tokens stay) from one the server refused (they are replaced).
     */
    fun hasUsableRefreshToken(): Boolean {
        val t = tokensRef.get() ?: return false
        return t.refreshExpiresAt - System.currentTimeMillis() > 5_000
    }

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
            // A refresh the server rejects needs a new session; a network
            // error or a busy server does not (the next request refreshes
            // again), so the session and its Telegram confirmation stay.
            tokens.accessExpiresAt <= now -> runCatching { refreshNow() }.onFailure {
                if (it.isRejectedRefresh()) runCatching { bootstrap() }
            }
            else -> Unit
        }
    }

    suspend fun bootstrap(): StoredTokens = mutex.withLock {
        val deviceId = deviceIdProvider.getOrCreate()
        val response = bootstrapApi.bootstrap(
            BootstrapRequestDto(
                deviceId = deviceId,
                platform = "android",
            )
        )
        val data = response.data
            ?: error(response.message ?: "Bootstrap failed")
        persist(data).also { _newSessions.tryEmit(Unit) }
    }

    /**
     * New tokens for the session this install already has. The bot writes
     * the Telegram confirmation into that session when the user signs in;
     * refresh keeps it, while bootstrap replaces the session and drops it,
     * so the server stops sending announcements meant for the account
     * (subscription or plan audiences, pushes included). Bootstrap only when
     * there is no usable refresh token or the server rejects it.
     */
    suspend fun renewSession(): StoredTokens {
        hydrateFromDb()
        val tokens = tokensRef.get()
        if (tokens != null && tokens.refreshExpiresAt > System.currentTimeMillis()) {
            for (attempt in 0 until REFRESH_ATTEMPTS) {
                try {
                    return refreshNow()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.isRejectedRefresh()) break
                    // Timeout, network, 429 or 5xx: the session is still
                    // good, ask again after a pause instead of replacing it.
                    if (attempt == REFRESH_ATTEMPTS - 1) throw e
                    delay(REFRESH_RETRY_MS * (attempt + 1))
                }
            }
        }
        return bootstrap()
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
        } catch (e: Exception) {
            // Only a refresh the server rejected needs a new session; after a
            // network error or a busy server the session (and its Telegram
            // confirmation) is kept and the request just fails this time.
            if (e.isRejectedRefresh()) runCatching { bootstrap().accessToken }.getOrNull() else null
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
                    panelUserUuid = mergePanelUserUuid(
                        session.panelUserUuid, session.shortUuid,
                        stored.panelUserUuid, stored.shortUuid,
                    ),
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
                    panelUserUuid = mergePanelUserUuid(
                        session.panelUserUuid, session.shortUuid,
                        stored.panelUserUuid, stored.shortUuid,
                    ),
                    shortUuid = stored.shortUuid ?: session.shortUuid,
                )
            }
        }
        return stored
    }
}

private const val REFRESH_ATTEMPTS = 3
private const val REFRESH_RETRY_MS = 2_000L

/**
 * The server turned the refresh token down (401/403, or an answer without
 * tokens), so only a new session helps. Timeouts, network errors, 429 and
 * 5xx are temporary and keep the session.
 */
private fun Throwable.isRejectedRefresh(): Boolean = when (this) {
    is IOException -> false
    is HttpException -> code() != 429 && code() < 500
    else -> true
}
