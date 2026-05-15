package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.dao.ServerDao
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.ServerEntity
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.vpn.VlessUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnRepository @Inject constructor(
    private val serverDao: ServerDao,
    private val sessionDao: SessionDao,
    private val prefsDataStore: PrefsDataStore,
    private val botApi: BotApi,
) {
    fun observeServers(): Flow<List<Server>> {
        return serverDao.observeAll().map { entities ->
            // Belt-and-braces filter for the panel's "subscription expired"
            // placeholder. refreshServers() already drops it before persisting,
            // but a stale Room row from the previous version of the app could
            // still surface it on first launch after upgrade.
            entities.map { it.toDomain() }.filterNot { it.isSentinel }
        }
    }

    suspend fun refreshServers(): Result<List<Server>> {
        val shortUuid = sessionDao.getSession()?.shortUuid
        if (shortUuid.isNullOrBlank()) {
            clearServerCache()
            return Result.failure(Exception("Нет подписки"))
        }

        return try {
            val subInfo = botApi.getSubscriptionInfo(shortUuid).response
            if (!subInfo.isFound || subInfo.links.isNullOrEmpty()) {
                clearServerCache()
                return Result.failure(Exception("Подписка не найдена"))
            }

            // Fetch nodes to map IP → countryCode and status
            val nodes = try {
                botApi.getNodes().response
            } catch (_: Exception) {
                emptyList()
            }
            val countryByAddress = nodes.associate { it.address to it.countryCode }
            val disabledNodeIps = nodes
                .filter { it.isDisabled || !it.isConnected }
                .map { it.address }
                .toSet()

            val servers = subInfo.links.mapNotNull { link ->
                VlessUrlParser.parse(link)
            }.filterNot { it.isSentinel }
            // Drop the panel's "subscription expired" placeholder link so it
            // never appears in the UI. It looks like a valid VLESS URL to the
            // parser, but its uuid is all-zeros and the address points nowhere
            // — handing it to xray would SIGSEGV the native loop.

            if (servers.isEmpty()) {
                clearServerCache()
                return Result.failure(Exception("Нет доступных серверов"))
            }

            val entities = withContext(Dispatchers.IO) {
                servers.map { server ->
                    // Resolve domain to IP for node matching
                    val resolvedIp = try {
                        InetAddress.getByName(server.address).hostAddress
                    } catch (_: Exception) {
                        server.address
                    }
                    val country = countryByAddress[server.address]
                        ?: countryByAddress[resolvedIp] ?: ""
                    val online = resolvedIp !in disabledNodeIps

                    ServerEntity(
                        id = "${server.address}:${server.port}:${server.sni}",
                        name = server.name,
                        address = server.address,
                        port = server.port,
                        uuid = server.uuid,
                        flow = server.flow,
                        security = server.security,
                        sni = server.sni,
                        fingerprint = server.fingerprint,
                        publicKey = server.publicKey,
                        shortId = server.shortId,
                        network = server.network,
                        path = server.path,
                        mode = server.mode,
                        spx = server.spx,
                        country = country,
                        isOnline = online,
                    )
                }
            }

            serverDao.replaceAll(entities)
            prefsDataStore.setServerCacheOwner(shortUuid)
            Result.success(entities.map { it.toDomain() })
        } catch (e: Exception) {
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

    private suspend fun clearServerCache() {
        serverDao.deleteAll()
        prefsDataStore.clearServerCacheOwner()
    }

    suspend fun getServers(): List<Server> {
        return serverDao.getAll().map { it.toDomain() }
    }

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
