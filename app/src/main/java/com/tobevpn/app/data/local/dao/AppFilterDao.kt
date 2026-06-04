package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.tobevpn.app.data.local.entity.AppFilterEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface AppFilterDao {
    @Query("SELECT packageName FROM app_filter ORDER BY packageName")
    fun observePackages(): Flow<List<String>>

    @Query("SELECT packageName FROM app_filter ORDER BY packageName")
    suspend fun getPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: AppFilterEntry)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entries: List<AppFilterEntry>)

    @Query("DELETE FROM app_filter WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM app_filter")
    suspend fun clear()

    @Transaction
    suspend fun replaceAll(packageNames: Collection<String>) {
        clear()
        insertAll(
            packageNames.asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .sorted()
                .map { AppFilterEntry(it) }
                .toList(),
        )
    }
}
