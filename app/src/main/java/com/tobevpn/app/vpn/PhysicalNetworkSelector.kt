package com.tobevpn.app.vpn

/**
 * Selects one stable upstream from the physical networks reported by a
 * passive ConnectivityManager callback.
 *
 * Android can keep Wi-Fi and cellular networks alive at the same time. A plain
 * registerNetworkCallback therefore reports both. Prefer a validated network,
 * but retain unvalidated physical networks as usable candidates: carrier
 * allowlists can intentionally prevent Android's general-internet validation
 * while still allowing the VPN endpoint. Preserve the current selection while
 * it remains at least as suitable as every other candidate, and only report a
 * handover when a better candidate appears or the selected one disappears.
 */
internal class PhysicalNetworkSelector<T> {
    private val candidates = LinkedHashMap<T, Candidate>()
    private var selected: T? = null

    @Synchronized
    fun update(
        network: T,
        validated: Boolean,
        priority: Int,
    ): SelectionChange<T> {
        candidates[network] = Candidate(validated = validated, priority = priority)
        return reselect()
    }

    @Synchronized
    fun onLost(network: T): SelectionChange<T> {
        candidates.remove(network)
        return reselect()
    }

    @Synchronized
    fun selectedOrNull(): T? = selected

    @Synchronized
    fun isSelected(network: T): Boolean = selected == network

    @Synchronized
    fun hasUsableNetwork(): Boolean = selected != null

    @Synchronized
    fun reset() {
        candidates.clear()
        selected = null
    }

    private fun reselect(): SelectionChange<T> {
        val previous = selected
        val previousCandidate = previous?.let(candidates::get)
        val best = candidates.entries
            .asSequence()
            .maxByOrNull { it.value.selectionScore }

        val next = if (previous != null &&
            previousCandidate != null &&
            (best == null || previousCandidate.selectionScore >= best.value.selectionScore)
        ) {
            previous
        } else {
            best?.key
        }
        selected = next

        val type = when {
            previous == next -> ChangeType.UNCHANGED
            previous == null && next != null -> ChangeType.INITIAL
            previous != null && next == null -> ChangeType.UNAVAILABLE
            else -> ChangeType.HANDOVER
        }
        return SelectionChange(type = type, previous = previous, current = next)
    }

    private data class Candidate(
        val validated: Boolean,
        val priority: Int,
    ) {
        val selectionScore: Int
            get() = priority + if (validated) VALIDATED_PRIORITY_BONUS else 0
    }

    data class SelectionChange<T>(
        val type: ChangeType,
        val previous: T?,
        val current: T?,
    )

    enum class ChangeType {
        UNCHANGED,
        INITIAL,
        HANDOVER,
        UNAVAILABLE,
    }

    private companion object {
        // Transport priorities currently top out at 300. Keep validation as
        // the dominant preference without discarding restricted networks.
        const val VALIDATED_PRIORITY_BONUS = 10_000
    }
}
