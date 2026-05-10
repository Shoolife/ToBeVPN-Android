package com.tobevpn.app.presentation.auth

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.AuthRepository
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
    data object Polling : AuthUiState
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

    init {
        // Subscribe to deep links from MainActivity (e.g. tobevpn://auth_callback)
        // so the user returning from Telegram completes auth without waiting for
        // the next polling tick.
        viewModelScope.launch {
            deepLinkBus.deepLinks.collect { uri -> handleDeepLinkCallback(uri) }
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 3000L
        private const val MAX_POLL_ATTEMPTS = 60 // 3 minutes
    }

    fun startTelegramAuth(context: Context) {
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
                _uiState.value = AuthUiState.Error(R.string.auth_error_open_telegram)
            }
        }
    }

    fun onReturnedFromTelegram() {
        currentAuthToken?.let { token ->
            if (pollingJob?.isActive != true) {
                startPolling(token)
            }
        }
    }

    fun handleDeepLinkCallback(uri: Uri?) {
        if (uri?.scheme == "tobevpn" && uri.host == "auth_callback") {
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
                    }
                    // If not confirmed, leave UI in its current state — the
                    // active polling job (if any) will catch up; otherwise the
                    // user simply hasn't really authenticated yet.
                }
            }
        }
    }

    private fun startPolling(authToken: String) {
        pollingJob?.cancel()
        _uiState.value = AuthUiState.Polling

        pollingJob = viewModelScope.launch {
            repeat(MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                val authenticated = authRepository.checkAuthStatus(authToken)
                if (authenticated) {
                    onAuthSuccess()
                    return@launch
                }
            }
            _uiState.value = AuthUiState.Error(R.string.auth_error_timeout)
        }
    }

    private suspend fun onAuthSuccess() {
        // Refresh servers with new subscription (user may have been upgraded)
        vpnRepository.refreshServers()
        // Force the sync — we just authenticated and the user expects to
        // immediately see the right plan (PAID / FREE_TRIAL), not whatever
        // was cached before login.
        authRepository.syncSubscription(force = true)
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
    }

    override fun onCleared() {
        pollingJob?.cancel()
        super.onCleared()
    }
}
