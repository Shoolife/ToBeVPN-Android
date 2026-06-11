package com.tobevpn.app.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
    }
}
