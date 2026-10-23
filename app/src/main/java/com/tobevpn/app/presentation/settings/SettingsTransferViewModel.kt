package com.tobevpn.app.presentation.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.settings.SettingsImportPreview
import com.tobevpn.app.data.settings.SettingsTransferManager
import com.tobevpn.app.data.settings.SettingsTransferSelection
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsTransferUiState(
    val isBusy: Boolean = false,
    val importPreview: SettingsImportPreview? = null,
)

sealed interface SettingsTransferEvent {
    data object Exported : SettingsTransferEvent
    data class Imported(val languageToApply: String?) : SettingsTransferEvent
    data object Failed : SettingsTransferEvent
}

@HiltViewModel
class SettingsTransferViewModel @Inject constructor(
    private val manager: SettingsTransferManager,
    authRepository: AuthRepository,
) : ViewModel() {
    /** The server-list view section is shown and moved for admins only. */
    val isAdmin: StateFlow<Boolean> = authRepository.observeAuthState()
        .map { (it as? AuthState.Authenticated)?.isAdminProfile == true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _state = MutableStateFlow(SettingsTransferUiState())
    val state: StateFlow<SettingsTransferUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SettingsTransferEvent>(extraBufferCapacity = 2)
    val events: SharedFlow<SettingsTransferEvent> = _events.asSharedFlow()

    fun exportTo(uri: Uri, selection: SettingsTransferSelection) {
        if (_state.value.isBusy || !selection.hasAny) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            runCatching { manager.writeExport(uri, selection, isAdmin.value) }
                .onSuccess { _events.emit(SettingsTransferEvent.Exported) }
                .onFailure {
                    SafeDiagnostics.warn(TAG, "Settings export failed: ${SafeDiagnostics.failureCategory(it)}")
                    _events.emit(SettingsTransferEvent.Failed)
                }
            _state.value = _state.value.copy(isBusy = false)
        }
    }

    fun inspectImport(uri: Uri) {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true, importPreview = null)
            runCatching { manager.readImport(uri, isAdmin.value) }
                .onSuccess {
                    _state.value = _state.value.copy(importPreview = it)
                }
                .onFailure {
                    SafeDiagnostics.warn(TAG, "Settings import read failed: ${SafeDiagnostics.failureCategory(it)}")
                    _events.emit(SettingsTransferEvent.Failed)
                }
            _state.value = _state.value.copy(isBusy = false)
        }
    }

    fun applyImport(selection: SettingsTransferSelection) {
        val preview = _state.value.importPreview ?: return
        if (_state.value.isBusy || !selection.hasAny) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isBusy = true)
            runCatching { manager.applyImport(preview, selection) }
                .onSuccess { language ->
                    _state.value = SettingsTransferUiState()
                    _events.emit(SettingsTransferEvent.Imported(language))
                }
                .onFailure {
                    SafeDiagnostics.warn(TAG, "Settings import apply failed: ${SafeDiagnostics.failureCategory(it)}")
                    _events.emit(SettingsTransferEvent.Failed)
                    _state.value = _state.value.copy(isBusy = false)
                }
        }
    }

    fun clearImport() {
        if (!_state.value.isBusy) _state.value = _state.value.copy(importPreview = null)
    }

    private companion object {
        const val TAG = "SettingsTransfer"
    }
}
