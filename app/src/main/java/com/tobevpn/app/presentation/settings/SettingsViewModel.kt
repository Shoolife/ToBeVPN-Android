package com.tobevpn.app.presentation.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.DeviceUnlinkRequestDto
import com.tobevpn.app.data.remote.dto.LinkedDeviceDto
import com.tobevpn.app.data.remote.dto.TvPairConfirmRequestDto
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.util.LocaleManager
import com.tobevpn.app.vpn.VpnConnectionManager
import com.tobevpn.app.vpn.XRayCore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val vpnRepository: VpnRepository,
    private val connectionManager: VpnConnectionManager,
    private val botApi: BotApi,
    private val prefsDataStore: PrefsDataStore,
    private val sessionDao: SessionDao,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Anonymous)

    val xrayVersion: String = XRayCore.getVersion()

    val userEmail: StateFlow<String?> = sessionDao.observeEmail()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _emailSaveResult = MutableStateFlow<EmailSaveResult?>(null)
    val emailSaveResult: StateFlow<EmailSaveResult?> = _emailSaveResult.asStateFlow()

    private val _language = MutableStateFlow(LocaleManager.current())
    val language: StateFlow<String> = _language.asStateFlow()

    private val _tvPairResult = MutableStateFlow<TvPairResult?>(null)
    val tvPairResult: StateFlow<TvPairResult?> = _tvPairResult.asStateFlow()

    private val _linkedDevicesState = MutableStateFlow(LinkedDevicesUiState())
    val linkedDevicesState: StateFlow<LinkedDevicesUiState> = _linkedDevicesState.asStateFlow()

    init {
        viewModelScope.launch {
            // Don't clobber the live local usage counter while a VPN session is
            // active — sync only the plan/limits, not the traffic number.
            val isConnected = connectionManager.connectionState.value is ConnectionState.Connected
            authRepository.syncSubscription(overwriteUsage = !isConnected)
        }
        viewModelScope.launch {
            authState.collectLatest { state ->
                if (state is AuthState.Authenticated) {
                    authRepository.registerCurrentDevice()
                } else {
                    _linkedDevicesState.value = LinkedDevicesUiState()
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            // Tear down any active VPN session before flipping identity.
            // Without this the existing tunnel keeps running with the previous
            // panel user's VLESS UUID — by the time the user signs out, that
            // panel user is unlinked server-side, so the tunnel is sending
            // traffic under a stale identity until it's manually disconnected.
            val state = connectionManager.connectionState.value
            if (state is ConnectionState.Connected || state is ConnectionState.Connecting) {
                connectionManager.stopVpn()
            }
            authRepository.logout()
            // Refresh servers so VPN uses the anonymous panel user's VLESS UUID
            vpnRepository.refreshServers()
        }
    }

    fun saveEmail(email: String) {
        viewModelScope.launch {
            _emailSaveResult.value = EmailSaveResult.Saving
            val result = authRepository.saveEmail(email)
            _emailSaveResult.value = if (result.isSuccess) {
                EmailSaveResult.Success
            } else {
                EmailSaveResult.Error
            }
        }
    }

    fun clearEmailResult() {
        _emailSaveResult.value = null
    }

    fun setLanguage(tag: String) {
        LocaleManager.apply(tag)
        _language.value = tag
    }

    fun refreshLinkedDevices() {
        val current = authState.value
        if (current !is AuthState.Authenticated) {
            _linkedDevicesState.value = LinkedDevicesUiState(
                errorMessage = "Sign in to manage devices.",
            )
            return
        }

        viewModelScope.launch {
            val currentDeviceId = authRepository.getOrCreateDeviceId()
            _linkedDevicesState.value = _linkedDevicesState.value.copy(
                isLoading = true,
                errorMessage = null,
                currentDeviceId = currentDeviceId,
            )

            authRepository.registerCurrentDevice()

            _linkedDevicesState.value = try {
                val response = botApi.getDevices()
                val data = response.data
                if (response.success && data != null) {
                    LinkedDevicesUiState(
                        isLoading = false,
                        currentDeviceId = currentDeviceId,
                        maxDevices = data.maxDevices,
                        devices = data.devices,
                    )
                } else {
                    _linkedDevicesState.value.copy(
                        isLoading = false,
                        errorMessage = response.message ?: "Could not load devices.",
                    )
                }
            } catch (e: Exception) {
                _linkedDevicesState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Could not load devices.",
                )
            }
        }
    }

    fun disconnectDevice(deviceId: String) {
        val current = authState.value
        if (current !is AuthState.Authenticated) return

        viewModelScope.launch {
            _linkedDevicesState.value = _linkedDevicesState.value.copy(
                busyDeviceId = deviceId,
                errorMessage = null,
            )
            _linkedDevicesState.value = try {
                val response = botApi.unlinkDevice(
                    DeviceUnlinkRequestDto(deviceId = deviceId)
                )
                if (response.success) {
                    _linkedDevicesState.value.copy(busyDeviceId = null)
                } else {
                    _linkedDevicesState.value.copy(
                        busyDeviceId = null,
                        errorMessage = response.message ?: context.getString(R.string.error_devices_disconnect),
                    )
                }
            } catch (_: Exception) {
                _linkedDevicesState.value.copy(
                    busyDeviceId = null,
                    errorMessage = context.getString(R.string.error_devices_disconnect),
                )
            }
            refreshLinkedDevices()
        }
    }

    fun clearLinkedDevicesError() {
        _linkedDevicesState.value = _linkedDevicesState.value.copy(errorMessage = null)
    }

    /** Confirms a legacy pairing code against the backend. */
    fun confirmTvPairing(code: String) {
        val current = authState.value
        if (current !is AuthState.Authenticated) {
            _tvPairResult.value = TvPairResult.Error(context.getString(R.string.error_auth_required))
            return
        }
        viewModelScope.launch {
            _tvPairResult.value = TvPairResult.Loading
            _tvPairResult.value = try {
                val response = botApi.confirmTvPairing(
                    TvPairConfirmRequestDto(code = code),
                )
                if (response.success) {
                    refreshLinkedDevices()
                    TvPairResult.Success
                } else {
                    TvPairResult.Error(response.message ?: context.getString(R.string.error_generic))
                }
            } catch (_: Exception) {
                TvPairResult.Error(context.getString(R.string.error_network))
            }
        }
    }

    fun clearTvPairResult() {
        _tvPairResult.value = null
    }

    fun setTvPairError(message: String) {
        _tvPairResult.value = TvPairResult.Error(message)
    }
}

data class LinkedDevicesUiState(
    val isLoading: Boolean = false,
    val currentDeviceId: String? = null,
    val maxDevices: Int = 5,
    val devices: List<LinkedDeviceDto> = emptyList(),
    val busyDeviceId: String? = null,
    val errorMessage: String? = null,
)

sealed interface EmailSaveResult {
    data object Saving : EmailSaveResult
    data object Success : EmailSaveResult
    data object Error : EmailSaveResult
}

sealed interface TvPairResult {
    data object Loading : TvPairResult
    data object Success : TvPairResult
    data class Error(val message: String) : TvPairResult
}
