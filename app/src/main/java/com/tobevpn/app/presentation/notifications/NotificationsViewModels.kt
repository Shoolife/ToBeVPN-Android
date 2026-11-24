package com.tobevpn.app.presentation.notifications

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.tobevpn.app.data.remote.dto.NotificationDetailDto
import com.tobevpn.app.notifications.NotificationDetailResult
import com.tobevpn.app.notifications.NotificationsError
import com.tobevpn.app.notifications.NotificationsFeedState
import com.tobevpn.app.notifications.NotificationsRepository
import com.tobevpn.app.presentation.navigation.NotificationDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
) : ViewModel() {

    val feed: StateFlow<NotificationsFeedState> = repository.feed

    init {
        // Entering the section: one feed request. The result lives in the
        // repository, so returning from an announcement does not reload it.
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { repository.loadFeed() }
    }
}

sealed interface NotificationDetailUiState {
    data object Loading : NotificationDetailUiState
    data class Loaded(val detail: NotificationDetailDto) : NotificationDetailUiState
    data class Failed(val error: NotificationsError) : NotificationDetailUiState
}

@HiltViewModel
class NotificationDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: NotificationsRepository,
) : ViewModel() {

    private val id = savedStateHandle.toRoute<NotificationDetailRoute>().id

    private val _state = MutableStateFlow<NotificationDetailUiState>(NotificationDetailUiState.Loading)
    val state: StateFlow<NotificationDetailUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = NotificationDetailUiState.Loading
        viewModelScope.launch {
            _state.value = when (val result = repository.loadDetail(id)) {
                is NotificationDetailResult.Loaded -> NotificationDetailUiState.Loaded(result.detail)
                is NotificationDetailResult.Failed -> NotificationDetailUiState.Failed(result.error)
            }
        }
    }

    /** The full text is on screen: mark it read and show the server's time. */
    fun onShown() {
        val loaded = _state.value as? NotificationDetailUiState.Loaded ?: return
        // Already read: the status shown is enough.
        if (loaded.detail.readAt != null) return
        viewModelScope.launch {
            val readAt = repository.markRead(loaded.detail.id) ?: return@launch
            val current = _state.value as? NotificationDetailUiState.Loaded ?: return@launch
            if (current.detail.id == loaded.detail.id) {
                _state.value = current.copy(detail = current.detail.copy(readAt = readAt))
            }
        }
    }
}
