package com.tobevpn.app.data.repository

import android.os.SystemClock
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.PanelSubInfoDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shares the short-lived subscription response between plan sync and server
 * refresh. These paths often run together on app start and screen entry.
 */
@Singleton
class SubscriptionInfoProvider @Inject constructor(
    private val botApi: BotApi,
) {
    private val mutex = Mutex()
    private var cached: CachedResponse? = null

    suspend fun get(
        shortUuid: String,
        forceRefresh: Boolean = false,
    ): PanelSubInfoDto {
        val requestedAtMs = SystemClock.elapsedRealtime()
        return mutex.withLock {
            val now = SystemClock.elapsedRealtime()
            val existing = cached
            val freshEnough = existing != null && now - existing.receivedAtMs <= CACHE_TTL_MS
            val refreshedForThisCall = existing != null && existing.receivedAtMs >= requestedAtMs
            if (existing != null &&
                existing.shortUuid == shortUuid &&
                ((!forceRefresh && freshEnough) || refreshedForThisCall)
            ) {
                return@withLock existing.value
            }

            botApi.getSubscriptionInfo(shortUuid).response.also { value ->
                cached = CachedResponse(
                    shortUuid = shortUuid,
                    receivedAtMs = SystemClock.elapsedRealtime(),
                    value = value,
                )
            }
        }
    }

    suspend fun invalidate(shortUuid: String) {
        mutex.withLock {
            if (cached?.shortUuid == shortUuid) {
                cached = null
            }
        }
    }

    private data class CachedResponse(
        val shortUuid: String,
        val receivedAtMs: Long,
        val value: PanelSubInfoDto,
    )

    private companion object {
        const val CACHE_TTL_MS = 5_000L
    }
}
