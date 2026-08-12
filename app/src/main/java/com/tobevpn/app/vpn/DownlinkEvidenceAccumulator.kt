package com.tobevpn.app.vpn

import java.util.concurrent.atomic.AtomicReference

internal data class DownlinkEvidence(
    val observedAtMs: Long = 0L,
    val loopGeneration: Int = -1,
    val bytes: Long = 0L,
)

/** One-shot accumulation of non-probe downlink between watchdog cycles. */
internal class DownlinkEvidenceAccumulator {
    private val evidence = AtomicReference(DownlinkEvidence())

    fun record(observedAtMs: Long, loopGeneration: Int, bytes: Long) {
        if (observedAtMs <= 0L || loopGeneration <= 0 || bytes <= 0L) return
        evidence.updateAndGet { previous ->
            DownlinkEvidence(
                observedAtMs = observedAtMs,
                loopGeneration = loopGeneration,
                bytes = if (previous.loopGeneration == loopGeneration) {
                    saturatingAdd(previous.bytes, bytes)
                } else {
                    bytes
                },
            )
        }
    }

    fun consume(): DownlinkEvidence = evidence.getAndSet(DownlinkEvidence())

    fun reset() {
        evidence.set(DownlinkEvidence())
    }

    private fun saturatingAdd(current: Long, increment: Long): Long =
        if (increment >= Long.MAX_VALUE - current) Long.MAX_VALUE else current + increment
}
