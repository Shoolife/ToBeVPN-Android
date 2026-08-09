package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tobevpn.app.data.local.entity.BaseStationBypassServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BaseStationBypassServerDao {

    @Query(
        "SELECT * FROM base_station_bypass_servers " +
            "ORDER BY sortOrder ASC, name COLLATE NOCASE ASC, id ASC",
    )
    fun observeAll(): Flow<List<BaseStationBypassServerEntity>>

    @Query(
        "SELECT * FROM base_station_bypass_servers " +
            "ORDER BY sortOrder ASC, name COLLATE NOCASE ASC, id ASC",
    )
    suspend fun getAll(): List<BaseStationBypassServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<BaseStationBypassServerEntity>)

    @Query("DELETE FROM base_station_bypass_servers")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(servers: List<BaseStationBypassServerEntity>) {
        deleteAll()
        upsertAll(servers)
    }
}
