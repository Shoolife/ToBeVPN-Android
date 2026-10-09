package com.tobevpn.app.data.remote

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Cancellable alternative to [Call.execute]: cancelling the coroutine cancels
 * the HTTP call. A blocking execute() inside withContext(Dispatchers.IO)
 * ignores cancellation, so a timeout around it (the server list's
 * subscription sync) waited for the request anyway.
 *
 * Failures arrive as [IOException], like from execute().
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // The close handler covers a cancellation that lands after
                // resume but before the caller gets the response.
                continuation.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        },
    )
}
