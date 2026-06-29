package com.tobevpn.app.util

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class DeepLinkDestination {
    AUTH,
    MAIN,
    SERVERS,
    SETTINGS,
}

/**
 * App-wide bus for deep-link URIs received by [com.tobevpn.app.MainActivity].
 *
 * The activity's `onCreate`/`onNewIntent` extracts the URI from the launch intent
 * and posts it here. Callback types have separate buffered channels so the
 * payment handler cannot consume an authentication callback or vice versa.
 *
 * A buffered channel is used so a URI delivered before the consumer subscribes
 * is not lost on a cold start.
 */
@Singleton
class DeepLinkBus @Inject constructor() {
    private val authChannel = Channel<Uri>(capacity = Channel.BUFFERED)
    private val paymentChannel = Channel<Uri>(capacity = Channel.BUFFERED)
    private val devicePairingChannel = Channel<String>(capacity = Channel.BUFFERED)
    private val navigationChannel = Channel<DeepLinkDestination>(capacity = Channel.BUFFERED)

    val authCallbacks: Flow<Uri> = authChannel.receiveAsFlow()
    val paymentCallbacks: Flow<Uri> = paymentChannel.receiveAsFlow()
    val devicePairingCodes: Flow<String> = devicePairingChannel.receiveAsFlow()
    val navigationRequests: Flow<DeepLinkDestination> = navigationChannel.receiveAsFlow()

    fun post(uri: Uri) {
        if (uri.scheme != SCHEME) return
        when (uri.host) {
            AUTH_CALLBACK_HOST -> {
                authChannel.trySend(uri)
                navigationChannel.trySend(DeepLinkDestination.AUTH)
            }
            PAYMENT_CALLBACK_HOST -> {
                paymentChannel.trySend(uri)
                navigationChannel.trySend(DeepLinkDestination.MAIN)
            }
            OPEN_HOST -> navigationChannel.trySend(DeepLinkDestination.MAIN)
            PAIR_HOST -> {
                parsePairingCode(uri)?.let { code ->
                    devicePairingChannel.trySend(code)
                    navigationChannel.trySend(DeepLinkDestination.SETTINGS)
                }
            }
        }
    }

    fun navigateTo(destination: DeepLinkDestination) {
        navigationChannel.trySend(destination)
    }

    fun supports(uri: Uri): Boolean {
        return uri.scheme == SCHEME &&
            (uri.host == AUTH_CALLBACK_HOST ||
                uri.host == PAYMENT_CALLBACK_HOST ||
                uri.host == OPEN_HOST ||
                uri.host == PAIR_HOST)
    }

    companion object {
        const val SCHEME = "tobevpn"
        const val AUTH_CALLBACK_HOST = "auth_callback"
        const val PAYMENT_CALLBACK_HOST = "payment_callback"
        const val OPEN_HOST = "open"
        const val PAIR_HOST = "pair"
        const val PAIR_CODE_QUERY = "code"

        fun createPairingUri(code: String): String = Uri.Builder()
            .scheme(SCHEME)
            .authority(PAIR_HOST)
            .appendQueryParameter(PAIR_CODE_QUERY, code)
            .build()
            .toString()

        fun parsePairingCode(uri: Uri): String? {
            if (uri.scheme != SCHEME || uri.host != PAIR_HOST) return null
            return uri.getQueryParameter(PAIR_CODE_QUERY)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }
    }
}
