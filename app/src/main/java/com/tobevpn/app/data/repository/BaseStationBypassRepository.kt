package com.tobevpn.app.data.repository

import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.BaseStationBypassServerDao
import com.tobevpn.app.data.local.entity.BaseStationBypassServerEntity
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.ServerSource
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.vpn.VlessUrlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val BASE_STATION_BYPASS_ID_PREFIX = "bs:"

@Singleton
class BaseStationBypassRepository @Inject constructor(
    private val serverDao: BaseStationBypassServerDao,
    private val prefsDataStore: PrefsDataStore,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    fun observeServers(): Flow<List<Server>> = serverDao.observeAll().map { entities ->
        entities
            .map(BaseStationBypassServerEntity::toDomain)
            .filter(BaseStationBypassProfileParser::isSupportedProfile)
    }

    suspend fun getServers(): List<Server> =
        serverDao.getAll()
            .map(BaseStationBypassServerEntity::toDomain)
            .filter(BaseStationBypassProfileParser::isSupportedProfile)

    suspend fun refreshServers(): Result<List<Server>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(BuildConfig.BASE_STATION_BYPASS_URL)
                .get()
                .header(
                    "User-Agent",
                    "ToBeVPN/${BuildConfig.VERSION_NAME}/android/${BuildConfig.VERSION_CODE}",
                )
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Base-station bypass profile returned HTTP_${response.code}")
                }
                response.body.readLimitedUtf8(MAX_PROFILE_BYTES)
            }
            val parsedProfile = BaseStationBypassProfileParser.parseWithFilterSummary(body)
            val servers = parsedProfile.servers
            SafeDiagnostics.info(
                TAG,
                "Base-station bypass profile filter: ${parsedProfile.filterSummary}",
            )
            if (servers.isEmpty()) {
                throw IOException("Base-station bypass profile contains no usable servers")
            }
            serverDao.replaceAll(servers.mapIndexed { index, server -> server.toEntity(index) })
            SafeDiagnostics.info(
                TAG,
                "Base-station bypass profile refreshed: count=${servers.size}",
            )
            Result.success(servers)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "Base-station bypass refresh failed: " +
                    SafeDiagnostics.failureCategory(error),
            )
            Result.failure(error)
        }
    }

    suspend fun clearCachedServers() {
        serverDao.deleteAll()
    }

    /**
     * Runs from application initialization, never from refreshServers() on the
     * connection hot path. An old cached entity cannot supply the transport
     * fields introduced in v20, so fetch the current public profile only when
     * the selected bypass id is demonstrably legacy.
     */
    suspend fun migratePersistedSelectionOnStartup() {
        val selection = prefsDataStore.getServerSelection()
        if (!isBaseStationBypassSelection(selection.selectedId, selection.selectedKey)) return

        val cached = getServers()
        val cachedResolution = resolveBaseStationBypassSelectionMigration(
            servers = cached,
            selectedId = selection.selectedId,
            selectedKey = selection.selectedKey,
        )
        if (cachedResolution != null &&
            BaseStationBypassProfileParser.hasCurrentStableId(cachedResolution)
        ) {
            persistMigratedSelection(
                cachedResolution,
                selection.selectedId,
                selection.selectedKey,
            )
            return
        }

        val refreshed = refreshServers().getOrNull() ?: return
        val resolved = resolveBaseStationBypassSelectionMigration(
            servers = refreshed,
            selectedId = selection.selectedId,
            selectedKey = selection.selectedKey,
        ) ?: return
        persistMigratedSelection(resolved, selection.selectedId, selection.selectedKey)
    }

    /**
     * Offline startup can postpone migration until the first successful
     * connect refresh. VpnConnectionManager calls this only after publishing
     * the resolved server as current, so the preference emission cannot start
     * a second switch in the middle of connection setup.
     */
    suspend fun migrateResolvedSelectionIfNeeded(server: Server) {
        if (server.source != ServerSource.BASE_STATION_BYPASS) return
        val selection = prefsDataStore.getServerSelection()
        if (!matchesBaseStationBypassSelectionId(server, selection.selectedId)) return
        persistMigratedSelection(server, selection.selectedId, selection.selectedKey)
    }

    private suspend fun persistMigratedSelection(
        server: Server,
        selectedId: String?,
        selectedKey: String?,
    ) {
        if (selectedId == server.id && selectedKey == server.id) return
        prefsDataStore.setSelectedServer(id = server.id, key = server.id)
        SafeDiagnostics.info(TAG, "Base-station bypass selection key migrated")
    }

    private fun ResponseBody.readLimitedUtf8(maxBytes: Int): String {
        val declaredLength = contentLength()
        if (declaredLength > maxBytes) {
            throw IOException("Base-station bypass profile is too large")
        }
        return byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > maxBytes) {
                    throw IOException("Base-station bypass profile is too large")
                }
                output.write(buffer, 0, read)
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private companion object {
        const val TAG = "BaseStationBypassRepo"
        const val MAX_PROFILE_BYTES = 512 * 1024
    }
}

internal fun resolveBaseStationBypassSelectionMigration(
    servers: List<Server>,
    selectedId: String?,
    selectedKey: String?,
): Server? {
    if (!isBaseStationBypassSelection(selectedId, selectedKey)) return null
    return servers.firstOrNull { it.id == selectedId }
        ?: servers.firstOrNull {
            matchesBaseStationBypassSelectionId(it, selectedId)
        }
}

private fun isBaseStationBypassSelection(
    selectedId: String?,
    selectedKey: String?,
): Boolean = selectedId?.startsWith(BASE_STATION_BYPASS_ID_PREFIX) == true ||
    selectedKey?.startsWith(BASE_STATION_BYPASS_ID_PREFIX) == true

/**
 * Matches both the current full-config id and the v19 id. The latter is
 * required while an in-flight connect request still holds the cached entity
 * that existed immediately before refreshServers migrated the preference.
 */
internal fun matchesBaseStationBypassSelectionId(
    server: Server,
    selectedId: String?,
): Boolean = selectedId != null && (
    server.id == selectedId ||
        BaseStationBypassProfileParser.legacyStableId(server) == selectedId
    )

internal object BaseStationBypassProfileParser {
    private const val MAX_SERVERS = 500

    fun parse(rawProfile: String): List<Server> = parseWithFilterSummary(rawProfile).servers

    /**
     * Same parse, plus a privacy-safe summary of what the client refused and
     * why. Kept free of Android logging so the parser stays unit-testable; the
     * repository is responsible for emitting the summary.
     */
    fun parseWithFilterSummary(rawProfile: String): ParsedProfile {
        val links = rawProfile
            .lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.startsWith("vless://") }
            .toList()
        val decoded = links.mapNotNull(VlessUrlParser::parse)
        val parsed = decoded.filterNot(Server::isSentinel)
        val servers = parsed
            .asSequence()
            .filter(::isSupportedProfile)
            .map { server ->
                server.copy(
                    id = stableId(server, includeExtendedTransport = true),
                    name = sanitizeDisplayName(server.name),
                    country = inferCountryCode(server.country, server.name),
                    source = ServerSource.BASE_STATION_BYPASS,
                )
            }
            .distinctBy(Server::id)
            .take(MAX_SERVERS)
            .toList()
        return ParsedProfile(
            servers = servers,
            filterSummary = filterSummary(
                links = links.size,
                decoded = decoded.size,
                parsed = parsed,
                kept = servers.size,
            ),
        )
    }

    /**
     * Reports how much of the public profile the client refuses and why, so the
     * drop rate can be judged from a device instead of guessed. Contains only
     * counts and transport names.
     */
    private fun filterSummary(
        links: Int,
        decoded: Int,
        parsed: List<Server>,
        kept: Int,
    ): String {
        val rejected = parsed.filterNot(::isSupportedProfile)
        val supported = parsed.size - rejected.size
        val summary = buildString {
            append("links=")
            append(links)
            append(" unparsable=")
            append(links - decoded)
            append(" sentinel=")
            append(decoded - parsed.size)
            append(" rejected=")
            append(rejected.size)
            append(" duplicates=")
            append((supported - kept).coerceAtLeast(0))
            append(" kept=")
            append(kept)
        }
        if (rejected.isEmpty()) return summary
        val byReason = rejected.groupingBy { server ->
            when {
                server.network !in SUPPORTED_NETWORKS -> "network:${server.network}"
                server.security !in SUPPORTED_SECURITIES -> "security:${server.security}"
                server.network == "ws" && server.security == "reality" -> "ws_reality"
                else -> "transport_detail"
            }
        }.eachCount()
        return summary + " reasons=" +
            byReason.entries.joinToString(separator = ",") { "${it.key}=${it.value}" }
    }

    data class ParsedProfile(
        val servers: List<Server>,
        val filterSummary: String,
    )

    internal fun legacyStableId(server: Server): String =
        stableId(server, includeExtendedTransport = false)

    internal fun hasCurrentStableId(server: Server): Boolean =
        server.id == stableId(server, includeExtendedTransport = true)

    internal fun isSupportedProfile(server: Server): Boolean {
        val headerType = server.headerType.lowercase(Locale.ROOT)
        if (server.network !in SUPPORTED_NETWORKS ||
            server.security !in SUPPORTED_SECURITIES
        ) {
            return false
        }
        // Current Xray supports REALITY only with RAW/TCP, XHTTP and gRPC.
        if (server.network == "ws" && server.security == "reality") return false
        if (server.network == "tcp" &&
            headerType !in SUPPORTED_TCP_HEADERS
        ) {
            return false
        }
        // REALITY must see the TLS ClientHello directly on RAW/TCP. HTTP
        // packet-header obfuscation would precede it and cannot negotiate.
        if (server.network == "tcp" &&
            server.security == "reality" &&
            headerType == "http"
        ) {
            return false
        }
        if (server.network == "xhttp" &&
            server.mode.lowercase(Locale.ROOT) !in SUPPORTED_XHTTP_MODES
        ) {
            return false
        }
        if (server.network == "xhttp" && server.extra.isNotBlank()) {
            val extra = runCatching { Json.parseToJsonElement(server.extra) }.getOrNull()
            if (extra !is JsonObject) return false
        }
        if (server.network == "grpc" &&
            server.mode.lowercase(Locale.ROOT) !in SUPPORTED_GRPC_MODES
        ) {
            return false
        }
        return true
    }

    private fun stableId(
        server: Server,
        includeExtendedTransport: Boolean,
    ): String {
        val configFields = mutableListOf(
            server.address,
            server.port.toString(),
            server.uuid,
            server.flow,
            server.security,
            server.sni,
            server.fingerprint,
            server.publicKey,
            server.shortId,
            server.network,
            server.path,
            server.mode,
            server.spx,
        )
        if (includeExtendedTransport) {
            val headerType = server.headerType.lowercase(Locale.ROOT)
            configFields += listOf(
                server.host,
                server.alpn.takeIf { server.security == "tls" }.orEmpty(),
                headerType.takeIf { server.network == "tcp" }.orEmpty(),
                server.serviceName.takeIf { server.network == "grpc" }.orEmpty(),
                server.extra.takeIf { server.network == "xhttp" }.orEmpty(),
            )
        }
        val configFingerprint = configFields.joinToString(separator = "\u0000")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(configFingerprint.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return BASE_STATION_BYPASS_ID_PREFIX + digest
    }

    /**
     * The public profile controls server display names. Keep ordinary letters,
     * symbols and emoji, but remove control/bidi/combining code points that can
     * stretch or visually reorder a Compose row. The cap also prevents a remote
     * profile update from forcing very expensive text measurement.
     */
    private fun sanitizeDisplayName(rawName: String): String {
        val normalized = Normalizer.normalize(rawName, Normalizer.Form.NFKC)
        val filtered = buildString(normalized.length.coerceAtMost(MAX_DISPLAY_NAME_CHARS)) {
            var offset = 0
            var previousWasSpace = false
            while (offset < normalized.length && codePointCount(0, length) < MAX_DISPLAY_NAME_CHARS) {
                val codePoint = normalized.codePointAt(offset)
                offset += Character.charCount(codePoint)
                val type = Character.getType(codePoint)
                when {
                    Character.isWhitespace(codePoint) -> {
                        if (isNotEmpty() && !previousWasSpace) append(' ')
                        previousWasSpace = true
                    }
                    type == Character.CONTROL.toInt() ||
                        type == Character.FORMAT.toInt() ||
                        type == Character.SURROGATE.toInt() ||
                        type == Character.PRIVATE_USE.toInt() ||
                        type == Character.UNASSIGNED.toInt() ||
                    type == Character.NON_SPACING_MARK.toInt() ||
                        type == Character.COMBINING_SPACING_MARK.toInt() ||
                        type == Character.ENCLOSING_MARK.toInt() -> Unit
                    !isSafeDisplayCodePoint(codePoint, type) -> Unit
                    else -> {
                        appendCodePoint(codePoint)
                        previousWasSpace = false
                    }
                }
            }
        }.trim().trimStart { character ->
            !character.isLetterOrDigit() && character != '[' && character != '('
        }
        return filtered.ifBlank { DEFAULT_DISPLAY_NAME }
    }

    private fun isSafeDisplayCodePoint(codePoint: Int, type: Int): Boolean =
        codePoint in ASCII_PRINTABLE_RANGE ||
            Character.isLetterOrDigit(codePoint) ||
            type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt()

    private fun inferCountryCode(rawCountry: String, rawName: String): String {
        val explicitCountry = rawCountry.trim().uppercase(Locale.ROOT)
        if (explicitCountry.length == COUNTRY_CODE_LENGTH &&
            explicitCountry.all(Char::isLetter)
        ) {
            return explicitCountry
        }

        var offset = 0
        while (offset < rawName.length) {
            val first = rawName.codePointAt(offset)
            val nextOffset = offset + Character.charCount(first)
            if (first in REGIONAL_INDICATOR_RANGE && nextOffset < rawName.length) {
                val second = rawName.codePointAt(nextOffset)
                if (second in REGIONAL_INDICATOR_RANGE) {
                    return buildString(COUNTRY_CODE_LENGTH) {
                        append((first - REGIONAL_INDICATOR_START + 'A'.code).toChar())
                        append((second - REGIONAL_INDICATOR_START + 'A'.code).toChar())
                    }
                }
            }
            offset = nextOffset
        }

        val tokens = rawName.lowercase(Locale.ROOT).split(COUNTRY_WORD_SPLIT_REGEX)
        return tokens.firstNotNullOfOrNull(COUNTRY_NAME_TO_CODE::get)
            ?: UNKNOWN_COUNTRY_CODE
    }

    private const val MAX_DISPLAY_NAME_CHARS = 80
    private const val DEFAULT_DISPLAY_NAME = "Base-station bypass"
    private const val UNKNOWN_COUNTRY_CODE = "UN"
    private const val COUNTRY_CODE_LENGTH = 2
    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF
    private val REGIONAL_INDICATOR_RANGE =
        REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END
    private val ASCII_PRINTABLE_RANGE = 0x21..0x7E
    private val SUPPORTED_NETWORKS = setOf("tcp", "ws", "xhttp", "grpc")
    private val SUPPORTED_SECURITIES = setOf("none", "tls", "reality")
    private val SUPPORTED_TCP_HEADERS = setOf("", "none", "http")
    private val SUPPORTED_XHTTP_MODES = setOf("", "auto", "packet-up", "stream-up", "stream-one")
    private val SUPPORTED_GRPC_MODES = setOf("", "gun", "multi")
    private val COUNTRY_WORD_SPLIT_REGEX = Regex("[^\\p{L}]+")
    private val COUNTRY_NAME_TO_CODE = mapOf(
        "russia" to "RU",
        "россия" to "RU",
        "germany" to "DE",
        "германия" to "DE",
        "netherlands" to "NL",
        "нидерланды" to "NL",
        "finland" to "FI",
        "финляндия" to "FI",
        "france" to "FR",
        "франция" to "FR",
        "sweden" to "SE",
        "швеция" to "SE",
        "estonia" to "EE",
        "эстония" to "EE",
        "switzerland" to "CH",
        "швейцария" to "CH",
        "turkey" to "TR",
        "турция" to "TR",
        "italy" to "IT",
        "италия" to "IT",
        "poland" to "PL",
        "польша" to "PL",
        "austria" to "AT",
        "австрия" to "AT",
    )
}

private fun Server.toEntity(sortOrder: Int) = BaseStationBypassServerEntity(
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
    sortOrder = sortOrder,
)

private fun BaseStationBypassServerEntity.toDomain() = Server(
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
    source = ServerSource.BASE_STATION_BYPASS,
)
