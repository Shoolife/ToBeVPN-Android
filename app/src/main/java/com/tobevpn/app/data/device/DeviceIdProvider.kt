package com.tobevpn.app.data.device

import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.local.dao.SessionDao
import kotlinx.coroutines.flow.firstOrNull
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the device ID used by the app auth backend.
 *
 * Existing linked installs keep their current persisted session/device ID to
 * avoid forcing a re-link during migration. Fresh installs derive the ID from
 * Android HWID instead of a random install UUID, so clearing app data or
 * reinstalling the APK cannot mint another free-trial device on the same
 * physical phone.
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
    private val sessionDao: SessionDao,
    private val fingerprintProvider: DeviceFingerprintProvider,
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

        val hwidDeviceId = stableDeviceIdFromHwid()
        if (hwidDeviceId != null) {
            val stored = prefsDataStore.deviceId.firstOrNull()
            if (stored != hwidDeviceId) {
                prefsDataStore.setDeviceId(hwidDeviceId)
            }
            return hwidDeviceId
        }

        val stored = prefsDataStore.deviceId.firstOrNull()?.takeIf { it.isNotBlank() }
        if (stored != null) {
            return stored
        }

        val installId = UUID.randomUUID().toString()
        prefsDataStore.setDeviceId(installId)
        return installId
    }

    private fun stableDeviceIdFromHwid(): String? {
        val hwid = fingerprintProvider.get().hwid
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotBlank() && it != LEGACY_BROKEN_ANDROID_ID }
            ?: return null

        return UUID.nameUUIDFromBytes(
            "$DEVICE_ID_NAMESPACE:$hwid".toByteArray(StandardCharsets.UTF_8),
        ).toString()
    }

    private companion object {
        const val DEVICE_ID_NAMESPACE = "tobevpn:android:device-id:v1"
        const val LEGACY_BROKEN_ANDROID_ID = "9774d56d682e549c"
    }
}
