package com.tobevpn.app.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object LocaleManager {

    const val LANG_EN = "en"
    const val LANG_RU = "ru"

    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    fun current(): String {
        // Prefer the explicit per-app override; fall back to whatever locale
        // is actually driving resource resolution. Without the fallback the
        // settings toggle stays on English when the device locale is Russian
        // and the user has never opened the in-app language picker.
        val override = AppCompatDelegate.getApplicationLocales()
        val tag = if (!override.isEmpty) {
            override[0]?.language
        } else {
            Locale.getDefault().language
        }
        return if (tag == LANG_RU) LANG_RU else LANG_EN
    }

    fun restartApp(context: Context) {
        val activity = context.findActivity()
        val launchIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            } ?: return

        if (activity != null) {
            activity.startActivity(launchIntent)
            activity.finishAffinity()
        } else {
            context.startActivity(launchIntent)
        }
        Runtime.getRuntime().exit(0)
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
