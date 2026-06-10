package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.dao.ServerDao
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.ServerEntity
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.SubscriptionPinger
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.vpn.VlessUrlParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
    private val prefsDataStore: PrefsDataStore,
    private val botApi: BotApi,
    private val subscriptionPinger: SubscriptionPinger,
) {
    private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshGeneration = AtomicLong(0L)

    fun observeServers(): Flow<List<Server>> {
        return serverDao.observeAll().map { entities ->
            // Belt-and-braces filter for the panel's "subscription expired"
            // placeholder. refreshServers() already drops it before persisting,
            // but a stale Room row from the previous version of the app could
            // still surface it on first launch after upgrade.
            entities.map { it.toDomain() }.filterNot { it.isSentinel }
        }
    }

    suspend fun refreshServers(forceRefresh: Boolean = false): Result<List<Server>> {
        val session = sessionDao.getSession()
        val shortUuid = session?.shortUuid
        if (shortUuid.isNullOrBlank()) {
            clearServerCache()
            return Result.failure(Exception("Нет подписки"))
        }

        return try {
            val profile = subscriptionPinger.fetchProfile(
                subscriptionUrl = session.subscriptionUrl,
                subscriptionKey = shortUuid,
            )
                ?: throw IOException("Subscription profile unavailable")
            prefsDataStore.setSubscriptionUsageBlocked(shortUuid, profile.isUsageBlocked)
            prefsDataStore.setUpdateRequired(profile.isUpdateRequired)
            if (profile.links.isEmpty() && !profile.isSuccessful && !profile.isUsageBlocked) {
                throw IOException("Subscription profile unavailable")
            }
            updateServersFromLinks(shortUuid, profile.links)
        } catch (e: Exception) {
            SafeDiagnostics.warn(TAG, "Server refresh failed; checking local cache: ${SafeDiagnostics.failureCategory(e)}")
            val cached = if (prefsDataStore.isServerCacheOwner(shortUuid)) {
                serverDao.getAll().map { it.toDomain() }.filterNot { it.isSentinel }
            } else {
                clearServerCache()
                emptyList()
            }
            if (cached.isNotEmpty()) {
                Result.success(cached)
            } else {
                Result.failure(e)
            }
        }
    }

    suspend fun updateServersFromLinks(
        shortUuid: String,
        links: List<String>,
    ): Result<List<Server>> {
        if (links.isEmpty()) {
            clearServerCache()
            return Result.failure(Exception("Подписка не найдена"))
        }

        val servers = links.mapNotNull { link -> VlessUrlParser.parse(link) }
            .filterNot { it.isSentinel }
        // Drop the panel's "subscription expired" placeholder link so it
        // never appears in the UI or reaches xray.
        if (servers.isEmpty()) {
            clearServerCache()
            return Result.failure(Exception("Нет доступных серверов"))
        }

        val cachedById = serverDao.getAll().associateBy { it.id }
        val entities = servers.map { server ->
            val id = serverId(server)
            val cached = cachedById[id]
            server.toEntity(
                country = cached?.country.orEmpty(),
                isOnline = cached?.isOnline ?: true,
            )
        }
        val generation = refreshGeneration.incrementAndGet()
        serverDao.replaceAll(entities)
        prefsDataStore.setServerCacheOwner(shortUuid)
        enrichMetadataInBackground(shortUuid, generation, servers)
        return Result.success(entities.map { it.toDomain() })
    }

    suspend fun clearCachedServers() {
        clearServerCache()
    }

    private fun enrichMetadataInBackground(
        shortUuid: String,
        generation: Long,
        servers: List<Server>,
    ) {
        enrichmentScope.launch {
            try {
                val nodes = botApi.getNodes().response
                val countryByAddress = nodes.associate { it.address to it.countryCode }
                val disabledNodeIps = nodes
                    .filter { it.isDisabled || !it.isConnected }
                    .map { it.address }
                    .toSet()

                val enriched = coroutineScope {
                    servers.map { server ->
                        async {
                            val resolvedIp = try {
                                InetAddress.getByName(server.address).hostAddress
                            } catch (_: Exception) {
                                server.address
                            }
                            server.toEntity(
                                country = countryByAddress[server.address]
                                    ?: countryByAddress[resolvedIp]
                                    ?: "",
                                isOnline = resolvedIp !in disabledNodeIps,
                            )
                        }
                    }.awaitAll()
                }

                val currentShortUuid = sessionDao.getSession()?.shortUuid
                if (refreshGeneration.get() == generation && currentShortUuid == shortUuid) {
                    serverDao.replaceAll(enriched)
                    prefsDataStore.setServerCacheOwner(shortUuid)
                }
            } catch (error: Exception) {
                SafeDiagnostics.warn(TAG, "Node metadata refresh failed: ${SafeDiagnostics.failureCategory(error)}")
            }
        }
    }

    private suspend fun clearServerCache() {
        refreshGeneration.incrementAndGet()
        serverDao.deleteAll()
        prefsDataStore.clearServerCacheOwner()
    }

    private companion object {
        const val TAG = "VpnRepository"
    }

    suspend fun getServers(): List<Server> {
        return serverDao.getAll().map { it.toDomain() }
    }

    private fun serverId(server: Server) = "${server.address}:${server.port}:${server.sni}"

    private fun Server.toEntity(
        country: String,
        isOnline: Boolean,
    ) = ServerEntity(
        id = serverId(this),
        name = name,
        address = address,
        port = port,
        uuid = uuid,
        flow = flow,
        security = security,
        sni = sni,
        fingerprint = fingerprint,
        publicKey = publicKey,
        shortId = shortId,
        network = network,
        path = path,
        mode = mode,
        spx = spx,
        country = country,
        isOnline = isOnline,
    )

    private fun ServerEntity.toDomain() = Server(
        id = id,
        name = name,
        address = address,
        port = port,
        uuid = uuid,
        flow = flow,
        security = security,
        sni = sni,
        fingerprint = fingerprint,
        publicKey = publicKey,
        shortId = shortId,
        network = network,
        path = path,
        mode = mode,
        spx = spx,
        country = country,
        isOnline = isOnline,
    )
}
