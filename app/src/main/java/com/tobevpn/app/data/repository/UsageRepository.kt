package com.tobevpn.app.data.repository

import com.tobevpn.app.data.local.dao.UsageDao
import com.tobevpn.app.data.local.entity.UsageEntity
import com.tobevpn.app.domain.model.UsageInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(
    private val usageDao: UsageDao,
) {
    fun observeUsage(): Flow<UsageInfo> {
        return usageDao.observeUsage().map { entity ->
            entity?.toUsageInfo() ?: UsageInfo()
        }
    }

    suspend fun getUsage(): UsageInfo {
        return usageDao.getUsage()?.toUsageInfo() ?: UsageInfo()
    }

    suspend fun ensureInitialized() {
        if (usageDao.getUsage() == null) {
            usageDao.upsert(UsageEntity())
        }
    }

    suspend fun addBytes(bytes: Long) {
        val current = usageDao.getUsage() ?: UsageEntity()
        usageDao.updateBytesUsed(current.bytesUsed + bytes)
    }

    suspend fun addTime(seconds: Long) {
        val current = usageDao.getUsage() ?: UsageEntity()
        usageDao.updateTimeUsed(current.timeUsedSeconds + seconds)
    }

    suspend fun updateUsage(bytesUsed: Long, timeUsedSeconds: Long) {
        usageDao.updateUsage(bytesUsed, timeUsedSeconds)
    }

    suspend fun updateLimits(bytesLimit: Long, timeLimitSeconds: Long) {
        usageDao.updateLimits(bytesLimit, timeLimitSeconds)
    }

    suspend fun setLastConnected(timestamp: Long) {
        usageDao.updateLastConnected(timestamp)
    }

    suspend fun resetSession() {
        usageDao.updateUsage(0, 0)
    }

    suspend fun isExhausted(): Boolean {
        return getUsage().isExhausted
    }

    private fun UsageEntity.toUsageInfo() = UsageInfo(
        bytesUsed = bytesUsed,
        bytesLimit = bytesLimit,
        timeUsedSeconds = timeUsedSeconds,
        timeLimitSeconds = timeLimitSeconds,
    )
}
