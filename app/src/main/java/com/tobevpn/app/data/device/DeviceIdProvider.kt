package com.tobevpn.app.data.device

import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import kotlinx.coroutines.flow.firstOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a stable per-install device ID used by the app auth backend.
 *
 * Existing installs keep their current persisted session/device ID to avoid
 * creating a duplicate backend device record during migration.
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
    private val sessionDao: SessionDao,
) {
    suspend fun getOrCreate(): String {
        val sessionDeviceId = sessionDao.getSession()?.deviceId?.takeIf { it.isNotBlank() }
        if (sessionDeviceId != null) {
            val stored = prefsDataStore.deviceId.firstOrNull()
            if (stored != sessionDeviceId) {
                prefsDataStore.setDeviceId(sessionDeviceId)
            }
            return sessionDeviceId
        }

        val stored = prefsDataStore.deviceId.firstOrNull()?.takeIf { it.isNotBlank() }
        if (stored != null) {
            return stored
        }

        val installId = UUID.randomUUID().toString()
        prefsDataStore.setDeviceId(installId)
        return installId
    }
}
