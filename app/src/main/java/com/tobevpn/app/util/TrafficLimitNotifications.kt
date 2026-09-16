package com.tobevpn.app.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tobevpn.app.MainActivity
import com.tobevpn.app.R
import com.tobevpn.app.data.local.PrefsDataStore
import com.tobevpn.app.data.repository.UsageRepository
import com.tobevpn.app.domain.model.UsageInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@Singleton
class TrafficLimitNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageRepository: UsageRepository,
    private val prefsDataStore: PrefsDataStore,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            usageRepository.observeUsage().collect { usage ->
                processUsage(usage)
            }
        }
    }

    private suspend fun processUsage(usage: UsageInfo) {
        val previous = prefsDataStore.getTrafficLimitAlertState()
        val evaluation = evaluateTrafficLimitAlert(usage, previous)
        if (evaluation.state != previous) {
            prefsDataStore.setTrafficLimitAlertState(evaluation.state)
        }
        if (previous.notifiedThresholdMask != 0L &&
            evaluation.state.notifiedThresholdMask == 0L
        ) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
        evaluation.thresholdToNotify?.let { threshold ->
            showNotification(usage, threshold)
        }
    }

    private fun showNotification(
        usage: UsageInfo,
        threshold: TrafficRemainingThreshold,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.traffic_limit_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val remaining = usage.bytesRemaining.coerceAtMost(usage.bytesLimit)
        val description = context.getString(
            R.string.traffic_limit_notification_description,
            formatGib(remaining),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.traffic_limit_notification_title))
            .setContentText(description)
            .setStyle(Notification.BigTextStyle().bigText(description))
            .setSmallIcon(R.drawable.ic_notification_tobevpn)
            .setColor(context.getColor(R.color.notification_icon_color))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
        SafeDiagnostics.info(
            TAG,
            "Traffic limit notification shown: remaining_threshold_percent=${threshold.percent}",
        )
    }

    private fun formatGib(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L).toDouble() / BYTES_PER_GIB
        return NumberFormat.getNumberInstance().apply {
            maximumFractionDigits = when {
                value < 1.0 -> 2
                value < 10.0 -> 1
                else -> 0
            }
            minimumFractionDigits = 0
        }.format(value)
    }

    private companion object {
        const val TAG = "TrafficLimitNotifications"
        const val CHANNEL_ID = "traffic_limit_alerts"
        const val NOTIFICATION_ID = 2003
        const val BYTES_PER_GIB = 1024.0 * 1024.0 * 1024.0
    }
}
