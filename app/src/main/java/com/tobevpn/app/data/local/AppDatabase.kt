package com.tobevpn.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tobevpn.app.data.local.dao.ServerDao
import com.tobevpn.app.data.local.dao.SessionDao
import com.tobevpn.app.data.local.dao.TrafficLogDao
import com.tobevpn.app.data.local.dao.UsageDao
import com.tobevpn.app.data.local.entity.ServerEntity
import com.tobevpn.app.data.local.entity.SessionEntity
import com.tobevpn.app.data.local.entity.TrafficLogEntity
import com.tobevpn.app.data.local.entity.UsageEntity

@Database(
    entities = [SessionEntity::class, UsageEntity::class, ServerEntity::class, TrafficLogEntity::class],
    version = 9,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun usageDao(): UsageDao
    abstract fun serverDao(): ServerDao
    abstract fun trafficLogDao(): TrafficLogDao
}
