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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Pause VPN at home": a pop-up when the phone's VPN pauses at home and when
 * it comes back on leaving. The VPN's own notification changes too, but
 * quietly; these say it the moment it happens. One at a time (the newer
 * replaces the older), gone by itself after a while.
 */
@Singleton
class HomePauseNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun showPaused() = show(
        context.getString(R.string.home_pause_notice_paused_title),
        context.getString(R.string.home_pause_notice_paused_text),
    )

    /** Connected by hand on the router's network: it stays on there. */
    fun showStayingOn() = show(
        context.getString(R.string.home_pause_notice_staying_title),
        context.getString(R.string.pause_at_home_connect_notice),
    )

    fun showResumed(serverName: String) = show(
        context.getString(R.string.home_pause_notice_resumed_title),
        if (serverName.isBlank()) {
            context.getString(R.string.home_pause_notice_resumed_text_plain)
        } else {
            context.getString(R.string.home_pause_notice_resumed_text, serverName)
        },
    )

    private fun show(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        // High importance: it pops up over the screen (heads-up).
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.home_pause_notice_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { setSound(null, null) },
        )
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_notification_tobevpn)
            .setColor(context.getColor(R.color.notification_icon_color))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private companion object {
        const val CHANNEL_ID = "home_pause"
        const val NOTIFICATION_ID = 2003
        const val TIMEOUT_MS = 60_000L
    }
}
