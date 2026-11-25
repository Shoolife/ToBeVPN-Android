package com.tobevpn.app.presentation.notifications

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.tobevpn.app.data.remote.dto.NotificationDetailDto
import com.tobevpn.app.notifications.NotificationDetailResult
import com.tobevpn.app.notifications.NotificationsError
import com.tobevpn.app.notifications.NotificationsFeedState
import com.tobevpn.app.notifications.NotificationsRepository
import com.tobevpn.app.presentation.components.awaitWholeRefreshTurns
import com.tobevpn.app.presentation.navigation.NotificationDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationsRepository,
) : ViewModel() {

    val feed: StateFlow<NotificationsFeedState> = repository.feed

    init {
        // Entering the section shows the feed loaded on app open; the server
        // is asked again only if there is none or a push made it stale.
        repository.onFeedEntered()
    }

    private val _refreshing = MutableStateFlow(false)

    /**
     * A refresh the user asked for (the top bar button, Retry): the list swaps
     * to placeholders and back as on every screen, and the result lands as
     * the refresh icon comes to rest.
     */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            val startedAt = SystemClock.elapsedRealtime()
            try {
                repository.loadFeed()
                awaitWholeRefreshTurns(startedAt)
            } finally {
                _refreshing.value = false
            }
        }
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
