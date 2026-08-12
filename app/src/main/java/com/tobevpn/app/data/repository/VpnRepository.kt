package com.tobevpn.app.data.repository

import android.os.SystemClock
import com.tobevpn.app.data.local.dao.ServerDao
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.ServerEntity
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.SubscriptionPinger
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.presentation.components.serverCountryCodeForUi
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
import java.util.Locale
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
            entities.map { it.toDomain() }.filter { it.isAvailable }
        }
    }

    suspend fun refreshServers(forceRefresh: Boolean = false): Result<List<Server>> {
        val startedAt = SystemClock.elapsedRealtime()
        val session = sessionDao.getSession()
        val shortUuid = session?.shortUuid
        if (shortUuid.isNullOrBlank()) {
            SafeDiagnostics.warn(TAG, "Server refresh skipped: SUBSCRIPTION_MISSING")
            return Result.failure(Exception("Нет подписки"))
        }
        SafeDiagnostics.trace(
            TAG,
            "Server refresh started: force=$forceRefresh",
        )

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
            if (profile.isUsageBlocked) {
                SafeDiagnostics.warn(TAG, "Server refresh blocked by subscription profile")
                clearServerCache()
                return Result.failure(Exception("Доступ к подписке ограничен"))
            }
            updateServersFromLinks(shortUuid, profile.links).also { result ->
                result.onSuccess { servers ->
                    SafeDiagnostics.trace(
                        TAG,
                        "Server refresh completed: source=NETWORK count=${servers.size} " +
                            "duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                    )
                }
                result.onFailure { error ->
                    SafeDiagnostics.warn(
                        TAG,
                        "Server profile processing failed: ${SafeDiagnostics.failureSummary(error)}",
                    )
                }
            }
        } catch (e: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "Server refresh failed; checking local cache: ${SafeDiagnostics.failureSummary(e)}",
            )
            val cached = getOwnedCachedServers(shortUuid)
            if (cached.isNotEmpty()) {
                SafeDiagnostics.trace(
                    TAG,
                    "Server refresh completed: source=CACHE count=${cached.size} " +
                        "duration_ms=${SystemClock.elapsedRealtime() - startedAt}",
                )
                Result.success(cached)
            } else {
                SafeDiagnostics.warn(TAG, "Server refresh cache fallback unavailable")
                clearServerCache()
                Result.failure(e)
            }
        }
    }

    suspend fun updateServersFromLinks(
        shortUuid: String,
        links: List<String>,
    ): Result<List<Server>> {
        if (links.isEmpty()) {
            keepOwnedCacheOnRefreshMiss(shortUuid)?.let { return Result.success(it) }
            clearServerCache()
            return Result.failure(Exception("Подписка не найдена"))
        }

        val parsedServers = links.mapNotNull { link -> VlessUrlParser.parse(link) }
        val servers = parsedServers.filter { it.isAvailable }
        SafeDiagnostics.trace(
            TAG,
            "Subscription server profile parsed: links=${links.size} " +
                "parsed=${parsedServers.size} sentinel=${parsedServers.count(Server::isSentinel)} " +
                "incompatible=${parsedServers.count { !it.isSentinel && !it.isXrayCompatible }} " +
                "usable=${servers.size}",
        )
        // Drop the panel's "subscription expired" placeholder link so it
        // never appears in the UI or reaches xray.
        if (servers.isEmpty()) {
            if (parsedServers.none { it.isSentinel }) {
                keepOwnedCacheOnRefreshMiss(shortUuid)?.let { return Result.success(it) }
            }
            clearServerCache()
            return Result.failure(Exception("Нет доступных серверов"))
        }

        val cachedServers = serverDao.getAll()
        val cachedById = cachedServers.associateBy { it.id }
        val cachedCountryByName = cachedServers
            .filter { it.country.isNotBlank() }
            .associateBy({ normalizedServerName(it.name) }, ServerEntity::country)
        val cachedCountryByAddress = cachedServers
            .filter { it.country.isNotBlank() }
            .associateBy({ it.address.lowercase(Locale.ROOT) }, ServerEntity::country)
        val stableServers = keepStableServerOrder(
            servers = servers,
            cachedServers = cachedServers,
        )
        val entities = stableServers.mapIndexed { index, server ->
            val id = serverId(server)
            val cached = cachedById[id]
            val cachedCountry = cached?.country?.takeIf(String::isNotBlank)
                ?: cachedCountryByName[normalizedServerName(server.name)]
                ?: cachedCountryByAddress[server.address.lowercase(Locale.ROOT)]
            server.toEntity(
                country = resolveRefreshedServerCountry(
                    serverName = server.name,
                    freshCountry = null,
                    fallbackCountry = cachedCountry,
                ),
                isOnline = cached?.isOnline ?: true,
                sortOrder = index,
            )
        }
        val generation = refreshGeneration.incrementAndGet()
        serverDao.replaceAll(entities)
        prefsDataStore.setServerCacheOwner(shortUuid)
        SafeDiagnostics.trace(
            TAG,
            "Server cache replaced: previous=${cachedServers.size} current=${entities.size}",
        )
        enrichMetadataInBackground(
            shortUuid = shortUuid,
            generation = generation,
            servers = stableServers,
            initialEntities = entities,
        )
        return Result.success(entities.map { it.toDomain() })
    }

    suspend fun clearCachedServers() {
        clearServerCache()
    }

    private fun enrichMetadataInBackground(
        shortUuid: String,
        generation: Long,
        servers: List<Server>,
        initialEntities: List<ServerEntity>,
    ) {
        enrichmentScope.launch {
            try {
                val nodes = botApi.getNodes().response
                val countryByAddress = nodes.associate { it.address to it.countryCode }
                val disabledNodeIps = nodes
                    .filter { it.isDisabled || !it.isConnected }
                    .map { it.address }
                    .toSet()
                val initialById = initialEntities.associateBy(ServerEntity::id)

                val enriched = coroutineScope {
                    servers.mapIndexed { index, server ->
                        async {
                            val resolvedIp = try {
                                InetAddress.getByName(server.address).hostAddress
                            } catch (_: Exception) {
                                server.address
                            }
                            val freshCountry = countryByAddress[server.address]
                                ?.takeIf(String::isNotBlank)
                                ?: countryByAddress[resolvedIp]?.takeIf(String::isNotBlank)
                            server.toEntity(
                                country = resolveRefreshedServerCountry(
                                    serverName = server.name,
                                    freshCountry = freshCountry,
                                    fallbackCountry = initialById[serverId(server)]?.country,
                                ),
                                isOnline = resolvedIp !in disabledNodeIps,
                                sortOrder = index,
                            )
                        }
                    }.awaitAll()
                }

                val currentShortUuid = sessionDao.getSession()?.shortUuid
                if (refreshGeneration.get() == generation && currentShortUuid == shortUuid) {
                    serverDao.replaceAll(enriched)
                    prefsDataStore.setServerCacheOwner(shortUuid)
                    SafeDiagnostics.trace(
                        TAG,
                        "Server metadata applied: count=${enriched.size} " +
                            "online=${enriched.count(ServerEntity::isOnline)} " +
                            "offline=${enriched.count { !it.isOnline }}",
                    )
                } else {
                    SafeDiagnostics.trace(TAG, "Stale server metadata result discarded")
                }
            } catch (error: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "Node metadata refresh failed: ${SafeDiagnostics.failureSummary(error)}",
                )
            }
        }
    }

    private suspend fun clearServerCache() {
        refreshGeneration.incrementAndGet()
        serverDao.deleteAll()
        prefsDataStore.clearServerCacheOwner()
        SafeDiagnostics.trace(TAG, "Server cache cleared")
    }

    private fun keepStableServerOrder(
        servers: List<Server>,
        cachedServers: List<ServerEntity>,
    ): List<Server> {
        if (cachedServers.isEmpty()) {
            return servers.sortedWith(
                compareBy<Server> { serverNameParts(it.name).base }
                    .thenBy { serverNameParts(it.name).number ?: Int.MAX_VALUE }
                    .thenBy { it.name.lowercase(Locale.ROOT) }
                    .thenBy { serverId(it) },
            )
        }
        val cachedPositionById = cachedServers
            .mapIndexed { index, entity -> entity.id to index }
            .toMap()
        val cachedGroupPositionByName = cachedServers
            .mapIndexed { index, entity -> serverNameParts(entity.name).base to index }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, positions) -> positions.min() }

        return servers
            .mapIndexed { profileIndex, server ->
                val nameParts = serverNameParts(server.name)
                OrderedServer(
                    server = server,
                    cachedPosition = cachedPositionById[serverId(server)],
                    cachedGroupPosition = cachedGroupPositionByName[nameParts.base],
                    nameParts = nameParts,
                    profileIndex = profileIndex,
                )
            }
            .sortedWith(
                compareBy<OrderedServer> {
                    if (it.nameParts.number != null) {
                        it.cachedGroupPosition ?: it.cachedPosition ?: Int.MAX_VALUE
                    } else {
                        it.cachedPosition ?: it.cachedGroupPosition ?: Int.MAX_VALUE
                    }
                }
                    .thenBy { it.nameParts.base }
                    .thenBy { it.nameParts.number ?: Int.MAX_VALUE }
                    .thenBy { it.server.name.lowercase(Locale.ROOT) }
                    .thenBy { serverId(it.server) }
                    .thenBy { it.profileIndex },
            )
            .map { it.server }
    }

    private fun serverNameParts(name: String): ServerNameParts {
        val normalized = normalizedServerName(name)
        val match = TRAILING_NUMBER_REGEX.matchEntire(normalized)
        return if (match != null) {
            ServerNameParts(
                base = match.groupValues[1].trim(),
                number = match.groupValues[2].toIntOrNull(),
            )
        } else {
            ServerNameParts(base = normalized, number = null)
        }
    }

    private suspend fun getOwnedCachedServers(shortUuid: String): List<Server> {
        if (!prefsDataStore.isServerCacheOwner(shortUuid)) return emptyList()
        return serverDao.getAll().map { it.toDomain() }.filter { it.isAvailable }
    }

    private suspend fun keepOwnedCacheOnRefreshMiss(shortUuid: String): List<Server>? {
        val session = sessionDao.getSession()
        if (session?.shortUuid != shortUuid) return null
        if (session.userPlan == "EXPIRED") return null
        if (prefsDataStore.isSubscriptionUsageBlocked(shortUuid)) return null
        return getOwnedCachedServers(shortUuid).takeIf { it.isNotEmpty() }
    }

    private companion object {
        const val TAG = "VpnRepository"
        val TRAILING_NUMBER_REGEX = Regex("""^(.*?)[\s#_-]+(\d+)$""")
    }

    private data class OrderedServer(
        val server: Server,
        val cachedPosition: Int?,
        val cachedGroupPosition: Int?,
        val nameParts: ServerNameParts,
        val profileIndex: Int,
    )

    private data class ServerNameParts(
        val base: String,
        val number: Int?,
    )

    suspend fun getServers(): List<Server> {
        return serverDao.getAll().map { it.toDomain() }.filter { it.isAvailable }
    }

    private fun serverId(server: Server) = "${server.address}:${server.port}:${server.sni}"

    private fun normalizedServerName(name: String): String = name
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)

    private fun Server.toEntity(
        country: String,
        isOnline: Boolean,
        sortOrder: Int,
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
        host = host,
        alpn = alpn,
        headerType = headerType,
        serviceName = serviceName,
        extra = extra,
        mode = mode,
        spx = spx,
        country = country,
        isOnline = isOnline,
        sortOrder = sortOrder,
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
        host = host,
        alpn = alpn,
        headerType = headerType,
        serviceName = serviceName,
        extra = extra,
        mode = mode,
        spx = spx,
        country = country,
        isOnline = isOnline,
    )
}

/**
 * A refresh is published before optional node metadata finishes. Resolve a
 * country from the new profile label first, then from fresh metadata, and
 * finally retain the previously displayed country. An empty/partial metadata
 * response must never turn an existing flag back into the globe placeholder.
 */
internal fun resolveRefreshedServerCountry(
    serverName: String,
    freshCountry: String?,
    fallbackCountry: String?,
): String {
    val freshResolved = serverCountryCodeForUi(
        countryCode = freshCountry.orEmpty(),
        serverName = serverName,
    )
    if (freshResolved.isNotBlank()) return freshResolved
    return serverCountryCodeForUi(
        countryCode = fallbackCountry.orEmpty(),
        serverName = serverName,
    )
}
