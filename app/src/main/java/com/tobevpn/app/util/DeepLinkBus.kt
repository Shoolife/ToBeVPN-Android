package com.tobevpn.app.util

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-wide bus for deep-link URIs received by [com.tobevpn.app.MainActivity].
 *
 * The activity's `onCreate`/`onNewIntent` extracts the URI from the launch intent
 * and posts it here; [com.tobevpn.app.presentation.auth.AuthViewModel] (and any
 * other interested ViewModel) consumes the flow and dispatches the URI.
 *
 * A buffered channel is used so a URI delivered before the consumer subscribes
 * isn't lost (which would happen with a SharedFlow at replay = 0).
 */
@Singleton
class DeepLinkBus @Inject constructor() {
    private val channel = Channel<Uri>(capacity = Channel.BUFFERED)

    val deepLinks: Flow<Uri> = channel.receiveAsFlow()

    fun post(uri: Uri) {
        channel.trySend(uri)
    }
}
