package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tobevpn.app.data.local.entity.ServerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ServerDao {

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers ORDER BY sortOrder ASC, name COLLATE NOCASE ASC, id ASC")
    suspend fun getAll(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(servers: List<ServerEntity>)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceAll(servers: List<ServerEntity>) {
        deleteAll()
        upsertAll(servers)
    }
}
