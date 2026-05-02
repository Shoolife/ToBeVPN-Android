package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tobevpn.app.data.local.entity.UsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UsageDao {

    @Query("SELECT * FROM usage WHERE id = 1")
    fun observeUsage(): Flow<UsageEntity?>

    @Query("SELECT * FROM usage WHERE id = 1")
    suspend fun getUsage(): UsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: UsageEntity)

    @Query("UPDATE usage SET bytesUsed = :bytes WHERE id = 1")
    suspend fun updateBytesUsed(bytes: Long)

    @Query("UPDATE usage SET timeUsedSeconds = :seconds WHERE id = 1")
    suspend fun updateTimeUsed(seconds: Long)

    @Query("UPDATE usage SET bytesUsed = :bytes, timeUsedSeconds = :seconds WHERE id = 1")
    suspend fun updateUsage(bytes: Long, seconds: Long)

    @Query("UPDATE usage SET bytesLimit = :bytesLimit, timeLimitSeconds = :timeLimit WHERE id = 1")
    suspend fun updateLimits(bytesLimit: Long, timeLimit: Long)

    @Query("UPDATE usage SET lastConnectedAt = :timestamp WHERE id = 1")
    suspend fun updateLastConnected(timestamp: Long)
}
