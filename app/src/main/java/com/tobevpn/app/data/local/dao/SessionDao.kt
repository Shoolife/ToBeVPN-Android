package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tobevpn.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query(
        "SELECT * FROM session " +
            "ORDER BY CASE WHEN authState = 'AUTHENTICATED' AND telegramId IS NOT NULL THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN isLinked = 1 THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN accessToken IS NOT NULL AND refreshToken IS NOT NULL THEN 1 ELSE 0 END DESC " +
            "LIMIT 1",
    )
    fun observeSession(): Flow<SessionEntity?>

    @Query(
        "SELECT * FROM session " +
            "ORDER BY CASE WHEN authState = 'AUTHENTICATED' AND telegramId IS NOT NULL THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN isLinked = 1 THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN accessToken IS NOT NULL AND refreshToken IS NOT NULL THEN 1 ELSE 0 END DESC " +
            "LIMIT 1",
    )
    suspend fun getSession(): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("DELETE FROM session WHERE deviceId != :deviceId")
    suspend fun deleteAllExcept(deviceId: String)

    @Transaction
    suspend fun upsertSingle(session: SessionEntity) {
        upsert(session)
        deleteAllExcept(session.deviceId)
    }

    @Query(
        "UPDATE session SET authState = :state, telegramId = :telegramId, userPlan = :plan " +
            "WHERE deviceId = (SELECT deviceId FROM session " +
            "ORDER BY CASE WHEN authState = 'AUTHENTICATED' AND telegramId IS NOT NULL THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN isLinked = 1 THEN 1 ELSE 0 END DESC LIMIT 1)",
    )
    suspend fun updateAuth(state: String, telegramId: Long, plan: String)

    @Query(
        "UPDATE session SET email = :email WHERE deviceId = (SELECT deviceId FROM session " +
            "ORDER BY CASE WHEN authState = 'AUTHENTICATED' AND telegramId IS NOT NULL THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN isLinked = 1 THEN 1 ELSE 0 END DESC LIMIT 1)",
    )
    suspend fun updateEmail(email: String?)

    @Query(
        "SELECT email FROM session " +
            "ORDER BY CASE WHEN authState = 'AUTHENTICATED' AND telegramId IS NOT NULL THEN 1 ELSE 0 END DESC, " +
            "CASE WHEN isLinked = 1 THEN 1 ELSE 0 END DESC LIMIT 1",
    )
    fun observeEmail(): Flow<String?>
}
