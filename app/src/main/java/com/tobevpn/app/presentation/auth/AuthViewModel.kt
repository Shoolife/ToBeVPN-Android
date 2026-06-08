package com.tobevpn.app.presentation.auth

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.DevicePairingPollResult
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.util.DeepLinkBus
import com.tobevpn.app.util.TelegramLinks
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object OpeningTelegram : AuthUiState
    data object LoadingDevicePairing : AuthUiState
    data object Polling : AuthUiState
    data class WaitingDevicePairing(val code: String, val expiresIn: Int) : AuthUiState
    data object Success : AuthUiState
    data class Error(@StringRes val messageRes: Int) : AuthUiState
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val vpnRepository: VpnRepository,
    private val prefsDataStore: PrefsDataStore,
    deepLinkBus: DeepLinkBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _showEmailPrompt = MutableStateFlow(false)
    val showEmailPrompt: StateFlow<Boolean> = _showEmailPrompt.asStateFlow()

    private val _emailSaving = MutableStateFlow(false)
    val emailSaving: StateFlow<Boolean> = _emailSaving.asStateFlow()

    private var pollingJob: Job? = null
    private var currentAuthToken: String? = null
    private var currentPairingCode: String? = null

    init {
        // Subscribe to deep links from MainActivity (e.g. tobevpn://auth_callback)
        // so the user returning from Telegram completes auth without waiting for
        // the next polling tick.
        viewModelScope.launch {
            deepLinkBus.authCallbacks.collect { uri -> handleDeepLinkCallback(uri) }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_POLL_ATTEMPTS = 60 // 3 minutes
    }

    fun startTelegramAuth(context: Context) {
        pollingJob?.cancel()
        currentPairingCode = null
        viewModelScope.launch {
            _uiState.value = AuthUiState.OpeningTelegram

            val result = authRepository.requestTelegramAuth()
            if (result.isFailure) {
                _uiState.value = AuthUiState.Error(R.string.auth_error_server)
                return@launch
            }

            val authToken = result.getOrThrow()
            currentAuthToken = authToken

            if (TelegramLinks.openStartLink(context, startParam = authToken)) {
                startPolling(authToken)
            } else {
                currentAuthToken = null
                authRepository.clearPendingAuthToken()
                _uiState.value = AuthUiState.Error(R.string.auth_error_open_telegram)
            }
        }
    }

    fun reopenTelegram(context: Context) {
        viewModelScope.launch {
            val authToken = currentAuthToken ?: authRepository.getPendingAuthToken()
            if (authToken == null) {
                startTelegramAuth(context)
                return@launch
            }

            currentAuthToken = authToken
            if (TelegramLinks.openStartLink(context, startParam = authToken)) {
                if (pollingJob?.isActive != true) {
                    startPolling(authToken)
                } else {
                    _uiState.value = AuthUiState.Polling
                }
            } else {
                currentAuthToken = null
                authRepository.clearPendingAuthToken()
                _uiState.value = AuthUiState.Error(R.string.auth_error_open_telegram)
            }
        }
    }

    fun startDevicePairing() {
        pollingJob?.cancel()
        currentAuthToken = null
        viewModelScope.launch {
            _uiState.value = AuthUiState.LoadingDevicePairing
            authRepository.clearPendingAuthToken()
            authRepository.requestDevicePairing()
                .onSuccess { pairing ->
                    currentPairingCode = pairing.code
                    _uiState.value = AuthUiState.WaitingDevicePairing(
                        code = pairing.code,
                        expiresIn = pairing.expiresIn,
                    )
                    startDevicePairingPolling(pairing.code)
                }
                .onFailure {
                    currentPairingCode = null
                    _uiState.value = AuthUiState.Error(R.string.auth_error_server)
                }
        }
    }

    fun onReturnedFromTelegram() {
        if (pollingJob?.isActive == true) return
        viewModelScope.launch {
            val token = currentAuthToken ?: authRepository.getPendingAuthToken()
            if (token != null) {
                currentAuthToken = token
                startPolling(token)
            }
        }
    }

    fun handleDeepLinkCallback(uri: Uri?) {
        if (uri?.scheme == DeepLinkBus.SCHEME && uri.host == DeepLinkBus.AUTH_CALLBACK_HOST) {
            val status = uri.getQueryParameter("status")
            if (status == "success") {
                viewModelScope.launch {
                    // currentAuthToken is null on a cold start (the activity was
                    // recreated by the deep-link). Fall back to the persisted
                    // pendingAuthToken so we still confirm with the backend
                    // before flipping the UI to Success.
                    val token = currentAuthToken ?: authRepository.getPendingAuthToken()
                    val confirmed = if (token != null) {
                        authRepository.checkAuthStatus(token)
                    } else {
                        false
                    }
                    if (confirmed) {
                        onAuthSuccess()
                    } else if (token != null && pollingJob?.isActive != true) {
                        currentAuthToken = token
                        startPolling(token)
                    }
                    // If the callback arrives before the backend has marked
                    // the auth as completed, keep polling from the recovered
                    // token instead of leaving the user on a stale screen.
                }
            }
        }
    }

    private fun startPolling(authToken: String) {
        pollingJob?.cancel()
        _uiState.value = AuthUiState.Polling

        pollingJob = viewModelScope.launch {
            repeat(MAX_POLL_ATTEMPTS) {
                val authenticated = authRepository.checkAuthStatus(authToken)
                if (authenticated) {
                    onAuthSuccess()
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
            currentAuthToken = null
            authRepository.clearPendingAuthToken()
            _uiState.value = AuthUiState.Error(R.string.auth_error_timeout)
        }
    }

    private fun startDevicePairingPolling(code: String) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            repeat(MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                val result = authRepository.checkDevicePairingStatus(code)
                result.onSuccess { status ->
                    when (status) {
                        DevicePairingPollResult.Completed -> {
                            onAuthSuccess()
                            currentPairingCode = null
                            return@launch
                        }
                        DevicePairingPollResult.Expired -> {
                            currentPairingCode = null
                            _uiState.value = AuthUiState.Error(R.string.auth_error_timeout)
                            return@launch
                        }
                        DevicePairingPollResult.Pending -> Unit
                    }
                }
                result.onFailure {
                    currentPairingCode = null
                    _uiState.value = AuthUiState.Error(R.string.auth_error_server)
                    return@launch
                }
            }
            currentPairingCode = null
            _uiState.value = AuthUiState.Error(R.string.auth_error_timeout)
        }
    }

    private suspend fun onAuthSuccess() {
        // Force the sync — we just authenticated and the user expects to
        // immediately see the right plan (PAID / FREE_TRIAL), not whatever
        // was cached before login.
        authRepository.syncSubscription(force = true)
        // Refresh servers with the subscription data that sync just populated.
        vpnRepository.refreshServers()
        _uiState.value = AuthUiState.Success
        pollingJob?.cancel()

        // Show email prompt if not shown before
        val alreadyShown = prefsDataStore.emailPromptShown.firstOrNull() ?: false
        if (!alreadyShown) {
            _showEmailPrompt.value = true
        }
    }

    fun saveEmail(email: String) {
        viewModelScope.launch {
            _emailSaving.value = true
            authRepository.saveEmail(email)
            _emailSaving.value = false
            _showEmailPrompt.value = false
        }
    }

    fun dismissEmailPrompt() {
        viewModelScope.launch {
            authRepository.markEmailPromptShown()
            _showEmailPrompt.value = false
        }
    }

    fun resetState() {
        pollingJob?.cancel()
        _uiState.value = AuthUiState.Idle
        _showEmailPrompt.value = false
        currentAuthToken = null
        currentPairingCode = null
        viewModelScope.launch {
            authRepository.clearPendingAuthToken()
        }
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
