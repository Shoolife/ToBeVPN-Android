package com.tobevpn.app.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * FCM entry point. In the background Android shows the push itself; here only
 * the token rotation and pushes received while the app is open arrive. Nothing
 * here asks the bot for the feed: a mass send must not start a wave of
 * requests, the full text is loaded when the push is tapped.
 */
@AndroidEntryPoint
class AnnouncementMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var pushRegistrar: PushRegistrar

    @Inject
    lateinit var notificationsRepository: NotificationsRepository

    override fun onNewToken(token: String) {
        pushRegistrar.onNewToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.data[AnnouncementPushes.EXTRA_NOTIFICATION_ID]
            ?.takeIf { NotificationsRepository.isUuid(it) }
            ?: return
        notificationsRepository.onPushReceived()
        val notification = message.notification
        AnnouncementPushes.show(
            context = applicationContext,
            id = id,
            title = notification?.title,
            body = notification?.body,
        )
    }
}
