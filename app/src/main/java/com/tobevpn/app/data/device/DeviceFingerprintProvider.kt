package com.tobevpn.app.data.device

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.tobevpn.app.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceFingerprint(
    val hwid: String,        // Settings.Secure.ANDROID_ID
    val platform: String,    // "Android"
    val osVersion: String,   // Build.VERSION.RELEASE — "16"
    val model: String,       // Build.MANUFACTURER + " " + Build.MODEL
    val userAgent: String,   // "ToBeVPN/<versionName>/android/<versionCode>"
)

@Singleton
class DeviceFingerprintProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun get(): DeviceFingerprint = DeviceFingerprint(
        hwid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.trim()
            .orEmpty(),
        platform = "Android",
        osVersion = Build.VERSION.RELEASE.orEmpty(),
        model = composeModel(),
        userAgent = "ToBeVPN/${BuildConfig.VERSION_NAME}/android/${BuildConfig.VERSION_CODE}",
    )

    private fun composeModel(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            model.isEmpty() && manufacturer.isEmpty() -> "Android"
            manufacturer.isEmpty() -> model
            model.isEmpty() -> manufacturer
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
    }
}
