package com.tobevpn.app.presentation.promocodes

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.tobevpn.app.data.remote.dto.PromocodeActivationResultDto
import com.tobevpn.app.data.remote.dto.PromocodeErrorEnvelopeDto
import com.tobevpn.app.data.remote.dto.PromocodeHistoryDto
import com.tobevpn.app.data.remote.dto.PromocodeHistoryItemDto
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.PromocodeAuthenticationException
import com.tobevpn.app.data.repository.PromocodeRepository
import com.tobevpn.app.data.repository.PromocodeResponseException
import com.tobevpn.app.data.repository.PurchaseRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.vpn.VpnConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import retrofit2.HttpException

@HiltViewModel
class PromocodesViewModel @Inject constructor(
    private val promocodeRepository: PromocodeRepository,
    private val purchaseRepository: PurchaseRepository,
    private val authRepository: AuthRepository,
    private val vpnRepository: VpnRepository,
    private val connectionManager: VpnConnectionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PromocodesUiState())
    val uiState: StateFlow<PromocodesUiState> = _uiState.asStateFlow()

    private var historyJob: Job? = null
    private var activationJob: Job? = null

    init {
        viewModelScope.launch {
            authRepository.observeAuthState()
                .map { it is AuthState.Authenticated }
                .distinctUntilChanged()
                .collect { authenticated ->
                    historyJob?.cancel()
                    activationJob?.cancel()
                    if (authenticated) {
                        _uiState.value = PromocodesUiState(
                            isAuthResolved = true,
                            isAuthenticated = true,
                            isInitialLoading = true,
                        )
                        requestPage(reset = true)
                    } else {
                        _uiState.value = PromocodesUiState(
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
            state.isActivating
        ) {
            return
        }
        requestPage(reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        val history = state.history ?: return
        if (
            !state.isAuthenticated ||
            state.isInitialLoading ||
            state.isRefreshing ||
            state.isLoadingMore ||
            state.isActivating ||
            history.promocodes.orEmpty().size >= history.total
        ) {
            return
        }
        requestPage(reset = false)
    }

    fun activate(rawCode: String) {
        val code = rawCode.trim().uppercase(Locale.ROOT)
        val state = _uiState.value
        if (
            code.isBlank() ||
            !state.isAuthenticated ||
            state.isInitialLoading ||
            state.isActivating ||
            activationJob?.isActive == true
        ) {
            return
        }

        activationJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isActivating = true,
                activationError = null,
                activationResult = null,
            )
            try {
                val result = promocodeRepository.activate(code)
                _uiState.value = _uiState.value.copy(
                    isActivating = true,
                    activationError = null,
                    activationResult = result,
                )
                refreshAccountAfterActivation(result)
                _uiState.value = _uiState.value.copy(isActivating = false)
                requestPage(reset = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeDiagnostics.warn(
                    TAG,
                    "Promocode activation failed: ${SafeDiagnostics.failureCategory(error)}",
                )
                val current = _uiState.value
                if (current.isAuthenticated) {
                    _uiState.value = current.copy(
                        isActivating = false,
                        activationError = error.toPromocodeActivationError(),
                    )
                }
            }
        }
    }

    fun clearActivationError() {
        val current = _uiState.value
        if (current.activationError != null) {
            _uiState.value = current.copy(activationError = null)
        }
    }

    fun dismissActivationResult() {
        val current = _uiState.value
        if (current.activationResult != null) {
            _uiState.value = current.copy(activationResult = null)
        }
    }

    private fun requestPage(reset: Boolean) {
        if (reset) {
            historyJob?.cancel()
        } else if (historyJob?.isActive == true) {
            return
        }
        historyJob = viewModelScope.launch {
            fetchPage(reset = reset)
        }
    }

    private suspend fun fetchPage(reset: Boolean) {
        val before = _uiState.value
        if (!before.isAuthenticated) return

        val existing = before.history
        val offset = if (reset) 0 else existing?.promocodes.orEmpty().size
        val refreshFeedbackStartedAt = if (reset && existing != null) {
            SystemClock.elapsedRealtime()
        } else {
            null
        }
        _uiState.value = before.copy(
            isInitialLoading = reset && existing == null,
            isRefreshing = reset && existing != null,
            isLoadingMore = !reset,
            loadError = null,
        )

        try {
            val (page, refreshedDiscount) = coroutineScope {
                val history = async {
                    promocodeRepository.getHistory(limit = PAGE_SIZE, offset = offset)
                }
                val discount = if (reset) {
                    async {
                        purchaseRepository.getPlans()
                            ?.effectiveDiscountPercent
                            ?.coerceIn(0, 100)
                    }
                } else {
                    null
                }
                history.await() to discount?.await()
            }
            val merged = if (reset || existing == null) {
                page.copy(promocodes = page.promocodes.orEmpty())
            } else {
                mergePromocodePages(existing, page)
            }
            awaitMinimumRefreshFeedback(refreshFeedbackStartedAt)
            val current = _uiState.value
            if (current.isAuthenticated) {
                _uiState.value = current.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    history = merged,
                    effectiveDiscountPercent = refreshedDiscount
                        ?: current.effectiveDiscountPercent,
                    loadError = null,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            awaitMinimumRefreshFeedback(refreshFeedbackStartedAt)
            SafeDiagnostics.warn(
                TAG,
                "Promocode history request failed: ${SafeDiagnostics.failureCategory(error)}",
            )
            val current = _uiState.value
            if (current.isAuthenticated) {
                _uiState.value = current.copy(
                    isInitialLoading = false,
                    isRefreshing = false,
                    isLoadingMore = false,
                    loadError = error.toPromocodeLoadError(),
                )
            }
        }
    }

    private suspend fun refreshAccountAfterActivation(result: PromocodeActivationResultDto) {
        val rewardType = result.rewardType.orEmpty().uppercase(Locale.ROOT)
        if (rewardType == "PERSONAL_DISCOUNT" || rewardType == "PURCHASE_DISCOUNT") {
            return
        }

        val isConnected = connectionManager.connectionState.value is ConnectionState.Connected
        runCatching {
            authRepository.syncSubscription(
                overwriteUsage = !isConnected,
                force = true,
            )
        }.onFailure { error ->
            SafeDiagnostics.warn(
                TAG,
                "Post-promocode subscription sync failed: ${SafeDiagnostics.failureCategory(error)}",
            )
        }
        vpnRepository.refreshServers(forceRefresh = true).onFailure { error ->
            SafeDiagnostics.warn(
                TAG,
                "Post-promocode server refresh failed: ${SafeDiagnostics.failureCategory(error)}",
            )
        }
    }

    private suspend fun awaitMinimumRefreshFeedback(startedAt: Long?) {
        if (startedAt == null) return
        val remaining = MIN_REFRESH_FEEDBACK_MS -
            (SystemClock.elapsedRealtime() - startedAt)
        if (remaining > 0) delay(remaining)
    }

    private companion object {
        const val TAG = "PromocodesViewModel"
        const val PAGE_SIZE = 20
        const val MIN_REFRESH_FEEDBACK_MS = 800L
    }
}

data class PromocodesUiState(
    val isAuthResolved: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isInitialLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isActivating: Boolean = false,
    val history: PromocodeHistoryDto? = null,
    val effectiveDiscountPercent: Int = 0,
    val loadError: PromocodeLoadError? = null,
    val activationError: PromocodeActivationError? = null,
    val activationResult: PromocodeActivationResultDto? = null,
)

enum class PromocodeLoadError {
    NETWORK,
    AUTH_REQUIRED,
    UNAVAILABLE,
    UNKNOWN,
}

enum class PromocodeActivationError {
    NETWORK,
    NOT_FOUND,
    EXPIRED,
    ALREADY_ACTIVATED,
    ACTIVE_SUBSCRIPTION_REQUIRED,
    ALREADY_UNLIMITED,
    ACTIVATION_LIMIT_REACHED,
    NEW_USERS_ONLY,
    EXISTING_USERS_ONLY,
    INVITED_USERS_ONLY,
    NOT_AVAILABLE,
    AUTH_REQUIRED,
    TOO_MANY_REQUESTS,
    UNKNOWN,
}

internal data class ParsedPromocodeServerError(
    val code: String?,
    val message: String?,
)

internal fun parsePromocodeErrorBody(rawBody: String?): ParsedPromocodeServerError? {
    if (rawBody.isNullOrBlank()) return null
    return runCatching {
        Gson().fromJson(rawBody, PromocodeErrorEnvelopeDto::class.java)
            ?.detail
            ?.let { detail ->
                ParsedPromocodeServerError(
                    code = detail.code,
                    message = detail.message,
                )
            }
    }.getOrNull()
}

internal fun mergePromocodePages(
    current: PromocodeHistoryDto,
    next: PromocodeHistoryDto,
): PromocodeHistoryDto {
    val items = (current.promocodes.orEmpty() + next.promocodes.orEmpty())
        .distinctBy(PromocodeHistoryItemDto::stableKey)
    return current.copy(
        telegramId = next.telegramId.takeIf { it > 0 } ?: current.telegramId,
        total = next.total,
        limit = next.limit,
        offset = next.offset,
        promocodes = items,
    )
}

private fun PromocodeHistoryItemDto.stableKey(): String = activationId
    ?.let { "activation:$it" }
    ?: listOf(promocodeId, code, rewardType, activatedAt).joinToString("|")

private fun Exception.toPromocodeLoadError(): PromocodeLoadError = when (this) {
    is IOException -> PromocodeLoadError.NETWORK
    is HttpException -> when (code()) {
        401, 403, 404 -> PromocodeLoadError.AUTH_REQUIRED
        in 500..599 -> PromocodeLoadError.UNAVAILABLE
        else -> PromocodeLoadError.UNKNOWN
    }
    is PromocodeResponseException -> PromocodeLoadError.UNAVAILABLE
    else -> PromocodeLoadError.UNKNOWN
}

internal fun Exception.toPromocodeActivationError(): PromocodeActivationError {
    if (this is IOException) return PromocodeActivationError.NETWORK
    if (this is PromocodeAuthenticationException) return PromocodeActivationError.AUTH_REQUIRED
    if (this is PromocodeResponseException) return PromocodeActivationError.UNKNOWN
    if (this !is HttpException) return PromocodeActivationError.UNKNOWN

    val serverError = parsePromocodeErrorBody(
        runCatching { response()?.errorBody()?.string() }.getOrNull(),
    )
    val serverCode = serverError?.code.orEmpty().uppercase(Locale.ROOT)
    val serverMessage = serverError?.message.orEmpty().lowercase(Locale.ROOT)

    return mapPromocodeActivationHttpError(
        httpStatus = code(),
        serverCode = serverCode,
        serverMessage = serverMessage,
    )
}

internal fun mapPromocodeActivationHttpError(
    httpStatus: Int,
    serverCode: String?,
    serverMessage: String?,
): PromocodeActivationError {
    val normalizedCode = serverCode.orEmpty().uppercase(Locale.ROOT)
    val normalizedMessage = serverMessage.orEmpty().lowercase(Locale.ROOT)

    return when {
        httpStatus == 401 || httpStatus == 403 || normalizedCode == "USER_NOT_FOUND" ->
            PromocodeActivationError.AUTH_REQUIRED
        httpStatus == 429 -> PromocodeActivationError.TOO_MANY_REQUESTS
        normalizedCode == "PROMOCODE_REQUEST_ID_CONFLICT" ->
            // The repository has already discarded the conflicting local key,
            // so the generic retry message is the only actionable guidance.
            PromocodeActivationError.UNKNOWN
        normalizedCode == "PROMOCODE_INVALID" -> PromocodeActivationError.NOT_AVAILABLE
        normalizedCode == "PROMOCODE_NOT_FOUND" || httpStatus == 404 ->
            PromocodeActivationError.NOT_FOUND
        normalizedCode == "PROMOCODE_EXPIRED" -> PromocodeActivationError.EXPIRED
        normalizedCode == "PROMOCODE_ALREADY_ACTIVATED" ->
            PromocodeActivationError.ALREADY_ACTIVATED
        normalizedCode == "PROMOCODE_NOT_AVAILABLE" -> when {
            "active subscription required" in normalizedMessage ->
                PromocodeActivationError.ACTIVE_SUBSCRIPTION_REQUIRED
            "already unlimited" in normalizedMessage ->
                PromocodeActivationError.ALREADY_UNLIMITED
            "activation limit" in normalizedMessage ->
                PromocodeActivationError.ACTIVATION_LIMIT_REACHED
            "new users only" in normalizedMessage -> PromocodeActivationError.NEW_USERS_ONLY
            "existing users only" in normalizedMessage ->
                PromocodeActivationError.EXISTING_USERS_ONLY
            "invited users only" in normalizedMessage ->
                PromocodeActivationError.INVITED_USERS_ONLY
            else -> PromocodeActivationError.NOT_AVAILABLE
        }
        httpStatus == 400 || httpStatus == 409 -> PromocodeActivationError.NOT_AVAILABLE
        else -> PromocodeActivationError.UNKNOWN
    }
}
