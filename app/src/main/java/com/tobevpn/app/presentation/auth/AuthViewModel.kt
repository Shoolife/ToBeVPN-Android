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
    data object LoadingTelegramPairing : AuthUiState
    data object Polling : AuthUiState
    data class WaitingDevicePairing(val code: String, val expiresIn: Int) : AuthUiState
    data class WaitingTelegramPairing(val qrData: String) : AuthUiState
    data object Success : AuthUiState
    data class Error(@StringRes val messageRes: Int) : AuthUiState
}

enum class AuthMethod {
    TELEGRAM,
    TOBEVPN_APP,
}

private enum class ActiveAuthFlow {
    TELEGRAM_LOGIN,
    TELEGRAM_QR,
    TOBEVPN_APP,
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    deepLinkBus: DeepLinkBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _authMethod = MutableStateFlow(AuthMethod.TELEGRAM)
    val authMethod: StateFlow<AuthMethod> = _authMethod.asStateFlow()

    private val _showEmailPrompt = MutableStateFlow(false)
    val showEmailPrompt: StateFlow<Boolean> = _showEmailPrompt.asStateFlow()

    private val _emailSaving = MutableStateFlow(false)
    val emailSaving: StateFlow<Boolean> = _emailSaving.asStateFlow()

    private var pollingJob: Job? = null
    private var requestJob: Job? = null
    private var currentAuthToken: String? = null
    private var currentPairingCode: String? = null
    private var operationGeneration = 0
    private var activeAuthFlow = ActiveAuthFlow.TELEGRAM_LOGIN
    private var sameDeviceTelegramAuthStarted = false

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
        _authMethod.value = AuthMethod.TELEGRAM
        activeAuthFlow = ActiveAuthFlow.TELEGRAM_LOGIN
        sameDeviceTelegramAuthStarted = true
        val generation = beginOperation()
        currentPairingCode = null
        requestJob = viewModelScope.launch {
            _uiState.value = AuthUiState.OpeningTelegram

            val result = authRepository.requestTelegramAuth()
            if (!isCurrentOperation(generation)) return@launch
            if (result.isFailure) {
                _uiState.value = AuthUiState.Error(R.string.auth_error_server)
                return@launch
            }

            val authToken = result.getOrThrow()
            currentAuthToken = authToken

            if (TelegramLinks.openStartLink(context, startParam = authToken)) {
                startTelegramPolling(
                    authToken = authToken,
                    generation = generation,
                )
            } else {
                currentAuthToken = null
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
                    startTelegramPolling(
                        authToken = authToken,
                        generation = operationGeneration,
                    )
                } else {
                    _uiState.value = AuthUiState.Polling
                }
            } else {
                currentAuthToken = null
                _uiState.value = AuthUiState.Error(R.string.auth_error_open_telegram)
            }
        }
    }

    fun startDevicePairing() {
        _authMethod.value = AuthMethod.TOBEVPN_APP
        activeAuthFlow = ActiveAuthFlow.TOBEVPN_APP
        sameDeviceTelegramAuthStarted = false
        val generation = beginOperation()
        currentAuthToken = null
        requestJob = viewModelScope.launch {
            _uiState.value = AuthUiState.LoadingDevicePairing
            if (!isCurrentOperation(generation)) return@launch
            authRepository.requestDevicePairing()
                .onSuccess { pairing ->
                    if (!isCurrentOperation(generation)) return@onSuccess
                    currentPairingCode = pairing.code
                    _uiState.value = AuthUiState.WaitingDevicePairing(
                        code = pairing.code,
                        expiresIn = pairing.expiresIn,
                    )
                    startDevicePairingPolling(pairing.code, generation)
                }
                .onFailure {
                    if (!isCurrentOperation(generation)) return@onFailure
                    currentPairingCode = null
                    _uiState.value = AuthUiState.Error(R.string.auth_error_server)
                }
        }
    }

    fun startTelegramQrPairing() {
        _authMethod.value = AuthMethod.TELEGRAM
        activeAuthFlow = ActiveAuthFlow.TELEGRAM_QR
        sameDeviceTelegramAuthStarted = false
        val generation = beginOperation()
        currentPairingCode = null
        currentAuthToken = null
        requestJob = viewModelScope.launch {
            _uiState.value = AuthUiState.LoadingTelegramPairing
            if (!isCurrentOperation(generation)) return@launch

            val result = authRepository.requestTelegramAuth()
            if (!isCurrentOperation(generation)) return@launch
            if (result.isFailure) {
                _uiState.value = AuthUiState.Error(R.string.auth_error_server)
                return@launch
            }

            val authToken = result.getOrThrow()
            val qrData = TelegramLinks.buildWebStartLink(authToken)
            currentAuthToken = authToken
            startTelegramPolling(
                authToken = authToken,
                generation = generation,
                qrData = qrData,
            )
        }
    }

    fun selectAuthMethod(method: AuthMethod) {
        if (_authMethod.value == method) return
        when (method) {
            AuthMethod.TELEGRAM -> showTelegramLogin()
            AuthMethod.TOBEVPN_APP -> startDevicePairing()
        }
    }

    fun showTelegramLogin() {
        _authMethod.value = AuthMethod.TELEGRAM
        activeAuthFlow = ActiveAuthFlow.TELEGRAM_LOGIN
        sameDeviceTelegramAuthStarted = false
        beginOperation()
        currentAuthToken = null
        currentPairingCode = null
        _uiState.value = AuthUiState.Idle
    }

    fun retryAuth(context: Context) {
        when (activeAuthFlow) {
            ActiveAuthFlow.TELEGRAM_LOGIN -> startTelegramAuth(context)
            ActiveAuthFlow.TELEGRAM_QR -> startTelegramQrPairing()
            ActiveAuthFlow.TOBEVPN_APP -> startDevicePairing()
        }
    }

    fun onReturnedFromTelegram() {
        if (activeAuthFlow != ActiveAuthFlow.TELEGRAM_LOGIN) return
        if (!sameDeviceTelegramAuthStarted && currentAuthToken == null) return
        if (pollingJob?.isActive == true) return
        viewModelScope.launch {
            val token = currentAuthToken ?: authRepository.getPendingAuthToken()
            if (token != null) {
                currentAuthToken = token
                startTelegramPolling(
                    authToken = token,
                    generation = operationGeneration,
                )
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
                        onAuthSuccess(
                            promptForEmail = activeAuthFlow == ActiveAuthFlow.TELEGRAM_LOGIN,
                        )
                    } else if (token != null && pollingJob?.isActive != true) {
                        currentAuthToken = token
                        startTelegramPolling(
                            authToken = token,
                            generation = operationGeneration,
                        )
                    }
                    // If the callback arrives before the backend has marked
                    // the auth as completed, keep polling from the recovered
                    // token instead of leaving the user on a stale screen.
                }
            }
        }
    }

    private fun startTelegramPolling(
        authToken: String,
        generation: Int,
        qrData: String? = null,
    ) {
        pollingJob?.cancel()
        _uiState.value = if (qrData == null) {
            AuthUiState.Polling
        } else {
            AuthUiState.WaitingTelegramPairing(qrData)
        }

        pollingJob = viewModelScope.launch {
            repeat(MAX_POLL_ATTEMPTS) {
                if (!isCurrentOperation(generation)) return@launch
                val authenticated = authRepository.checkAuthStatus(authToken)
                if (!isCurrentOperation(generation)) return@launch
                if (authenticated) {
                    onAuthSuccess(promptForEmail = qrData == null)
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
            if (!isCurrentOperation(generation)) return@launch
            currentAuthToken = null
            // Keep the persisted token: the other person may confirm after
            // this screen timeout. AppSessionViewModel continues polling and
            // safely applies the linked profile in the background.
            _uiState.value = AuthUiState.Error(R.string.auth_error_timeout)
        }
    }

    private fun startDevicePairingPolling(code: String, generation: Int) {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            repeat(MAX_POLL_ATTEMPTS) {
                delay(POLL_INTERVAL_MS)
                if (!isCurrentOperation(generation)) return@launch
                val result = authRepository.checkDevicePairingStatus(code)
                if (!isCurrentOperation(generation)) return@launch
                result.onSuccess { status ->
                    when (status) {
                        DevicePairingPollResult.Completed -> {
                            onAuthSuccess(promptForEmail = false)
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
            if (!isCurrentOperation(generation)) return@launch
            currentPairingCode = null
            _uiState.value = AuthUiState.Error(R.string.auth_error_timeout)
        }
    }

    private fun beginOperation(): Int {
        requestJob?.cancel()
        pollingJob?.cancel()
        operationGeneration += 1
        return operationGeneration
    }

    private fun isCurrentOperation(generation: Int): Boolean {
        return generation == operationGeneration
    }

    private suspend fun onAuthSuccess(promptForEmail: Boolean = true) {
        // AuthRepository finishes the complete transition before returning:
        // local identity, subscription, server cache and active VPN profile.
        // Read the flag BEFORE cancelling the polling job: onAuthSuccess
        // usually runs inside pollingJob itself, and any suspend call after
        // the self-cancel throws CancellationException — which used to
        // silently skip the email prompt on every polling-confirmed login.
        val alreadyShown = if (promptForEmail) {
            prefsDataStore.emailPromptShown.firstOrNull() ?: false
        } else {
            true
        }
        _uiState.value = AuthUiState.Success
        if (!alreadyShown) {
            _showEmailPrompt.value = true
        }
        pollingJob?.cancel()
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

    override fun onCleared() {
        requestJob?.cancel()
        pollingJob?.cancel()
        super.onCleared()
    }
}
