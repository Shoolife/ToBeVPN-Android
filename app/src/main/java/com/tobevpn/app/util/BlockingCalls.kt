package com.tobevpn.app.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/** Owns blocking calls whose callers stopped waiting; they end on their own. */
// A separate view of the IO pool: calls left running after a timeout must not
// take the threads the rest of the app (database, DataStore) waits on.
@OptIn(ExperimentalCoroutinesApi::class)
private val blockingCallScope = CoroutineScope(
    SupervisorJob() + Dispatchers.IO.limitedParallelism(BLOCKING_CALL_THREADS),
)

private const val BLOCKING_CALL_THREADS = 64

/**
 * Runs a blocking call that coroutine cancellation cannot interrupt (a
 * socket connect with its DNS lookup, a native Xray probe) and waits for it
 * at most [timeoutMs]. Wrapping such a call in withTimeoutOrNull alone does
 * not work: the timeout only fires once the call returns by itself, so a
 * server check could hang far past the configured timeout.
 *
 * @return the call's result, or null when it did not finish in time (it then
 *   completes in the background and its result is dropped).
 */
suspend fun <T> awaitBlocking(timeoutMs: Long, block: () -> T): Result<T>? {
    val started = CompletableDeferred<Unit>()
    val call = blockingCallScope.async {
        started.complete(Unit)
        runCatching(block)
    }
    try {
        // The timeout counts from when the call gets a thread: with many
        // servers checked at once, time spent queued for one marked live
        // servers unavailable before the configured timeout had passed.
        started.await()
    } catch (cancelled: CancellationException) {
        call.cancel()
        throw cancelled
    }
    return withTimeoutOrNull(timeoutMs) { call.await() }
}
