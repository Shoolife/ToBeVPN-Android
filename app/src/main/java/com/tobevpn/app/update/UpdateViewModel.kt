package com.tobevpn.app.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.repository.UpdateCheckResult
import com.tobevpn.app.data.repository.UpdateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the in-app updater state machine.
 *
 *   Idle ─ check() ─▶ Available ─ startDownload() ─▶ Downloading
 *                       │                              │
 *                       │                              ▼
 *                       │                          ReadyToInstall ─ install() ─▶ (system installer)
 *                       │                              │
 *                       └◀ dismiss() ─────────────────┘
 *
 * If the GitHub probe fails or current build is already up-to-date the state
 * stays at [UpdateUiState.Idle] — the banner stays hidden.
 */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val repository: UpdateRepository,
    private val downloader: UpdateDownloader,
    val installer: UpdateInstaller,
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var observeJob: Job? = null
    private var checked = false

    private val _manualCheckInFlight = MutableStateFlow(false)
    val manualCheckInFlight: StateFlow<Boolean> = _manualCheckInFlight.asStateFlow()

    /** Idempotent — calling on every Home recomposition is fine. */
    fun checkOnce() {
        if (checked) return
        checked = true
        if (!BuildConfig.IN_APP_UPDATES_ENABLED) return
        viewModelScope.launch {
            val result = repository.checkForUpdate()
            if (result is UpdateCheckResult.Available) {
                _state.value = UpdateUiState.Available(result)
            }
        }
    }

    /**
     * Bypasses the 7-day cache and forces a fresh GitHub probe. Used by the
     * "Check for updates" button in Settings — gives the user a way to see
     * a new release the moment it ships, without waiting for the next
     * weekly cache expiry.
     */
    fun forceCheck() {
        if (!BuildConfig.IN_APP_UPDATES_ENABLED) return
        if (_manualCheckInFlight.value) return
        viewModelScope.launch {
            _manualCheckInFlight.value = true
            try {
                val result = repository.checkForUpdate(force = true)
                if (result is UpdateCheckResult.Available) {
                    _state.value = UpdateUiState.Available(result)
                } else if (_state.value is UpdateUiState.Available) {
                    // Previously-cached "available" is no longer current.
                    _state.value = UpdateUiState.Idle
                }
            } finally {
                _manualCheckInFlight.value = false
            }
        }
    }

    fun dismiss() {
        cancelDownload()
        _state.value = UpdateUiState.Idle
    }

    fun startDownload() {
        if (!BuildConfig.IN_APP_UPDATES_ENABLED) return
        val available = (_state.value as? UpdateUiState.Available) ?: return
        val info = available.info
        val id = downloader.start(info.apkUrl, info.apkFileName)
        _state.value = UpdateUiState.Downloading(info, id, downloadedBytes = 0L, totalBytes = info.apkSizeBytes)

        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            downloader.observe(id).collect { progress ->
                val current = _state.value
                if (current !is UpdateUiState.Downloading || current.downloadId != id) return@collect

                _state.value = when (progress) {
                    is DownloadProgress.Running ->
                        current.copy(
                            downloadedBytes = progress.downloadedBytes,
                            totalBytes = progress.totalBytes.takeIf { it > 0 } ?: current.totalBytes,
                        )
                    is DownloadProgress.Done ->
                        UpdateUiState.ReadyToInstall(info, progress.localUri)
                    is DownloadProgress.Failed ->
                        UpdateUiState.Failed(info, progress.reason)
                }
            }
        }
    }

    fun cancelDownload() {
        val current = _state.value
        if (current is UpdateUiState.Downloading) {
            downloader.cancel(current.downloadId)
        }
        observeJob?.cancel()
        observeJob = null
    }

    fun retry() {
        val info = (_state.value as? UpdateUiState.Failed)?.info ?: return
        _state.value = UpdateUiState.Available(info)
    }

    override fun onCleared() {
        super.onCleared()
        observeJob?.cancel()
    }
}

sealed interface UpdateUiState {
    data object Idle : UpdateUiState

    data class Available(val info: UpdateCheckResult.Available) : UpdateUiState

    data class Downloading(
        val info: UpdateCheckResult.Available,
        val downloadId: Long,
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : UpdateUiState

    data class ReadyToInstall(
        val info: UpdateCheckResult.Available,
        val localUri: android.net.Uri,
    ) : UpdateUiState

    data class Failed(
        val info: UpdateCheckResult.Available,
        val reason: String,
    ) : UpdateUiState
}
