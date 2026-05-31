package com.tobevpn.app.data.local

import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.entity.SessionEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single point of truth for session row writes.
 *
 * Why this exists: multiple sources update the session entity concurrently —
 * `BootstrapManager.persist` writes tokens, `AuthRepository.syncSubscription`
 * writes panel info, `checkAuthStatus`/`logout`/`ensurePanelUser` write auth
 * state. All of them follow a read-modify-write pattern (`getSession()` then
 * `upsert(copy(...))`), so without a shared lock two writes that fire close
 * together can lose updates.
 *
 * Every mutation should go through [update] (atomic transform) or [upsert]
 * (full replace). Plain `sessionDao.upsert(...)` calls in production code
 * defeat the lock — keep new writers funnelled through this class.
 */
@Singleton
class SessionStore @Inject constructor(
    private val sessionDao: SessionDao,
) {
    private val mutex = Mutex()

    /**
     * Atomically reads the current session, applies [transform], and writes
     * the result back. Returns the persisted entity, or `null` if no session
     * exists yet.
     */
    suspend fun update(transform: (SessionEntity) -> SessionEntity): SessionEntity? {
        return mutex.withLock {
            val current = sessionDao.getSession() ?: return@withLock null
            val updated = transform(current)
            sessionDao.upsertSingle(updated)
            updated
        }
    }

    /**
     * Read-modify-write that creates a default [SessionEntity] when none exists.
     * Used by code paths that bootstrap the very first session row (e.g. cold
     * start before any tokens have been persisted).
     */
    suspend fun updateOrCreate(
        defaultDeviceId: String,
        transform: (SessionEntity) -> SessionEntity,
    ): SessionEntity {
        return mutex.withLock {
            val current = sessionDao.getSession() ?: SessionEntity(deviceId = defaultDeviceId)
            val updated = transform(current)
            sessionDao.upsertSingle(updated)
            updated
        }
    }

    /** Full replace — used when the caller already holds the entity. */
    suspend fun upsert(session: SessionEntity) {
        mutex.withLock { sessionDao.upsertSingle(session) }
    }
}
