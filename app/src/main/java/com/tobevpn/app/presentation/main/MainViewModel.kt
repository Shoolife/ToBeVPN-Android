package com.tobevpn.app.presentation.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PendingPurchaseState
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.remote.dto.PurchasePlansDto
import com.tobevpn.app.data.repository.AppFilterRepository
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.data.repository.CurrencyRepository
import com.tobevpn.app.data.repository.PurchaseRepository
import com.tobevpn.app.data.repository.ServerQualityRepository
import com.tobevpn.app.data.repository.VpnRepository
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.domain.model.ConnectionState
import com.tobevpn.app.domain.model.Server
import com.tobevpn.app.domain.model.UsageInfo
import com.tobevpn.app.domain.model.UserPlan
import com.tobevpn.app.presentation.servers.resolveSelectedServer
import com.tobevpn.app.util.DeepLinkBus
import com.tobevpn.app.util.PaymentNotifications
import com.tobevpn.app.util.SafeDiagnostics
import com.tobevpn.app.vpn.VpnConnectionManager
import com.tobevpn.app.vpn.VpnToggleController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

data class CurrentPlanLimits(
    val trafficLimitBytes: Long?,
    val deviceLimit: Int?,
    val renewalUrl: String?,
)

private data class SelectedServerSnapshot(
    val server: Server?,
    val selectedId: String?,
    val selectedKey: String?,
    val automatic: Boolean,
) {
    fun hasSameSelection(other: SelectedServerSnapshot): Boolean =
        selectedId == other.selectedId &&
            selectedKey == other.selectedKey &&
            automatic == other.automatic
}

@HiltViewModel
class MainViewModel @Inject constructor(
    private val connectionManager: VpnConnectionManager,
    private val vpnToggleController: VpnToggleController,
    private val vpnRepository: VpnRepository,
    private val authRepository: AuthRepository,
    private val prefsDataStore: PrefsDataStore,
    private val currencyRepository: CurrencyRepository,
    private val purchaseRepository: PurchaseRepository,
    private val appFilterRepository: AppFilterRepository,
    private val serverQualityRepository: ServerQualityRepository,
    private val deepLinkBus: DeepLinkBus,
    private val paymentNotifications: PaymentNotifications,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _rubToUsdRate = MutableStateFlow<Double?>(null)
    val rubToUsdRate: StateFlow<Double?> = _rubToUsdRate

    private val _purchasePlans = MutableStateFlow<PurchasePlansDto?>(null)
    val purchasePlans: StateFlow<PurchasePlansDto?> = _purchasePlans

    private val _purchasePlansFromCache = MutableStateFlow(false)
    val purchasePlansFromCache: StateFlow<Boolean> = _purchasePlansFromCache

    private val _purchasePlansLoading = MutableStateFlow(false)
    val purchasePlansLoading: StateFlow<Boolean> = _purchasePlansLoading

    private val _purchasePlansLoaded = MutableStateFlow(false)
    val purchasePlansLoaded: StateFlow<Boolean> = _purchasePlansLoaded

    private val _currentLimits = MutableStateFlow<CurrentPlanLimits?>(null)
    val currentLimits: StateFlow<CurrentPlanLimits?> = _currentLimits

    private val _connectionPreparation = MutableStateFlow(false)
    val connectionPreparation: StateFlow<Boolean> = _connectionPreparation

    private val _paymentSuccessVisible = MutableStateFlow(false)
    val paymentSuccessVisible: StateFlow<Boolean> = _paymentSuccessVisible

    val connectionState: StateFlow<ConnectionState> = connectionManager.connectionState
    val usageInfo: StateFlow<UsageInfo> = connectionManager.usageInfo
    val sessionTimeSeconds: StateFlow<Long> = connectionManager.sessionTimeSeconds
    val sessionBytes: StateFlow<Long> = connectionManager.sessionBytes

    /**
     * Reactive reminder shown on Home when the user's app-filter selection
     * would silently trap their traffic — currently only the "Whitelist
     * with no apps picked" case (a connect attempt would establish a TUN
     * that nothing is allowed to use). Emits null when the filter is fine,
     * a localized message when it isn't. The Home screen renders this as
     * the same red ErrorCard it uses for connection errors, so the warning
     * disappears the moment the user picks an app or switches mode.
     */
    val appFilterReminder: StateFlow<String?> = appFilterRepository.observeState()
        .map { state ->
            if (state.mode == AppFilterMode.WHITELIST && state.selectedPackages.isEmpty()) {
                context.getString(R.string.app_filter_empty_warning)
            } else {
                null
            }
        }
        // Eagerly so the reminder is computed at MainViewModel construction
        // — otherwise the first paint of Home rides on the StateFlow's
        // initial `null` until the WhileSubscribed window opens, and the
        // user briefly sees a blank "Disconnected" line before the
        // reminder pops in. With Eagerly the reminder is in its final
        // state by the time Home composes for the first time.
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // 0 = not measured yet, >0 = successful TCP connect latency, <0 = unreachable.
    private val _serverPing = MutableStateFlow<Long>(0)

    // Show the selected server (from prefs), not just the connected one.
    // Keep the raw selection fields too: a server can change because the
    // background subscription refresh rewrote the cache, and that must not be
    // treated as a user-requested server switch while VPN is already running.
    private val selectedServerSnapshot: StateFlow<SelectedServerSnapshot> = combine(
        prefsDataStore.selectedServerId,
        prefsDataStore.selectedServerKey,
        prefsDataStore.automaticServerSelection,
        vpnRepository.observeServers(),
        _serverPing,
    ) { selectedId, selectedKey, automatic, servers, ping ->
        val server = resolveSelectedServer(
            servers = servers,
            selectedId = selectedId,
            selectedKey = selectedKey,
            allowFallback = automatic,
        )
        SelectedServerSnapshot(
            server = server?.copy(ping = ping),
            selectedId = selectedId,
            selectedKey = selectedKey,
            automatic = automatic,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        SelectedServerSnapshot(null, null, null, true),
    )

    val currentServer: StateFlow<Server?> = selectedServerSnapshot
        .map { it.server }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val automaticServerSelection: StateFlow<Boolean> = prefsDataStore.automaticServerSelection
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val authState: StateFlow<AuthState> = authRepository.observeAuthState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuthState.Anonymous)

    val subscriptionUsageBlocked: StateFlow<Boolean> = authRepository.observeSubscriptionUsageBlocked()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val updateRequired: StateFlow<Boolean> = prefsDataStore.observeUpdateRequired()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Home screen visibility — gates the periodic ping loop so it doesn't
    // keep opening TCP probes while the app is backgrounded.
    private val screenActive = MutableStateFlow(false)

    private var initialized = false
    private var lastSyncTime = 0L
    private var pendingPurchaseRefreshJob: Job? = null
    private val pendingPurchaseRefreshSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private var paymentFeedbackSent = false
    private var connectionPreparationJob: Job? = null
    private var connectionPreparationRequest = 0
    private var purchaseLoadRequest = 0
    private var purchaseStateOwner: AuthState? = null
    private var purchaseStateOwnerInitialized = false
    private val purchasePlansGson = Gson()

    private companion object {
        const val TAG = "MainViewModel"
        const val RESUME_SYNC_THROTTLE_MS = 5_000L
        const val PURCHASE_REFRESH_ACTIVE_WINDOW_MS = 2L * 60L * 1000L
        const val PURCHASE_REFRESH_TOTAL_WINDOW_MS = 10L * 60L * 1000L
        const val PURCHASE_REFRESH_INTERVAL_MS = 3_000L
        const val PURCHASE_REFRESH_SLOW_INTERVAL_MS = 30_000L
        const val PURCHASE_PENDING_MAX_AGE_MS = 30L * 60L * 1000L
    }

    private fun launchGuarded(
        operation: String,
        block: suspend () -> Unit,
    ): Job = viewModelScope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeDiagnostics.warn(TAG, "$operation failed: ${SafeDiagnostics.failureCategory(error)}")
        }
    }

    init {
        launchGuarded("Initial sync") {
            authRepository.fetchRemoteConfig()
            authRepository.getOrCreateDeviceId()
            authRepository.ensurePanelUser()
            // Force the first sync of each cold start — the throttle window
            // exists to keep onResume / screen-open from hammering the panel,
            // not to skip the user's expectation that opening the app shows
            // the actual current plan. Otherwise a stale cached "FREE_TRIAL"
            // (e.g. from a transient panel hiccup that returned empty squads)
            // sticks for up to 12h until the next force-refresh.
            authRepository.syncSubscription(force = true)
            val servers = vpnRepository.refreshServers().getOrNull().orEmpty()
            vpnToggleController.ensureAutomaticServerSelected(servers, forceSelection = true)
            lastSyncTime = System.currentTimeMillis()
            initialized = true
            startPendingPurchaseRefreshIfNeeded()
        }
        launchGuarded("Currency refresh") {
            _rubToUsdRate.value = currencyRepository.getRubToUsdRate()
        }
        launchGuarded("Payment callback observer") {
            deepLinkBus.paymentCallbacks.collect {
                pendingPurchaseRefreshSignal.trySend(Unit)
                startPendingPurchaseRefreshIfNeeded()
            }
        }
        launchGuarded("Connection state observer") {
            connectionState.collect { state ->
                if (state is ConnectionState.Connecting ||
                    state is ConnectionState.Connected ||
                    state is ConnectionState.Error
                ) {
                    _connectionPreparation.value = false
                    connectionPreparationJob = null
                }
            }
        }
        // Measure ping immediately when server appears/changes, then every 5s.
        // If VPN is currently active and the selected server changes,
        // automatically reconnect to the new server.
        launchGuarded("Selected server observer") {
            var lastSnapshot: SelectedServerSnapshot? = null
            selectedServerSnapshot.collect { snapshot ->
                val server = snapshot.server
                if (server != null) {
                    val previousSnapshot = lastSnapshot
                    val previousServer = previousSnapshot?.server
                    val vpnConfigChanged = previousServer == null || !server.hasSameVpnConfig(previousServer)
                    val selectionChanged = previousSnapshot != null &&
                        !snapshot.hasSameSelection(previousSnapshot)
                    lastSnapshot = snapshot
                    if (vpnConfigChanged || selectionChanged) {
                        _serverPing.value = serverQualityRepository.measurePing(server, force = true)
                    }
                    // Auto-reconnect if VPN was running on a different server.
                    // Only do it for a real selected-server preference change.
                    // Plain subscription/server-list refreshes can reorder or
                    // re-resolve the same automatic choice; reconnecting there
                    // makes navigation to Settings look like VPN off/on.
                    // Skip when the new pick is the panel's "subscription
                    // expired" sentinel — switchServer would call startVpn,
                    // and xray would crash on its all-zeros uuid. Skip on
                    // EXPIRED plan in general so we don't pretend the user
                    // can roam between servers when nothing is going to
                    // tunnel anyway.
                    if (selectionChanged && !server.isSentinel && !isExpired()) {
                        val state = connectionState.value
                        val managedServer = connectionManager.currentServer.value
                        if (_connectionPreparation.value) {
                            cancelConnectionPreparation()
                            connectionManager.stopVpn()
                            prepareAndStartConnection()
                        } else if ((state is ConnectionState.Connected || state is ConnectionState.Connecting) &&
                            managedServer?.hasSameVpnConfig(server) != true
                        ) {
                            connectionManager.switchServer(server)
                        }
                    }
                }
            }
        }
        // Auto-stop the tunnel the moment we detect the user's plan has
        // gone EXPIRED while a session is live. Without this the green
        // "Connected" badge stays on the screen indefinitely (the panel
        // told us we're expired but xray keeps the tunnel up locally),
        // and the next user action — picking a server — would drag the
        // expired sentinel through the auto-reconnect path.
        launchGuarded("Auth state observer") {
            authRepository.observeAuthState().collect { state ->
                if (!purchaseStateOwnerInitialized) {
                    purchaseStateOwner = state
                    purchaseStateOwnerInitialized = true
                } else if (state != purchaseStateOwner) {
                    clearPurchaseState()
                    purchaseStateOwner = state
                }
                if (state !is AuthState.Authenticated) {
                    prefsDataStore.clearPendingPurchase()
                }
                val expired = state is AuthState.Authenticated && state.plan == UserPlan.EXPIRED
                if (expired) {
                    val curr = connectionState.value
                    if (curr is ConnectionState.Connected || curr is ConnectionState.Connecting) {
                        connectionManager.stopVpn()
                    }
                }
            }
        }
        launchGuarded("Subscription block observer") {
            subscriptionUsageBlocked.collect { blocked ->
                if (blocked) {
                    clearPurchaseState()
                    prefsDataStore.clearPendingPurchase()
                }
            }
        }
        launchGuarded("Periodic ping refresh") {
            while (true) {
                delay(5000)
                if (!screenActive.value) continue
                val server = currentServer.value ?: continue
                val ping = serverQualityRepository.measurePing(server, force = true)
                _serverPing.value = ping
            }
        }
    }

    private fun isExpired(): Boolean {
        val state = authState.value
        return state is AuthState.Authenticated && state.plan == UserPlan.EXPIRED
    }

    fun toggleConnection() {
        if (_connectionPreparation.value) {
            cancelConnectionPreparation()
            connectionManager.stopVpn()
            return
        }

        val state = connectionState.value
        if ((state is ConnectionState.Disconnected || state is ConnectionState.Error) &&
            connectionManager.isOwnVpnNetworkActive()
        ) {
            cancelConnectionPreparation()
            connectionManager.stopVpn()
            return
        }

        when (state) {
            is ConnectionState.Disconnected, is ConnectionState.Error -> prepareAndStartConnection()
            is ConnectionState.Connected, is ConnectionState.Connecting -> {
                cancelConnectionPreparation()
                connectionManager.stopVpn()
            }
        }
    }

    private fun prepareAndStartConnection() {
        connectionPreparationJob?.cancel()
        val request = ++connectionPreparationRequest
        _connectionPreparation.value = true
        connectionPreparationJob = viewModelScope.launch {
            var submittedToManager = false
            try {
                val selectedServer = currentServer.value ?: run {
                    connectionManager.showError(context.getString(R.string.error_no_servers))
                    return@launch
                }
                val server = vpnToggleController.prepareServerForConnect(selectedServer) ?: run {
                    connectionManager.showError(context.getString(R.string.error_no_servers))
                    return@launch
                }
                if (request != connectionPreparationRequest || !_connectionPreparation.value) {
                    return@launch
                }
                // VpnConnectionManager performs the final guard against expired
                // or placeholder subscriptions and publishes the resulting error.
                submittedToManager = true
                vpnToggleController.startVpn(server) {
                    viewModelScope.launch {
                        if (request == connectionPreparationRequest) {
                            _connectionPreparation.value = false
                            connectionPreparationJob = null
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (request == connectionPreparationRequest) {
                    connectionManager.showError(context.getString(R.string.error_network))
                }
            } finally {
                if (!submittedToManager && request == connectionPreparationRequest) {
                    _connectionPreparation.value = false
                    connectionPreparationJob = null
                }
            }
        }
    }

    private fun cancelConnectionPreparation() {
        connectionPreparationRequest++
        connectionPreparationJob?.cancel()
        connectionPreparationJob = null
        _connectionPreparation.value = false
    }

    /** Home left the foreground — pause the periodic ping loop. */
    fun onPause() {
        screenActive.value = false
    }

    /** Re-sync subscription & servers when app returns to foreground (throttled to 5s). */
    fun onResume() {
        screenActive.value = true
        if (!initialized) return
        launchGuarded("Resume sync") resume@{
            if (prefsDataStore.getPendingPurchaseState() != null) {
                startPendingPurchaseRefreshIfNeeded()
                return@resume
            }

            val now = System.currentTimeMillis()
            if (now - lastSyncTime < RESUME_SYNC_THROTTLE_MS) return@resume
            lastSyncTime = now
            // If VPN is currently active, don't let panel overwrite the
            // live local usage counter — it lags behind and causes the UI
            // to jump backwards.
            val isActive = connectionState.value is ConnectionState.Connected ||
                connectionState.value is ConnectionState.Connecting ||
                _connectionPreparation.value
            authRepository.syncSubscription(overwriteUsage = !isActive)
            if (isActive) return@resume
            val servers = vpnRepository.refreshServers().getOrNull().orEmpty()
            vpnToggleController.ensureAutomaticServerSelected(servers)
        }
    }

    fun getVpnPermissionIntent(activity: Activity): Intent? {
        return VpnService.prepare(activity)
    }

    /**
     * Loads the list of available purchase plans for the current user.
     * Only meaningful when [authState] is [AuthState.Authenticated].
     * Safe to call multiple times — refreshes in background.
     */
    fun loadPurchasePlans() {
        if (subscriptionUsageBlocked.value || _purchasePlansLoading.value) return
        val request = beginPurchaseRefresh()
        launchGuarded("Purchase plans refresh") {
            refreshPurchasePlansAndLimits(request)
        }
    }

    fun requestSubscriptionSheet(onAllowed: () -> Unit) {
        if (subscriptionUsageBlocked.value) return
        if (authState.value is AuthState.Authenticated) {
            loadPurchasePlans()
            onAllowed()
            return
        }
        launchGuarded("Subscription sheet guard") {
            if (!authRepository.pingHwidOnly()) {
                onAllowed()
            }
        }
    }

    fun openPurchaseUrl(context: Context, paymentUrl: String?) {
        if (subscriptionUsageBlocked.value || paymentUrl.isNullOrBlank()) return
        launchGuarded("Open purchase URL") purchase@{
            if (authRepository.pingHwidOnly()) {
                clearPurchaseState()
                prefsDataStore.clearPendingPurchase()
                return@purchase
            }
            val baseline = authRepository.getAuthStateSnapshot() as? AuthState.Authenticated
                ?: run {
                    clearPurchaseState()
                    prefsDataStore.clearPendingPurchase()
                    return@purchase
                }
            paymentFeedbackSent = false
            _paymentSuccessVisible.value = false
            prefsDataStore.markPendingPurchaseStarted(
                baselinePlan = baseline.plan.name,
                baselineExpiresAt = baseline.planExpiresAt,
            )
            val opened = openExternalPayment(context, paymentUrl)
            if (opened) {
                startPendingPurchaseRefreshIfNeeded()
            } else {
                prefsDataStore.clearPendingPurchase()
            }
        }
    }

    private suspend fun refreshPurchasePlansAndLimits(request: Int) {
        val authenticated = authRepository.getAuthStateSnapshot() as? AuthState.Authenticated
        if (authenticated == null) {
            if (request == purchaseLoadRequest) clearPurchaseState()
            return
        }
        try {
            val freshPlans = purchaseRepository.getPlans()
                ?.takeIf(::hasPurchasablePlanShape)
            val usingFallbackPlanShape = freshPlans == null
            val plans = if (freshPlans != null) {
                cachePurchasePlanShape(authenticated.telegramId, freshPlans)
                _purchasePlansFromCache.value = false
                freshPlans
            } else {
                loadCachedPurchasePlanShape(authenticated.telegramId)
                    ?.also { _purchasePlansFromCache.value = true }
            }
            if (request != purchaseLoadRequest ||
                authRepository.getAuthStateSnapshot() != authenticated
            ) {
                if (request == purchaseLoadRequest) clearPurchaseState()
                return
            }
            _purchasePlans.value = plans
            if (authRepository.pingHwidOnly()) {
                if (request == purchaseLoadRequest) clearPurchaseState()
                return
            }
            val limits = if (usingFallbackPlanShape) null else loadCurrentLimitsNow()
            if (request != purchaseLoadRequest ||
                authRepository.getAuthStateSnapshot() != authenticated
            ) {
                if (request == purchaseLoadRequest) clearPurchaseState()
                return
            }
            _currentLimits.value = limits
        } finally {
            if (request == purchaseLoadRequest) {
                _purchasePlansLoaded.value = true
                _purchasePlansLoading.value = false
            }
        }
    }

    private fun beginPurchaseRefresh(): Int {
        val request = ++purchaseLoadRequest
        _purchasePlans.value = null
        _purchasePlansFromCache.value = false
        _purchasePlansLoaded.value = false
        _currentLimits.value = null
        _purchasePlansLoading.value = true
        return request
    }

    private fun clearPurchaseState() {
        purchaseLoadRequest++
        _purchasePlans.value = null
        _purchasePlansFromCache.value = false
        _purchasePlansLoaded.value = false
        _purchasePlansLoading.value = false
        _currentLimits.value = null
    }

    private fun hasPurchasablePlanShape(plans: PurchasePlansDto): Boolean {
        return plans.plans.orEmpty().any { plan -> plan.durations.orEmpty().any { it.days > 0 } }
    }

    private suspend fun cachePurchasePlanShape(telegramId: Long, plans: PurchasePlansDto) {
        runCatching {
            val shapeOnly = plans.copy(
                plans = plans.plans.orEmpty()
                    .filter { plan -> plan.durations.orEmpty().any { it.days > 0 } }
                    .map { plan ->
                        plan.copy(
                            description = null,
                            trafficLimit = 0,
                            deviceLimit = 0,
                            durations = plan.durations.orEmpty()
                                .filter { it.days > 0 }
                                .map { duration ->
                                    duration.copy(
                                        botStartParam = null,
                                        botPaymentUrl = null,
                                        prices = emptyList(),
                                        paymentMethods = emptyList(),
                                    )
                                },
                        )
                    },
            )
            prefsDataStore.setPurchasePlansCache(
                telegramId = telegramId,
                json = purchasePlansGson.toJson(shapeOnly),
            )
        }.onFailure { error ->
            SafeDiagnostics.warn(TAG, "Purchase plans shape cache failed: ${SafeDiagnostics.failureCategory(error)}")
        }
    }

    private suspend fun loadCachedPurchasePlanShape(telegramId: Long): PurchasePlansDto? {
        return runCatching {
            val json = prefsDataStore.getPurchasePlansCache(telegramId) ?: return null
            purchasePlansGson.fromJson(json, PurchasePlansDto::class.java)
                ?.takeIf(::hasPurchasablePlanShape)
        }.onFailure { error ->
            SafeDiagnostics.warn(TAG, "Purchase plans shape cache read failed: ${SafeDiagnostics.failureCategory(error)}")
        }.getOrNull()
    }

    private suspend fun loadCurrentLimitsNow(): CurrentPlanLimits? {
        return try {
            authRepository.getCurrentSubscriptionPlan()?.let { plan ->
                CurrentPlanLimits(
                    trafficLimitBytes = plan.trafficLimitBytes,
                    deviceLimit = plan.deviceLimit,
                    renewalUrl = plan.renewalUrl,
                )
            } ?: run {
                SafeDiagnostics.warn(TAG, "Current plan limits response did not contain current-plan data")
                null
            }
        } catch (error: Exception) {
            SafeDiagnostics.warn(TAG, "Current plan limits request failed: ${SafeDiagnostics.failureCategory(error)}")
            null
        }
    }

    private fun startPendingPurchaseRefreshIfNeeded() {
        if (pendingPurchaseRefreshJob?.isActive == true) return
        pendingPurchaseRefreshJob = launchGuarded("Pending purchase refresh") pendingRefresh@{
            val pending = prefsDataStore.getPendingPurchaseState() ?: return@pendingRefresh
            val maxDeadline = pending.startedAt + PURCHASE_PENDING_MAX_AGE_MS
            val now = System.currentTimeMillis()
            if (now > maxDeadline) {
                expirePendingPurchase()
                return@pendingRefresh
            }

            val activeDeadline = minOf(now + PURCHASE_REFRESH_ACTIVE_WINDOW_MS, maxDeadline)
            while (System.currentTimeMillis() <= activeDeadline) {
                refreshSubscriptionAfterPurchase()
                if (paymentLooksApplied(pending, authRepository.getAuthStateSnapshot())) {
                    completeSuccessfulPayment()
                    return@pendingRefresh
                }
                awaitPurchaseRefreshSignal(PURCHASE_REFRESH_INTERVAL_MS)
            }

            val totalDeadline = minOf(now + PURCHASE_REFRESH_TOTAL_WINDOW_MS, maxDeadline)
            while (System.currentTimeMillis() <= totalDeadline) {
                awaitPurchaseRefreshSignal(PURCHASE_REFRESH_SLOW_INTERVAL_MS)
                refreshSubscriptionAfterPurchase()
                if (paymentLooksApplied(pending, authRepository.getAuthStateSnapshot())) {
                    completeSuccessfulPayment()
                    return@pendingRefresh
                }
            }

            if (System.currentTimeMillis() > maxDeadline) {
                expirePendingPurchase()
            }
        }
    }

    private suspend fun awaitPurchaseRefreshSignal(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) {
            pendingPurchaseRefreshSignal.receive()
        }
    }

    private suspend fun completeSuccessfulPayment() {
        prefsDataStore.clearPendingPurchase()
        if (paymentFeedbackSent) return
        paymentFeedbackSent = true
        _paymentSuccessVisible.value = true
        paymentNotifications.showSuccessfulPayment()
    }

    fun dismissPaymentSuccess() {
        _paymentSuccessVisible.value = false
    }

    private suspend fun expirePendingPurchase() {
        prefsDataStore.clearPendingPurchase()
        prefsDataStore.clearSubscriptionSyncTimestamp()
    }

    private suspend fun refreshSubscriptionAfterPurchase() {
        val isConnected = connectionState.value is ConnectionState.Connected
        authRepository.syncSubscription(
            overwriteUsage = !isConnected,
            force = true,
        )
        vpnRepository.refreshServers()
        lastSyncTime = System.currentTimeMillis()
    }

    private fun paymentLooksApplied(pending: PendingPurchaseState, current: AuthState): Boolean {
        val authenticated = current as? AuthState.Authenticated ?: return false
        val currentIsPaid = authenticated.plan == UserPlan.PAID || authenticated.plan == UserPlan.ADMIN
        val baselineWasPaid = pending.baselinePlan == UserPlan.PAID.name ||
            pending.baselinePlan == UserPlan.ADMIN.name

        if (!baselineWasPaid && currentIsPaid) return true

        val baselineExpiresAt = pending.baselineExpiresAt
        val currentExpiresAt = authenticated.planExpiresAt
        return baselineWasPaid &&
            currentIsPaid &&
            baselineExpiresAt != null &&
            currentExpiresAt != null &&
            currentExpiresAt > baselineExpiresAt
    }

    private fun openExternalPayment(context: Context, paymentUrl: String): Boolean {
        val paymentUri = runCatching { Uri.parse(paymentUrl) }.getOrNull() ?: return false
        val bot = paymentUri.getQueryParameter("domain")
            ?: paymentUri.pathSegments.firstOrNull().orEmpty()
        val startParam = paymentUri.getQueryParameter("start").orEmpty()

        if (bot.isNotBlank() && startParam.isNotBlank()) {
            val telegramUri = Uri.Builder()
                .scheme("tg")
                .authority("resolve")
                .appendQueryParameter("domain", bot)
                .appendQueryParameter("start", startParam)
                .build()
            if (startActivitySafely(context, Intent(Intent.ACTION_VIEW, telegramUri))) {
                return true
            }
        }

        return startActivitySafely(context, Intent(Intent.ACTION_VIEW, paymentUri))
    }

    private fun startActivitySafely(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
