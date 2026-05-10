package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.tobevpn.app.data.local.entity.AppFilterEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface AppFilterDao {
    @Query("SELECT packageName FROM app_filter")
    fun observePackages(): Flow<List<String>>

    @Query("SELECT packageName FROM app_filter")
    suspend fun getPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: AppFilterEntry)

    @Query("DELETE FROM app_filter WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM app_filter")
    suspend fun clear()
}
