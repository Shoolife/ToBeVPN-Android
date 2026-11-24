package com.tobevpn.app.notifications

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

/** The system side of announcement pushes: channel, local banner, tap intent. */
object AnnouncementPushes {
    /** Exact ID the server sends in `android.notification.channel_id`. */
    const val CHANNEL_ID = "tobevpn_announcements"

    /** Data key FCM puts into the launch intent's extras on a tap. */
    const val EXTRA_NOTIFICATION_ID = "notification_id"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.announcements_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.announcements_channel_description)
            },
        )
    }

    /** The announcement ID a launch intent carries, if it is a valid UUID. */
    fun notificationIdFrom(intent: Intent?): String? =
        intent?.getStringExtra(EXTRA_NOTIFICATION_ID)
            ?.trim()
            ?.takeIf { NotificationsRepository.isUuid(it) }

    /**
     * A push that arrived while the app was open: Android does not show those
     * itself. Same ID as the tag, so a repeat replaces the banner.
     */
    fun show(context: Context, id: String, title: String?, body: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        createChannel(context)
        val tap = PendingIntent.getActivity(
            context,
            id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_NOTIFICATION_ID, id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification_tobevpn)
            .setColor(context.getColor(R.color.notification_icon_color))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(id, NOTIFICATION_TAG_ID, notification)
    }

    private const val NOTIFICATION_TAG_ID = 0
}
