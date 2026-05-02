package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tobevpn.app.data.local.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Query("SELECT * FROM session LIMIT 1")
    fun observeSession(): Flow<SessionEntity?>

    @Query("SELECT * FROM session LIMIT 1")
    suspend fun getSession(): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity)

    @Query("UPDATE session SET authState = :state, telegramId = :telegramId, userPlan = :plan WHERE deviceId = (SELECT deviceId FROM session LIMIT 1)")
    suspend fun updateAuth(state: String, telegramId: Long, plan: String)

    @Query("UPDATE session SET email = :email WHERE deviceId = (SELECT deviceId FROM session LIMIT 1)")
    suspend fun updateEmail(email: String?)

    @Query("SELECT email FROM session LIMIT 1")
    fun observeEmail(): Flow<String?>
}
