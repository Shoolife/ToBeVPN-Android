package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.AppFilterDao
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AppFilterState
import com.tobevpn.app.util.SafeDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppFilterRepository @Inject constructor(
    private val dao: AppFilterDao,
    private val prefs: PrefsDataStore,
) {
    private val writeMutex = Mutex()
    // Writes run on this singleton-scoped coroutine, *not* the calling
    // ViewModel's viewModelScope. The DataStore value is the source of truth;
    // Room is kept only as a legacy migration source and best-effort mirror.
    private val writeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        writeScope.launch {
            try {
                writeMutex.withLock { getSelectedPackagesLocked() }
            } catch (error: Exception) {
                SafeDiagnostics.warn(TAG, "App filter migration failed: ${SafeDiagnostics.failureCategory(error)}")
            }
        }
    }

    private companion object {
        const val TAG = "AppFilterRepository"
    }

    fun observeMode(): Flow<AppFilterMode> = prefs.appFilterMode.map { parseMode(it) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSelectedPackages(): Flow<Set<String>> =
        prefs.appFilterPackages
            .flatMapLatest { stored ->
                if (stored != null) {
                    flowOf(stored)
                } else {
                    dao.observePackages().map { it.toSet() }
                }
            }
            .distinctUntilChanged()

    /**
     * Combined snapshot of the current policy. Used by the ViewModel for the
     * settings summary line and by [getSnapshot] for the connect path.
     */
    fun observeState(): Flow<AppFilterState> =
        observeMode().combine(observeSelectedPackages()) { mode, set ->
            AppFilterState(mode = mode, selectedPackages = set)
        }

    /**
     * Synchronous snapshot for the VPN connect path. We read both stores
     * once and never block the connect coroutine on a Flow subscription.
     */
    suspend fun getSnapshot(): AppFilterState {
        val mode = parseMode(prefs.getAppFilterMode())
        val selected = writeMutex.withLock { getSelectedPackagesLocked() }
        return AppFilterState(mode = mode, selectedPackages = selected)
    }

    fun setMode(mode: AppFilterMode) {
        writeScope.launch { prefs.setAppFilterMode(mode.name) }
    }

    fun toggle(packageName: String) {
        writeScope.launch {
            writeMutex.withLock {
                val current = getSelectedPackagesLocked()
                val updated = if (packageName in current) current - packageName else current + packageName
                persistSelectedPackagesLocked(updated)
            }
        }
    }

    fun setSelected(packageNames: Collection<String>) {
        writeScope.launch {
            writeMutex.withLock {
                persistSelectedPackagesLocked(packageNames)
            }
        }
    }

    fun clearAll() {
        writeScope.launch {
            writeMutex.withLock {
                persistSelectedPackagesLocked(emptySet())
            }
        }
    }

    private suspend fun getSelectedPackagesLocked(): Set<String> {
        val stored = prefs.getAppFilterPackages()
        if (stored != null) return stored

        val legacy = normalizePackages(dao.getPackages())
        prefs.setAppFilterPackages(legacy)
        return legacy
    }

    private suspend fun persistSelectedPackagesLocked(packageNames: Collection<String>) {
        val normalized = normalizePackages(packageNames)
        prefs.setAppFilterPackages(normalized)
        runCatching { dao.replaceAll(normalized) }
    }

    private fun normalizePackages(packageNames: Collection<String>): Set<String> =
        packageNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    private fun parseMode(raw: String?): AppFilterMode = when (raw) {
        AppFilterMode.WHITELIST.name -> AppFilterMode.WHITELIST
        AppFilterMode.BLACKLIST.name -> AppFilterMode.BLACKLIST
        else -> AppFilterMode.OFF
    }
}
