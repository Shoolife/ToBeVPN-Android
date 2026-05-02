package com.tobevpn.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "usage")
data class UsageEntity(
    @PrimaryKey
    val id: Int = 1, // singleton row
    val bytesUsed: Long = 0,
    val bytesLimit: Long = 0, // 0 = unlimited
    val timeUsedSeconds: Long = 0,
    val timeLimitSeconds: Long = 0, // 0 = unlimited
    val lastConnectedAt: Long? = null, // epoch millis when VPN was last connected
)
