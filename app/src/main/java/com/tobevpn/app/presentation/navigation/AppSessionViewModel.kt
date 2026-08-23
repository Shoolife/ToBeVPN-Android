package com.tobevpn.app.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.TelegramAuthPollResult
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class AppSessionViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val connectionManager: VpnConnectionManager,
) : ViewModel() {

    init {
        viewModelScope.launch {
            authRepository.subscriptionResetEvents.collectLatest {
                val state = connectionManager.connectionState.value
                if (state !is ConnectionState.Connected && state !is ConnectionState.Connecting) {
                    return@collectLatest
                }
                val server = connectionManager.currentServer.value
                if (server == null) {
                    connectionManager.stopVpn()
                    return@collectLatest
                }
                connectionManager.switchServer(server, allowStaleOnRefreshMiss = false)
            }
        }

        // Recover devices linked by QR while an older client had already lost
        // its screen-scoped token. The VPN reset collector is installed first
        // so a fast reconciliation cannot lose its reconnect event.
        viewModelScope.launch {
            authRepository.reconcileLinkedIdentity()
        }

        // QR confirmation belongs to the application session, not to one
        // navigation screen. Continue while a messenger is open or the user
        // has returned to Main; the database token also survives recreation.
        // Use collect (not collectLatest): successful completion clears the
        // token itself and must not cancel profile reconciliation halfway.
        viewModelScope.launch {
            authRepository.observePendingAuthToken().collect { token ->
                if (token == null) return@collect

                var attempt = 0
                while (isActive && authRepository.getPendingAuthToken() == token) {
                    when (authRepository.pollTelegramAuthStatus(token)) {
                        TelegramAuthPollResult.COMPLETED -> break
                        TelegramAuthPollResult.INVALID -> {
                            authRepository.clearPendingAuthToken(token)
                            break
                        }
                        TelegramAuthPollResult.PENDING,
                        TelegramAuthPollResult.RETRYABLE_ERROR,
                        -> {
                            delay(pendingAuthPollDelayMs(attempt))
                            attempt += 1
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            authRepository.observeAuthState()
                .map { state -> state is AuthState.Authenticated }
                .distinctUntilChanged()
                .collectLatest { isAuthenticated ->
                    if (!isAuthenticated) return@collectLatest

                    while (isActive) {
                        val stillLinked = authRepository.syncDeviceSessionState().getOrNull()
                        if (stillLinked == false) {
                            connectionManager.stopVpn()
                            withContext(NonCancellable) {
                                authRepository.clearRemoteUnlinkedSession()
                            }
                            break
                        }
                        delay(DEVICE_LINK_POLL_INTERVAL_MS)
                    }
                }
        }
    }

    private companion object {
        const val DEVICE_LINK_POLL_INTERVAL_MS = 5L * 60L * 1000L

        fun pendingAuthPollDelayMs(attempt: Int): Long = when {
            attempt < 60 -> 3_000L
            attempt < 108 -> 15_000L
            else -> 60_000L
        }
    }
}
