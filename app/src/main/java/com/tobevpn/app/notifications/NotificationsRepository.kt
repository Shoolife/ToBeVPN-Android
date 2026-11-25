package com.tobevpn.app.notifications

import android.content.Context
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.NotificationDetailDto
import com.tobevpn.app.data.remote.dto.NotificationItemDto
import com.tobevpn.app.data.repository.AuthRepository
import com.tobevpn.app.domain.model.AuthState
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.Response
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What a failed announcements request tells the screen. */
sealed interface NotificationsError {
    /** No connection, 5xx or an unreadable answer: offer Retry. */
    data object Network : NotificationsError

    /** 429: the server asked to wait (seconds from Retry-After, if given). */
    data class RateLimited(val retryAfterSeconds: Long?) : NotificationsError

    /** 403: this session may not see the feed. */
    data object Forbidden : NotificationsError

    /** 404 on an announcement: expired or meant for another recipient. */
    data object NotFound : NotificationsError
}

data class NotificationsFeedState(
    /** The last feed the server returned; null until the first one arrives. */
    val items: List<NotificationItemDto>? = null,
    val loading: Boolean = false,
    val error: NotificationsError? = null,
)

sealed interface NotificationDetailResult {
    data class Loaded(val detail: NotificationDetailDto) : NotificationDetailResult
    data class Failed(val error: NotificationsError) : NotificationDetailResult
}

/**
 * Announcements from the bot (Рассылка → TobeVPN): the feed, one
 * announcement, its read mark, and the dot on the Home bell.
 *
 * As few feed requests as possible (agreed with the bot side): one when the
 * app window first opens in a process, then at most one a day when the window
 * comes back, and on the user's Refresh. Entering the feed shows the loaded
 * one; it asks the server again only when there is none yet or a push said
 * something new arrived. Nothing runs in the background — pushes are the only
 * automatic channel. Concurrent loads share one request. The server picks the
 * recipient from the device session, so after a sign-in, sign-out or account
 * change the old feed is dropped.
 */
@Singleton
class NotificationsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val botApi: BotApi,
    authRepository: AuthRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val loadMutex = Mutex()
    private var inFlight: Deferred<Unit>? = null
    private var openedThisProcess = false

    /** A push arrived after the last feed: the feed lacks that announcement. */
    @Volatile
    private var staleByPush = false

    private val _feed = MutableStateFlow(NotificationsFeedState())
    val feed: StateFlow<NotificationsFeedState> = _feed.asStateFlow()

    private val _hasUnread = MutableStateFlow(prefs.getBoolean(KEY_UNREAD, false))

    /** The dot on the bell: an unread item in the last feed, or a push since. */
    val hasUnread: StateFlow<Boolean> = _hasUnread.asStateFlow()

    init {
        // A different recipient: drop what belonged to the previous one.
        scope.launch {
            authRepository.observeAuthState()
                .map { (it as? AuthState.Authenticated)?.telegramId }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    _feed.value = NotificationsFeedState()
                    setUnread(false)
                    openedThisProcess = false
                    loadFeed()
                }
        }
    }

    /**
     * The app window is shown: the first time in this process loads the feed
     * for the bell, later ones only when the last feed is a day old.
     */
    fun onAppOpened() {
        val due = !openedThisProcess ||
            System.currentTimeMillis() - prefs.getLong(KEY_LOADED_AT, 0L) >= DAILY_MS
        if (!due) return
        openedThisProcess = true
        scope.launch { loadFeed() }
    }

    /** The user entered the feed: reuse the loaded one unless it is missing or a push made it stale. */
    fun onFeedEntered() {
        if (_feed.value.items != null && !staleByPush) return
        scope.launch { loadFeed() }
    }

    /** A push arrived: the bell shows the dot until the feed says otherwise. */
    fun onPushReceived() {
        staleByPush = true
        setUnread(true)
    }

    /** One feed request; callers arriving while it runs wait for the same one. */
    suspend fun loadFeed() {
        val job = loadMutex.withLock {
            inFlight?.takeIf { it.isActive } ?: scope.async { fetchFeed() }.also { inFlight = it }
        }
        job.await()
    }

    private suspend fun fetchFeed() {
        _feed.update { it.copy(loading = true, error = null) }
        val result = call { botApi.getNotifications() }
        result.fold(
            onSuccess = { items ->
                staleByPush = false
                prefs.edit().putLong(KEY_LOADED_AT, System.currentTimeMillis()).apply()
                _feed.value = NotificationsFeedState(items = items.orEmpty())
                setUnread(items.orEmpty().any { it.readAt == null })
            },
            onFailure = { error ->
                _feed.update { it.copy(loading = false, error = error.asNotificationsError()) }
            },
        )
    }

    suspend fun loadDetail(id: String): NotificationDetailResult {
        if (!isUuid(id)) return NotificationDetailResult.Failed(NotificationsError.NotFound)
        return call { botApi.getNotification(id) }.fold(
            onSuccess = { detail ->
                if (detail == null) {
                    NotificationDetailResult.Failed(NotificationsError.Network)
                } else {
                    NotificationDetailResult.Loaded(detail)
                }
            },
            onFailure = { NotificationDetailResult.Failed(it.asNotificationsError()) },
        )
    }

    /**
     * The full text is on screen. Returns the server's read time (also on a
     * repeat: it keeps the first one) and updates the feed entry with it.
     */
    suspend fun markRead(id: String): String? {
        if (!isUuid(id)) return null
        val readAt = call { botApi.markNotificationRead(id) }.getOrNull()?.readAt ?: return null
        _feed.update { state ->
            state.copy(items = state.items?.map { if (it.id == id) it.copy(readAt = readAt) else it })
        }
        _feed.value.items?.let { items -> setUnread(items.any { it.readAt == null }) }
        return readAt
    }

    private fun setUnread(value: Boolean) {
        _hasUnread.value = value
        prefs.edit().putBoolean(KEY_UNREAD, value).apply()
    }

    private suspend fun <T> call(request: suspend () -> Response<com.tobevpn.app.data.remote.dto.ApiResponse<T>>): Result<T?> {
        return try {
            val response = request()
            if (response.isSuccessful) {
                Result.success(response.body()?.data)
            } else {
                Result.failure(HttpStatusException(response.code(), retryAfterSeconds(response)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SafeDiagnostics.warn(TAG, "Announcements request failed: ${SafeDiagnostics.failureCategory(e)}")
            Result.failure(e)
        } catch (e: RuntimeException) {
            // An unreadable body (Gson) is a broken answer, not a crash.
            SafeDiagnostics.warn(TAG, "Announcements answer unreadable: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    private fun retryAfterSeconds(response: Response<*>): Long? =
        response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.coerceAtLeast(0)

    private class HttpStatusException(val code: Int, val retryAfterSeconds: Long?) :
        RuntimeException("HTTP $code")

    private fun Throwable.asNotificationsError(): NotificationsError = when (this) {
        is HttpStatusException -> when (code) {
            404 -> NotificationsError.NotFound
            403 -> NotificationsError.Forbidden
            429 -> NotificationsError.RateLimited(retryAfterSeconds)
            else -> NotificationsError.Network
        }
        else -> NotificationsError.Network
    }

    companion object {
        private const val TAG = "Notifications"
        private const val PREFS = "announcements"
        private const val KEY_UNREAD = "has_unread"
        private const val KEY_LOADED_AT = "feed_loaded_at"
        private const val DAILY_MS = 24L * 60 * 60 * 1000

        fun isUuid(value: String?): Boolean =
            value != null && value.length == 36 && runCatching { UUID.fromString(value) }.isSuccess
    }
}
