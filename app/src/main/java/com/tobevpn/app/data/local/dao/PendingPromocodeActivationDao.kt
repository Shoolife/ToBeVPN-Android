package com.tobevpn.app.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.tobevpn.app.data.local.entity.PendingPromocodeActivationEntity

@Dao
interface PendingPromocodeActivationDao {

    @Query(
        "SELECT * FROM pending_promocode_activations " +
            "WHERE telegramId = :telegramId AND code = :code LIMIT 1",
    )
    suspend fun get(
        telegramId: Long,
        code: String,
    ): PendingPromocodeActivationEntity?

    @Upsert
    suspend fun upsert(activation: PendingPromocodeActivationEntity)

    @Query(
        "DELETE FROM pending_promocode_activations " +
            "WHERE telegramId = :telegramId AND code = :code AND requestId = :requestId",
    )
    suspend fun deleteIfMatches(
        telegramId: Long,
        code: String,
        requestId: String,
    ): Int
}
