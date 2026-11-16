package com.tobevpn.app.router

import android.os.SystemClock
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.SubscriptionPinger
import com.tobevpn.app.data.remote.buildSubscriptionUrlByKey
import com.tobevpn.app.data.repository.VpnRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings the router onto the account's current credentials after its link
 * was reset (another device unlinked with a reset, the bot): the router would
 * otherwise keep logging in with the revoked ones and quietly send the home
 * direct.
 *
 * It writes only on proof, never on a guess: the router's link and this
 * app's link differ, the router is this account's, and, asked one right
 * after the other in the same way, this app's link answers with servers
 * while the router's is refused. A timeout or network error is "not known",
 * and nothing is written; a router link that still answers is never touched
 * (so revoked credentials can never replace live ones).
 */
@Singleton
class RouterCredentials @Inject constructor(
    private val repository: RouterRepository,
    private val vpnRepository: VpnRepository,
    private val sessionDao: SessionDao,
    private val botApi: BotApi,
    private val pinger: SubscriptionPinger,
) {
    enum class Result { UPDATED, CURRENT, NOT_KNOWN }

    private val mutex = Mutex()
    private var lastBackgroundAt = 0L

    /**
     * The background check (Home), at most every [BACKGROUND_EVERY_MS]; the
     * Router screen asks at each visit through [repair].
     */
    suspend fun repairInBackground(): Result {
        val now = SystemClock.elapsedRealtime()
        if (lastBackgroundAt != 0L && now - lastBackgroundAt < BACKGROUND_EVERY_MS) return Result.NOT_KNOWN
        return repair().also { if (it != Result.NOT_KNOWN) lastBackgroundAt = now }
    }

    /** [Result.UPDATED] when the router now has this account's credentials anew. */
    suspend fun repair(beforeWrite: () -> Boolean = { true }): Result = mutex.withLock {
        try {
            repairNow(beforeWrite)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Result.NOT_KNOWN
        }
    }

    private suspend fun repairNow(beforeWrite: () -> Boolean): Result {
        val key = sessionDao.getSession()?.shortUuid?.takeIf { it.isNotBlank() } ?: return Result.NOT_KNOWN
        val router = repository.subscriptionLink() ?: return Result.NOT_KNOWN
        if (key in router.url) return Result.CURRENT
        // Only this account's router gets this account's credentials (a list
        // that cannot be read stops here too).
        val hwid = router.hwid ?: return Result.NOT_KNOWN
        // An account list that cannot be read is "not known", not "another account's".
        val devices = botApi.getDevices().data?.devices ?: return Result.NOT_KNOWN
        val accountRouters = devices.map { it.hwid ?: it.deviceId }
        if (accountRouters.none { it.equals(hwid, ignoreCase = true) }) return Result.CURRENT
        val ownUrl = buildSubscriptionUrlByKey(BuildConfig.SUBSCRIPTION_BASE_URL, key)?.toString() ?: return Result.NOT_KNOWN
        if (linkState(ownUrl, key) != LinkState.LIVE) return Result.NOT_KNOWN
        val routerKey = router.url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        when (linkState(router.url, routerKey)) {
            LinkState.LIVE -> return Result.CURRENT
            LinkState.UNKNOWN -> return Result.NOT_KNOWN
            LinkState.DEAD -> Unit
        }
        val servers = vpnRepository.refreshServers(forceRefresh = true).getOrNull()
            ?.filter { it.isAvailable }?.takeIf { it.isNotEmpty() } ?: return Result.NOT_KNOWN
        if (!beforeWrite()) return Result.NOT_KNOWN
        return if (repository.rewriteServers(servers, ownUrl)) Result.UPDATED else Result.NOT_KNOWN
    }

    private enum class LinkState { LIVE, DEAD, UNKNOWN }

    private suspend fun linkState(url: String, key: String): LinkState {
        val profile = pinger.fetchProfile(subscriptionUrl = url, subscriptionKey = key) ?: return LinkState.UNKNOWN
        return when {
            profile.isSuccessful && profile.links.isNotEmpty() -> LinkState.LIVE
            // A blocked (expired) subscription still exists: not a revoked link.
            profile.isUsageBlocked -> LinkState.LIVE
            !profile.isSuccessful && profile.links.isEmpty() -> LinkState.DEAD
            else -> LinkState.UNKNOWN
        }
    }

    private companion object {
        const val BACKGROUND_EVERY_MS = 6 * 60 * 60 * 1000L
    }
}
