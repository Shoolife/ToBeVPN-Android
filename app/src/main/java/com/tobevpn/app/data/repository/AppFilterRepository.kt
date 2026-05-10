package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.AppFilterDao
import com.tobevpn.app.data.local.entity.AppFilterEntry
import com.tobevpn.app.domain.model.AppFilterMode
import com.tobevpn.app.domain.model.AppFilterState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppFilterRepository @Inject constructor(
    private val dao: AppFilterDao,
    private val prefs: PrefsDataStore,
) {
    fun observeMode(): Flow<AppFilterMode> = prefs.appFilterMode.map { parseMode(it) }

    fun observeSelectedPackages(): Flow<Set<String>> =
        dao.observePackages().map { it.toSet() }

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
        val selected = dao.getPackages().toSet()
        return AppFilterState(mode = mode, selectedPackages = selected)
    }

    suspend fun setMode(mode: AppFilterMode) {
        prefs.setAppFilterMode(mode.name)
    }

    suspend fun toggle(packageName: String) {
        val current = dao.getPackages().toSet()
        if (packageName in current) dao.delete(packageName)
        else dao.insert(AppFilterEntry(packageName))
    }

    suspend fun setSelected(packageNames: Collection<String>) {
        dao.clear()
        packageNames.forEach { dao.insert(AppFilterEntry(it)) }
    }

    suspend fun clearAll() {
        dao.clear()
    }

    private fun parseMode(raw: String?): AppFilterMode = when (raw) {
        AppFilterMode.WHITELIST.name -> AppFilterMode.WHITELIST
        AppFilterMode.BLACKLIST.name -> AppFilterMode.BLACKLIST
        else -> AppFilterMode.OFF
    }
}
