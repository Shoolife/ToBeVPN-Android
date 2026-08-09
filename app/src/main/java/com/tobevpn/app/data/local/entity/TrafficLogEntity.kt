package com.tobevpn.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "traffic_log")
data class TrafficLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val bytesUsed: Long,
    val timeUsedSeconds: Long,
    val serverId: String = "",
    @ColumnInfo(defaultValue = "'STANDARD'")
    val serverSource: String = "STANDARD",
    val timestamp: Long, // epoch seconds, start of session
    val isAuthenticated: Boolean = false,
)
