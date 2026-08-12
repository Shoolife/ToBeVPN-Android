package com.tobevpn.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "base_station_bypass_servers")
data class BaseStationBypassServerEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val uuid: String,
    val flow: String = "",
    val security: String = "reality",
    val sni: String = "",
    val fingerprint: String = "chrome",
    val publicKey: String = "",
    val shortId: String = "",
    val network: String = "tcp",
    val path: String = "",
    @ColumnInfo(defaultValue = "''") val host: String = "",
    @ColumnInfo(defaultValue = "''") val alpn: String = "",
    @ColumnInfo(defaultValue = "''") val headerType: String = "",
    @ColumnInfo(defaultValue = "''") val serviceName: String = "",
    @ColumnInfo(defaultValue = "''") val extra: String = "",
    val mode: String = "",
    val spx: String = "",
    val country: String = "",
    val isOnline: Boolean = true,
    val sortOrder: Int = 0,
)
