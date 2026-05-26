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
 * HWID-derived ID is always preferred when available — this ensures the same
 * physical device always produces the same device_id regardless of app-data
 * resets, reinstalls, or upgrades from older versions that used random UUIDs.
 *
 * The only exception is an already-linked (authenticated) session: changing
 * its device_id would break the Telegram link and force a re-pair. In that
 * case we keep the session's existing ID.
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    private val prefsDataStore: PrefsDataStore,
    private val sessionDao: SessionDao,
    private val fingerprintProvider: DeviceFingerprintProvider,
) {
    suspend fun getOrCreate(): String {
        val session = sessionDao.getSession()
        val sessionDeviceId = session?.deviceId?.takeIf { it.isNotBlank() }
        val hwidDeviceId = stableDeviceIdFromHwid()

        val isLinked = session?.authState == "AUTHENTICATED" && session.telegramId != null

        if (sessionDeviceId != null && (isLinked || hwidDeviceId == null || sessionDeviceId == hwidDeviceId)) {
            if (prefsDataStore.deviceId.firstOrNull() != sessionDeviceId) {
                prefsDataStore.setDeviceId(sessionDeviceId)
            }
            return sessionDeviceId
        }

        if (hwidDeviceId != null) {
            if (prefsDataStore.deviceId.firstOrNull() != hwidDeviceId) {
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
