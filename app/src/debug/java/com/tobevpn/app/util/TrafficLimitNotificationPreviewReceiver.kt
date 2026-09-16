package com.tobevpn.app.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.tobevpn.app.MainActivity
import com.tobevpn.app.R

class TrafficLimitNotificationPreviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_ALL, false)) {
            showAll(context)
        } else {
            showTrafficPreview(
                context = context,
                percent = intent.getIntExtra(EXTRA_PERCENT, 20),
                notificationId = PRODUCTION_TRAFFIC_NOTIFICATION_ID,
            )
        }
    }

    private fun showAll(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        PREVIEW_NOTIFICATION_IDS.forEach(manager::cancel)
        manager.cancel(PRODUCTION_TRAFFIC_NOTIFICATION_ID)
        manager.cancel(PRODUCTION_PAYMENT_NOTIFICATION_ID)

        manager.createNotificationChannel(
            NotificationChannel(
                VPN_CHANNEL_ID,
                context.getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val vpnStates = listOf(
            context.getString(R.string.state_disconnected),
            context.getString(R.string.vpn_notification_connecting_to, "Нидерланды"),
            context.getString(R.string.vpn_notification_connected_to, "Нидерланды"),
            context.getString(R.string.vpn_waiting_for_network_resume),
        )
        vpnStates.forEachIndexed { index, text ->
            manager.notify(
                VPN_PREVIEW_ID_START + index,
                createVpnPreviewNotification(context, text, VPN_PREVIEW_ID_START + index),
            )
        }

        manager.createNotificationChannel(
            NotificationChannel(
                PAYMENT_CHANNEL_ID,
                context.getString(R.string.payment_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        manager.notify(
            PRODUCTION_PAYMENT_NOTIFICATION_ID,
            createStandardNotification(
                context = context,
                channelId = PAYMENT_CHANNEL_ID,
                title = context.getString(R.string.payment_success_title),
                text = context.getString(R.string.payment_success_description),
                notificationId = PRODUCTION_PAYMENT_NOTIFICATION_ID,
            ),
        )
        showTrafficPreview(context, percent = 20, notificationId = TRAFFIC_PREVIEW_ID_20)
        showTrafficPreview(context, percent = 10, notificationId = TRAFFIC_PREVIEW_ID_10)
        showTrafficPreview(context, percent = 5, notificationId = TRAFFIC_PREVIEW_ID_5)
    }

    private fun showTrafficPreview(context: Context, percent: Int, notificationId: Int) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                TRAFFIC_CHANNEL_ID,
                context.getString(R.string.traffic_limit_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val description = context.getString(
            R.string.traffic_limit_notification_description,
            percent.toString(),
        )
        manager.notify(
            notificationId,
            createStandardNotification(
                context = context,
                channelId = TRAFFIC_CHANNEL_ID,
                title = context.getString(R.string.traffic_limit_notification_title),
                text = description,
                notificationId = notificationId,
                bigText = true,
            ),
        )
    }

    private fun createStandardNotification(
        context: Context,
        channelId: String,
        title: String,
        text: String,
        notificationId: Int,
        bigText: Boolean = false,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_tobevpn)
            .setColor(context.getColor(R.color.notification_icon_color))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .apply {
                if (bigText) setStyle(Notification.BigTextStyle().bigText(text))
            }
            .build()
    }

    private fun createVpnPreviewNotification(
        context: Context,
        text: String,
        requestCode: Int,
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, VPN_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification_tobevpn)
            .setColor(context.getColor(R.color.notification_icon_color))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val EXTRA_PERCENT = "percent"
        const val EXTRA_SHOW_ALL = "show_all"
        const val VPN_CHANNEL_ID = "tobevpn_channel"
        const val PAYMENT_CHANNEL_ID = "payment_updates"
        const val TRAFFIC_CHANNEL_ID = "traffic_limit_alerts"
        const val VPN_PREVIEW_ID_START = 2910
        const val TRAFFIC_PREVIEW_ID_20 = 2920
        const val TRAFFIC_PREVIEW_ID_10 = 2921
        const val TRAFFIC_PREVIEW_ID_5 = 2922
        const val PRODUCTION_PAYMENT_NOTIFICATION_ID = 2002
        const val PRODUCTION_TRAFFIC_NOTIFICATION_ID = 2003
        val PREVIEW_NOTIFICATION_IDS = (VPN_PREVIEW_ID_START..TRAFFIC_PREVIEW_ID_5)
    }
}
