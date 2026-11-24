package com.tobevpn.app.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.tobevpn.app.BuildConfig
import com.tobevpn.app.data.remote.BootstrapManager
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.PushRegistrationRequestDto
import com.tobevpn.app.util.SafeDiagnostics
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.random.Random

/**
 * Announcement pushes: Firebase set up from BuildConfig, and the FCM token
 * registered with the bot (`PUT /api/device/push-registration`).
 *
 * The token is sent once per device session: the pair already sent is
 * remembered, a refresh keeps it, a new bootstrap forgets it (the server's
 * new session has no registration yet). Without a Firebase config or Google
 * Play services there are simply no pushes; the feed works anyway.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val botApi: BotApi,
    bootstrapManager: BootstrapManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    init {
        // A new session has no registration on the server: send the token again.
        scope.launch {
            bootstrapManager.newSessions.collect {
                prefs.edit().remove(KEY_SENT_TOKEN).apply()
                registerIfNeeded()
            }
        }
    }

    /** Firebase is configured for this build and set up in this process. */
    val available: Boolean by lazy { initFirebase() }

    private fun initFirebase(): Boolean {
        if (BuildConfig.FIREBASE_APP_ID.isBlank() ||
            BuildConfig.FIREBASE_API_KEY.isBlank() ||
            BuildConfig.FIREBASE_PROJECT_ID.isBlank()
        ) {
            return false
        }
        return runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(
                    context,
                    FirebaseOptions.Builder()
                        .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                        .setApiKey(BuildConfig.FIREBASE_API_KEY)
                        .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                        .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                        .build(),
                )
            }
            true
        }.getOrElse {
            SafeDiagnostics.warn(TAG, "Firebase setup failed: ${it.javaClass.simpleName}")
            false
        }
    }

    /** The app window opened, or the session was replaced: register if needed. */
    fun registerIfNeeded() {
        if (!available) return
        scope.launch {
            val token = currentToken() ?: return@launch
            register(token)
        }
    }

    /** FCM rotated the token. */
    fun onNewToken(token: String) {
        scope.launch { register(token) }
    }

    private suspend fun register(token: String) = mutex.withLock {
        if (token.isBlank() || prefs.getString(KEY_SENT_TOKEN, null) == token) return@withLock
        for (attempt in 0 until MAX_ATTEMPTS) {
            val outcome = try {
                val response = botApi.registerPush(PushRegistrationRequestDto(token))
                when {
                    response.isSuccessful -> {
                        prefs.edit().putString(KEY_SENT_TOKEN, token).apply()
                        SafeDiagnostics.info(TAG, "Push registration saved")
                        return@withLock
                    }
                    // 409: a concurrent change on the server; retry shortly.
                    response.code() == 409 -> 2_000L
                    response.code() == 429 || response.code() >= 500 ->
                        response.headers()["Retry-After"]?.trim()?.toLongOrNull()
                            ?.coerceIn(1, 300)?.times(1000)
                            ?: backoff(attempt)
                    else -> {
                        // 403 (not an Android session), 422 (token): no retry.
                        SafeDiagnostics.warn(TAG, "Push registration refused: HTTP ${response.code()}")
                        return@withLock
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                backoff(attempt)
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(outcome)
        }
        // Next window open tries again.
        SafeDiagnostics.warn(TAG, "Push registration postponed")
    }

    private fun backoff(attempt: Int): Long =
        (5_000L shl attempt) + Random.nextLong(0, 3_000)

    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    val token = if (task.isSuccessful) task.result else null
                    if (token == null) {
                        SafeDiagnostics.warn(
                            TAG,
                            "FCM token unavailable: ${task.exception?.javaClass?.simpleName}",
                        )
                    }
                    if (cont.isActive) cont.resume(token)
                }
        }.onFailure {
            SafeDiagnostics.warn(TAG, "FCM unavailable: ${it.javaClass.simpleName}")
            if (cont.isActive) cont.resume(null)
        }
    }

    private companion object {
        const val TAG = "PushRegistrar"
        const val PREFS = "push_registration"
        const val KEY_SENT_TOKEN = "sent_token"
        const val MAX_ATTEMPTS = 3
    }
}
