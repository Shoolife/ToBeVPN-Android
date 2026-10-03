package com.tobevpn.app.billing

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.PendingPurchasesParams
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.R
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides whether the app may link users to external payment (the Telegram
 * bot) — the Google Play Payments policy depends on the user's Play country.
 *
 * Direct-download builds always may. Google Play builds may only for Play
 * accounts in Russia, where Play's billing requirement does not apply
 * (Play Console Help, "Changes to Google Play's billing system for users in
 * Russia and Belarus"). The country comes from Google Play itself, never from
 * a user choice, and an unknown country counts as "not allowed".
 *
 * The Billing Library is used only to read the country; no purchases are made.
 */
@Singleton
class PlayBillingCountry @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _country = MutableStateFlow(prefs.getString(KEY_COUNTRY, null))

    /** ISO 3166-1 alpha-2 country of the Google Play account, null until known. */
    val country: StateFlow<String?> = _country.asStateFlow()

    private val _externalPurchasesAllowed = MutableStateFlow(
        !BuildConfig.PLAY_DISTRIBUTION || isAllowedCountry(_country.value),
    )
    val externalPurchasesAllowed: StateFlow<Boolean> = _externalPurchasesAllowed.asStateFlow()

    private val _status = MutableStateFlow(
        PlayBillingStatus(
            distribution = BuildConfig.PLAY_DISTRIBUTION,
            country = _country.value,
            externalPurchasesAllowed = _externalPurchasesAllowed.value,
            checkedAtMillis = prefs.getLong(KEY_CHECKED_AT, 0L).takeIf { it > 0L },
            lastResult = prefs.getString(KEY_LAST_RESULT, null),
            installer = installerPackage(context),
            checking = false,
        ),
    )

    /** Everything the hidden diagnostics card shows about the Play country check. */
    val status: StateFlow<PlayBillingStatus> = _status.asStateFlow()

    private val refreshing = AtomicBoolean(false)

    /** Re-reads the Play account country; cheap enough to call on every app start. */
    fun refresh() {
        if (!BuildConfig.PLAY_DISTRIBUTION) return
        if (!refreshing.compareAndSet(false, true)) return
        _status.value = _status.value.copy(checking = true)
        val client = BillingClient.newBuilder(context)
            .setListener { _, _ -> }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    SafeDiagnostics.trace(TAG, "Billing setup failed: ${result.responseCode}")
                    finish(client, "setup ${responseCodeName(result.responseCode)}")
                    return
                }
                client.getBillingConfigAsync(GetBillingConfigParams.newBuilder().build()) { configResult, config ->
                    if (configResult.responseCode == BillingClient.BillingResponseCode.OK && config != null) {
                        val country = config.countryCode.uppercase()
                        prefs.edit().putString(KEY_COUNTRY, country).apply()
                        _country.value = country
                        _externalPurchasesAllowed.value = isAllowedCountry(country)
                        SafeDiagnostics.trace(TAG, "Play country: $country")
                        finish(client, "OK")
                    } else {
                        SafeDiagnostics.trace(TAG, "Billing config unavailable: ${configResult.responseCode}")
                        finish(client, "config ${responseCodeName(configResult.responseCode)}")
                    }
                }
            }

            override fun onBillingServiceDisconnected() {
                finish(client, "disconnected")
            }
        })
    }

    private fun finish(client: BillingClient, result: String) {
        runCatching { client.endConnection() }
        if (!refreshing.get()) return
        val now = System.currentTimeMillis()
        prefs.edit()
            .putLong(KEY_CHECKED_AT, now)
            .putString(KEY_LAST_RESULT, result)
            .apply()
        _status.value = _status.value.copy(
            country = _country.value,
            externalPurchasesAllowed = _externalPurchasesAllowed.value,
            checkedAtMillis = now,
            lastResult = result,
            checking = false,
        )
        refreshing.set(false)
    }

    private companion object {
        const val TAG = "PlayBillingCountry"
        const val PREFS_NAME = "play_billing_country"
        const val KEY_COUNTRY = "country"
        const val KEY_CHECKED_AT = "checked_at"
        const val KEY_LAST_RESULT = "last_result"
        val EXTERNAL_PAYMENT_COUNTRIES = setOf("RU")

        fun isAllowedCountry(country: String?): Boolean =
            country != null && country.uppercase() in EXTERNAL_PAYMENT_COUNTRIES

        fun installerPackage(context: Context): String? = runCatching {
            val pm = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()

        fun responseCodeName(code: Int): String = when (code) {
            BillingClient.BillingResponseCode.OK -> "OK"
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED -> "SERVICE_DISCONNECTED"
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED -> "FEATURE_NOT_SUPPORTED"
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "SERVICE_UNAVAILABLE"
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> "BILLING_UNAVAILABLE"
            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> "DEVELOPER_ERROR"
            BillingClient.BillingResponseCode.ERROR -> "ERROR"
            BillingClient.BillingResponseCode.NETWORK_ERROR -> "NETWORK_ERROR"
            else -> "code $code"
        }
    }
}

/** Snapshot of the Play country check for the hidden diagnostics card. */
data class PlayBillingStatus(
    /** True for the Google Play build; direct builds never ask Google Play. */
    val distribution: Boolean,
    val country: String?,
    val externalPurchasesAllowed: Boolean,
    val checkedAtMillis: Long?,
    /** "OK", or the failed step with the Billing response code. */
    val lastResult: String?,
    /** Package that installed the app, e.g. com.android.vending for Google Play. */
    val installer: String?,
    val checking: Boolean,
)

/** Whether payment links to the Telegram bot may be shown; see [PlayBillingCountry]. */
val LocalExternalPurchasesAllowed = staticCompositionLocalOf { !BuildConfig.PLAY_DISTRIBUTION }

/** Google Play account country; see [PlayBillingCountry.country]. */
val LocalPlayBillingCountry = staticCompositionLocalOf<String?> { null }

/**
 * Shown instead of any payment hint when external payment is not allowed:
 * names the country Google Play reports, so users understand why.
 */
@Composable
fun paymentUnavailableMessage(): String {
    val country = LocalPlayBillingCountry.current
    val locale = LocalConfiguration.current.locales[0]
    val countryName = country
        ?.let { Locale("", it).getDisplayCountry(locale) }
        ?.takeIf { it.isNotBlank() && !it.equals(country, ignoreCase = true) }
    return if (countryName != null) {
        stringResource(R.string.payment_unavailable_country, countryName)
    } else {
        stringResource(R.string.payment_unavailable)
    }
}
