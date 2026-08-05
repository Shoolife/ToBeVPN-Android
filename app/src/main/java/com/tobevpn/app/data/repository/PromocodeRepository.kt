package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.dao.PendingPromocodeActivationDao
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.PendingPromocodeActivationEntity
import com.tobevpn.app.data.remote.BootstrapManager
import com.tobevpn.app.data.remote.BotApi
import com.tobevpn.app.data.remote.dto.ApiResponse
import com.tobevpn.app.data.remote.dto.PromocodeActivateRequestDto
import com.tobevpn.app.data.remote.dto.PromocodeActivationResultDto
import com.tobevpn.app.data.remote.dto.PromocodeHistoryDto
import com.tobevpn.app.util.SafeDiagnostics
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PromocodeRepository @Inject constructor(
    private val botApi: BotApi,
    private val bootstrapManager: BootstrapManager,
    private val sessionDao: SessionDao,
    private val pendingActivationDao: PendingPromocodeActivationDao,
) {
    private val activationMutex = Mutex()

    suspend fun getHistory(limit: Int, offset: Int): PromocodeHistoryDto {
        val response = executeWithSessionRecovery {
            botApi.getAppliedPromocodes(limit = limit, offset = offset)
        }
        return response.data
            ?.takeIf { response.success }
            ?: throw PromocodeResponseException()
    }

    suspend fun activate(code: String): PromocodeActivationResultDto = activationMutex.withLock {
        val normalizedCode = code.trim().uppercase(Locale.ROOT)
        require(normalizedCode.isNotBlank()) { "Promocode must not be blank" }

        // Resolve the authenticated owner before persisting an attempt. The
        // owner is part of the backend idempotency contract, so a UUID created
        // for one Telegram account must never be reused for another one.
        bootstrapManager.ensureBootstrapped()
        val session = sessionDao.getSession()
        val telegramId = session
            ?.takeIf { it.authState == "AUTHENTICATED" }
            ?.telegramId
            ?: throw PromocodeAuthenticationException()

        val storedAttempt = pendingActivationDao.get(telegramId, normalizedCode)
        val requestId = storedAttempt
            ?.requestId
            ?.let(::canonicalUuid4OrNull)
            ?: UUID.randomUUID().toString()
        val request = PromocodeActivateRequestDto(
            code = normalizedCode,
            requestId = requestId,
        )

        // Persist before touching the network. If the process dies after the
        // server applies the reward but before Retrofit returns, the next
        // attempt for this code will replay the same request_id and receive the
        // already-applied result instead of applying the reward twice.
        if (storedAttempt?.requestId != requestId) {
            pendingActivationDao.upsert(
                PendingPromocodeActivationEntity(
                    telegramId = telegramId,
                    code = normalizedCode,
                    requestId = requestId,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        try {
            // The immutable request object is deliberately created outside the
            // recovery lambda: a 401/403 bootstrap retry must use the same UUID.
            val response = executeWithSessionRecovery {
                botApi.activatePromocode(request)
            }
            val result = response.data
                ?.takeIf { response.success }
                ?: throw PromocodeResponseException()
            if (result.requestId != null && result.requestId != requestId) {
                throw PromocodeResponseException()
            }
            clearPendingAttempt(
                telegramId = telegramId,
                code = normalizedCode,
                requestId = requestId,
            )
            result
        } catch (error: Exception) {
            // Timeouts, I/O failures and 5xx responses have an ambiguous
            // outcome: the backend may already have reserved/applied the
            // reward. Keep the UUID for a safe replay. A definitive 4xx (apart
            // from Request Timeout) did not produce a usable activation and
            // can be discarded; notably this heals a request-id conflict by
            // generating a fresh UUID on the user's next explicit attempt.
            if (shouldDiscardPendingPromocodeAttempt((error as? HttpException)?.code())) {
                clearPendingAttempt(
                    telegramId = telegramId,
                    code = normalizedCode,
                    requestId = requestId,
                )
            }
            throw error
        }
    }

    /**
     * Cleanup is best-effort after a response. A local I/O failure must not
     * turn an already successful server activation into a visible failure;
     * leaving the row behind is safe because a later replay remains
     * idempotent. Coroutine cancellation is still propagated normally.
     */
    private suspend fun clearPendingAttempt(
        telegramId: Long,
        code: String,
        requestId: String,
    ) {
        try {
            pendingActivationDao.deleteIfMatches(
                telegramId = telegramId,
                code = code,
                requestId = requestId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeDiagnostics.warn(
                TAG,
                "Failed to clear a completed promocode activation attempt: " +
                    SafeDiagnostics.failureCategory(error),
            )
        }
    }

    private suspend fun <T> executeWithSessionRecovery(
        request: suspend () -> ApiResponse<T>,
    ): ApiResponse<T> {
        bootstrapManager.ensureBootstrapped()
        return try {
            request()
        } catch (error: Exception) {
            if (error !is HttpException || error.code() !in setOf(401, 403)) {
                throw error
            }
            runCatching { bootstrapManager.bootstrap() }.getOrElse { throw error }
            request()
        }
    }

    private companion object {
        const val TAG = "PromocodeRepository"
    }
}

internal class PromocodeResponseException : IllegalStateException()

internal class PromocodeAuthenticationException : IllegalStateException()

internal fun canonicalUuid4OrNull(raw: String): String? {
    val parsed = runCatching { UUID.fromString(raw) }.getOrNull() ?: return null
    val canonical = parsed.toString()
    return canonical.takeIf {
        parsed.version() == 4 && canonical.equals(raw, ignoreCase = true)
    }
}

/**
 * Whether it is safe to forget a locally persisted idempotency key.
 *
 * HTTP 408 is intentionally treated like a network timeout: intermediaries
 * may return it after forwarding the request, so the outcome is uncertain.
 */
internal fun shouldDiscardPendingPromocodeAttempt(httpStatus: Int?): Boolean =
    httpStatus != null && httpStatus in 400..499 && httpStatus != 408
