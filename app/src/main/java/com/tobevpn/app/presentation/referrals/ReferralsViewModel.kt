package com.tobevpn.app.presentation.referrals

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.ReferralListItemDto
import com.tobevpn.app.data.remote.dto.ReferralUserDto
import com.tobevpn.app.data.remote.dto.ReferralsDto
import com.tobevpn.app.data.remote.dto.SetReferrerRequestDto
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.HttpException

@HiltViewModel
class ReferralsViewModel @Inject constructor(
    private val botApi: BotApi,
    private val prefsDataStore: PrefsDataStore,
    authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReferralsUiState())
    val uiState: StateFlow<ReferralsUiState> = _uiState.asStateFlow()

    private var requestJob: Job? = null
    private var assignReferrerJob: Job? = null
    private var telegramId: Long? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .map { (it as? AuthState.Authenticated)?.telegramId }
                .distinctUntilChanged()
                .collect { authenticatedTelegramId ->
                    requestJob?.cancel()
                    assignReferrerJob?.cancel()
                    telegramId = authenticatedTelegramId
                    if (authenticatedTelegramId != null) {
                        // The server's error limit outlives the screen, the
                        // app process and token refreshes; restore it so a
                        // reopened screen does not send into a known 429.
                        _uiState.value = ReferralsUiState(
                            isAuthResolved = true,
                            isAuthenticated = true,
                            isInitialLoading = true,
                            referrerLimitedUntil = prefsDataStore
                                .getReferrerLimitUntil(authenticatedTelegramId),
                        )
                        requestPage(reset = true)
                    } else {
                        _uiState.value = ReferralsUiState(
                            isAuthResolved = true,
                            isAuthenticated = false,
                        )
                    }
                }
        }
    }

    fun refresh() {
        val state = _uiState.value
        if (
            !state.isAuthenticated ||
            state.isInitialLoading ||
            state.isRefreshing ||
            state.isLoadingMore ||
            state.isAssigningReferrer
        ) {
            return
        }
        requestPage(reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        val data = state.data ?: return
        val loadedCount = data.referrals.orEmpty().size
        if (
            !state.isAuthenticated ||
            state.isInitialLoading ||
            state.isRefreshing ||
            state.isLoadingMore ||
            state.isAssigningReferrer ||
            loadedCount >= data.total
        ) {
            return
        }
        requestPage(reset = false)
    }

    fun assignReferrer(referrerId: Long) {
        val state = _uiState.value
        if (
            referrerId <= 0 ||
            !state.isAuthenticated ||
            state.data == null ||
            state.data.referrer != null ||
            state.isInitialLoading ||
            state.isRefreshing ||
            state.isLoadingMore ||
            state.isAssigningReferrer ||
            assignReferrerJob?.isActive == true ||
            state.isReferrerLimited(System.currentTimeMillis())
        ) {
            return
        }

        assignReferrerJob = viewModelScope.launch {
            _uiState.value = state.copy(
                isAssigningReferrer = true,
                referrerAssignmentError = null,
            )
            try {
                val response = botApi.setReferrer(
                    SetReferrerRequestDto(referrerId = referrerId),
                )
                if (!response.success) {
                    throw ReferrerAssignmentResponseException()
                }

                val current = _uiState.value
                if (current.isAuthenticated) {
                    _uiState.value = current.copy(
                        data = current.data?.copy(
                            referrer = ReferralUserDto(telegramId = referrerId),
                        ),
                        isAssigningReferrer = false,
                        referrerAssignmentError = null,
                    )
                    requestPage(reset = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "Referrer assignment failed: ${SafeDiagnostics.failureCategory(error)}",
                )
                val limitedUntil = (error as? HttpException)
                    ?.takeIf { it.code() == HTTP_TOO_MANY_REQUESTS }
                    ?.let { limited ->
                        val now = System.currentTimeMillis()
                        val waitSeconds = parseRetryAfterSeconds(
                            limited.response()?.headers()?.get("Retry-After"),
                            now,
                        ) ?: DEFAULT_LIMIT_WAIT_SECONDS
                        (now + waitSeconds * 1_000L).also { until ->
                            telegramId?.let { id -> prefsDataStore.setReferrerLimitUntil(id, until) }
                        }
                    }
                val current = _uiState.value
                if (current.isAuthenticated) {
                    _uiState.value = current.copy(
                        isAssigningReferrer = false,
                        referrerAssignmentError = if (limitedUntil != null) {
                            ReferrerAssignmentError.LIMITED
                        } else {
                            error.toReferrerAssignmentError()
                        },
                        referrerLimitedUntil = limitedUntil ?: current.referrerLimitedUntil,
                    )
                }
            }
        }
    }

    fun clearReferrerAssignmentError() {
        val state = _uiState.value
        // The limit message stays while the window lasts: editing the ID does
        // not lift the server's block.
        if (state.referrerAssignmentError != null &&
            state.referrerAssignmentError != ReferrerAssignmentError.LIMITED
        ) {
            _uiState.value = state.copy(referrerAssignmentError = null)
        }
    }

    private fun requestPage(reset: Boolean) {
        if (reset) {
            requestJob?.cancel()
        } else if (requestJob?.isActive == true) {
            return
        }
        requestJob = viewModelScope.launch {
            fetchPage(reset = reset)
        }
    }

    private suspend fun fetchPage(reset: Boolean) {
        val before = _uiState.value
        if (!before.isAuthenticated) return

        val existingData = before.data
        val offset = if (reset) 0 else existingData?.referrals.orEmpty().size
        val refreshFeedbackStartedAt = if (reset && existingData != null) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
        _uiState.value = before.copy(
            isInitialLoading = reset && existingData == null,
            isRefreshing = reset && existingData != null,
            isLoadingMore = !reset,
            error = null,
        )

        try {
            val response = botApi.getReferrals(limit = PAGE_SIZE, offset = offset)
            val page = response.data
            if (!response.success || page == null) {
                throw ReferralResponseException()
            }

            val merged = if (reset || existingData == null) {
                page.copy(referrals = page.referrals.orEmpty())
            } else {
                mergeReferralPages(existingData, page)
            }
            awaitMinimumRefreshFeedback(refreshFeedbackStartedAt)
            _uiState.value = ReferralsUiState(
                isAuthResolved = true,
                isAuthenticated = true,
                data = merged,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            awaitMinimumRefreshFeedback(refreshFeedbackStartedAt)
            SafeDiagnostics.warn(
                TAG,
                "Referral request failed: ${SafeDiagnostics.failureCategory(error)}",
            )
            val current = _uiState.value
            if (current.isAuthenticated) {
                _uiState.value = current.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    error = error.toReferralLoadError(),
                )
            }
        }
    }

    private companion object {
        const val TAG = "ReferralsViewModel"
        const val PAGE_SIZE = 20
        const val MIN_REFRESH_FEEDBACK_MS = 900L
        const val HTTP_TOO_MANY_REQUESTS = 429
        // Retry-After missing or unreadable: the server's window is at most
        // 24 h, an hour is a safe pause that still lets the user retry today.
        const val DEFAULT_LIMIT_WAIT_SECONDS = 3_600L
    }

    private suspend fun awaitMinimumRefreshFeedback(startedAt: Long?) {
        if (startedAt == null) return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val remaining = MIN_REFRESH_FEEDBACK_MS - elapsed
        if (remaining > 0) delay(remaining)
    }
}

data class ReferralsUiState(
    val isAuthResolved: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isAssigningReferrer: Boolean = false,
    val data: ReferralsDto? = null,
    val error: ReferralLoadError? = null,
    val referrerAssignmentError: ReferrerAssignmentError? = null,
    // End of the server's referrer error-limit window (wall clock, ms).
    val referrerLimitedUntil: Long? = null,
) {
    fun isReferrerLimited(nowMillis: Long): Boolean =
        referrerLimitedUntil != null && nowMillis < referrerLimitedUntil
}

enum class ReferralLoadError {
    NETWORK,
    UNAVAILABLE,
    UNKNOWN,
}

enum class ReferrerAssignmentError {
    NETWORK,
    NOT_FOUND,
    CONFLICT,
    UNAVAILABLE,
    LIMITED,
    UNKNOWN,
}

private class ReferralResponseException : IllegalStateException()
private class ReferrerAssignmentResponseException : IllegalStateException()

private fun Exception.toReferralLoadError(): ReferralLoadError = when (this) {
    is IOException -> ReferralLoadError.NETWORK
    is HttpException -> when (code()) {
        401, 403, 404 -> ReferralLoadError.UNAVAILABLE
        else -> ReferralLoadError.UNKNOWN
    }
    is ReferralResponseException -> ReferralLoadError.UNAVAILABLE
    else -> ReferralLoadError.UNKNOWN
}

private fun Exception.toReferrerAssignmentError(): ReferrerAssignmentError = when (this) {
    is IOException -> ReferrerAssignmentError.NETWORK
    is HttpException -> when (code()) {
        404 -> ReferrerAssignmentError.NOT_FOUND
        409 -> ReferrerAssignmentError.CONFLICT
        401, 403 -> ReferrerAssignmentError.UNAVAILABLE
        else -> ReferrerAssignmentError.UNKNOWN
    }
    is ReferrerAssignmentResponseException -> ReferrerAssignmentError.UNKNOWN
    else -> ReferrerAssignmentError.UNKNOWN
}

internal fun mergeReferralPages(
    current: ReferralsDto,
    next: ReferralsDto,
): ReferralsDto {
    val mergedItems = (current.referrals.orEmpty() + next.referrals.orEmpty())
        .distinctBy(ReferralListItemDto::stableReferralKey)
    return current.copy(
        referralCode = next.referralCode ?: current.referralCode,
        referralUrl = next.referralUrl ?: current.referralUrl,
        referrer = next.referrer ?: current.referrer,
        total = next.total,
        referrals = mergedItems,
        limit = next.limit,
        offset = next.offset,
    )
}

private fun ReferralListItemDto.stableReferralKey(): Triple<Long?, String?, String?> =
    Triple(telegramId, createdAt, displayName)

/**
 * Seconds to wait from a Retry-After header: either delta-seconds (what the
 * referrer endpoint sends) or an HTTP date. Null when absent or unreadable.
 */
internal fun parseRetryAfterSeconds(value: String?, nowMillis: Long): Long? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
    val date = runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(text)
    }.getOrNull() ?: return null
    return ((date.time - nowMillis) / 1_000L).coerceAtLeast(0L)
}
