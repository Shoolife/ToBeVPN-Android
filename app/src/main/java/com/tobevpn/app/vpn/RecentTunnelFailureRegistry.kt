package com.tobevpn.app.vpn

import com.tobevpn.app.data.repository.serverConnectionIdentityKey
import com.tobevpn.app.domain.model.Server

/**
 * Remembers servers whose tunnel failed end-to-end validation so the next
 * automatic selection explores different candidates instead of retrying the
 * same ones a minute later.
 *
 * Public bypass pools are intermittent rather than permanently broken: the
 * same profile can refuse traffic and then work again minutes later. The
 * penalty therefore expires on its own, and a confirmed healthy tunnel clears
 * it immediately. Entries are a preference, not a hard ban — callers keep a
 * fallback tier so a fully penalised pool still yields a candidate.
 */
internal class RecentTunnelFailureRegistry(
    private val penaltyMs: Long = DEFAULT_PENALTY_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = LinkedHashMap<String, Entry>()

    init {
        require(penaltyMs > 0L) { "penaltyMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    @Synchronized
    fun record(server: Server, nowMs: Long) {
        val key = serverConnectionIdentityKey(server)
        entries.remove(key)
        entries[key] = Entry(server = server, failedAtMs = nowMs)
        while (entries.size > maxEntries) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    @Synchronized
    fun forget(server: Server) {
        entries.remove(serverConnectionIdentityKey(server))
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }

    /** Servers still inside the penalty window, oldest failure first. */
    @Synchronized
    fun penalisedServers(nowMs: Long): List<Server> {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            if (isExpired(iterator.next().value, nowMs)) iterator.remove()
        }
        return entries.values.map(Entry::server)
    }

    private fun isExpired(entry: Entry, nowMs: Long): Boolean {
        // A backwards clock must not extend the penalty indefinitely.
        val age = nowMs - entry.failedAtMs
        return age < 0L || age >= penaltyMs
    }

    private data class Entry(
        val server: Server,
        val failedAtMs: Long,
    )

    private companion object {
        const val DEFAULT_PENALTY_MS = 10L * 60L * 1_000L
        const val DEFAULT_MAX_ENTRIES = 32
    }
}
