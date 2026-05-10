package com.tobevpn.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_filter")
data class AppFilterEntry(
    @PrimaryKey
    val packageName: String,
)
