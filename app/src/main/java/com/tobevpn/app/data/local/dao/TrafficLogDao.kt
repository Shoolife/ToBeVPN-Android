package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.tobevpn.app.data.local.entity.TrafficLogEntity
import kotlinx.coroutines.flow.Flow

data class TrafficStat(
    val period: Long,
    val totalBytes: Long,
    val totalSeconds: Long,
    val sessions: Int,
)

@Dao
interface TrafficLogDao {

    @Insert
    suspend fun insert(log: TrafficLogEntity)

    // Hourly stats for a specific day
    @Query("""
        SELECT
            (timestamp / 3600) * 3600 AS period,
            SUM(bytesUsed) AS totalBytes,
            SUM(timeUsedSeconds) AS totalSeconds,
            COUNT(*) AS sessions
        FROM traffic_log
        WHERE timestamp >= :dayStart AND timestamp < :dayEnd
          AND isAuthenticated = :isAuthenticated
        GROUP BY period
        ORDER BY period ASC
    """)
    fun getHourlyStats(dayStart: Long, dayEnd: Long, isAuthenticated: Boolean): Flow<List<TrafficStat>>


    // Daily stats for a date range (timezone-aware grouping)
    @Query("""
        SELECT
            ((timestamp + :tzOffsetSec) / 86400) * 86400 - :tzOffsetSec AS period,
            SUM(bytesUsed) AS totalBytes,
            SUM(timeUsedSeconds) AS totalSeconds,
            COUNT(*) AS sessions
        FROM traffic_log
        WHERE timestamp >= :weekStart AND timestamp < :weekEnd
          AND isAuthenticated = :isAuthenticated
        GROUP BY ((timestamp + :tzOffsetSec) / 86400)
        ORDER BY period ASC
    """)
    fun getDailyStats(weekStart: Long, weekEnd: Long, isAuthenticated: Boolean, tzOffsetSec: Long): Flow<List<TrafficStat>>

    // Weekly stats for a date range
    @Query("""
        SELECT
            ((timestamp - :monthStart) / 604800) * 604800 + :monthStart AS period,
            SUM(bytesUsed) AS totalBytes,
            SUM(timeUsedSeconds) AS totalSeconds,
            COUNT(*) AS sessions
        FROM traffic_log
        WHERE timestamp >= :monthStart AND timestamp < :monthEnd
          AND isAuthenticated = :isAuthenticated
        GROUP BY period
        ORDER BY period ASC
    """)
    fun getWeeklyStats(monthStart: Long, monthEnd: Long, isAuthenticated: Boolean): Flow<List<TrafficStat>>

    // Total all-time, filtered by auth state
    @Query("SELECT COALESCE(SUM(bytesUsed), 0) FROM traffic_log WHERE isAuthenticated = :isAuthenticated")
    fun getTotalBytes(isAuthenticated: Boolean): Flow<Long>

    // Device-wide stats. Account/subscription-wide traffic is handled by the
    // subscription payload, while traffic_log is a local per-device history.
    @Query("""
        SELECT
            (timestamp / 3600) * 3600 AS period,
            SUM(bytesUsed) AS totalBytes,
            SUM(timeUsedSeconds) AS totalSeconds,
            COUNT(*) AS sessions
        FROM traffic_log
        WHERE timestamp >= :dayStart AND timestamp < :dayEnd
        GROUP BY period
        ORDER BY period ASC
    """)
    fun getDeviceHourlyStats(dayStart: Long, dayEnd: Long): Flow<List<TrafficStat>>

    @Query("""
        SELECT
            ((timestamp + :tzOffsetSec) / 86400) * 86400 - :tzOffsetSec AS period,
            SUM(bytesUsed) AS totalBytes,
            SUM(timeUsedSeconds) AS totalSeconds,
            COUNT(*) AS sessions
        FROM traffic_log
        WHERE timestamp >= :weekStart AND timestamp < :weekEnd
        GROUP BY ((timestamp + :tzOffsetSec) / 86400)
        ORDER BY period ASC
    """)
    fun getDeviceDailyStats(weekStart: Long, weekEnd: Long, tzOffsetSec: Long): Flow<List<TrafficStat>>

    @Query("""
        SELECT
            ((timestamp - :monthStart) / 604800) * 604800 + :monthStart AS period,
            SUM(bytesUsed) AS totalBytes,
            SUM(timeUsedSeconds) AS totalSeconds,
            COUNT(*) AS sessions
        FROM traffic_log
        WHERE timestamp >= :monthStart AND timestamp < :monthEnd
        GROUP BY period
        ORDER BY period ASC
    """)
    fun getDeviceWeeklyStats(monthStart: Long, monthEnd: Long): Flow<List<TrafficStat>>

    @Query("SELECT COALESCE(SUM(bytesUsed), 0) FROM traffic_log")
    fun getDeviceTotalBytes(): Flow<Long>
}
