package com.phonecompanion.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Widget cache (widgets PR / P7).
 *
 * One row per widget id from GET /api/v1/widgets. Payload is stored as the
 * raw JSON string for its type envelope; per-type Glance widgets parse the
 * slice they need. Secret payloads are stored exactly as received (encrypted
 * envelope); they are NEVER cached decrypted.
 */
@Entity(tableName = "widget_cache")
data class WidgetCacheEntity(
    @PrimaryKey val id: String,
    /** One of text|secret|list|status (unknown types are ignored by UI). */
    val type: String,
    val title: String,
    val updatedAt: String,
    /** Raw payload JSON for [type]. */
    val payloadJson: String,
    /** Present only for secret widgets: kid of the key that encrypted it. */
    val secretRef: String? = null,
)

@Dao
interface WidgetCacheDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WidgetCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<WidgetCacheEntity>)

    @Query("SELECT * FROM widget_cache ORDER BY id")
    suspend fun getAll(): List<WidgetCacheEntity>

    @Query("SELECT * FROM widget_cache WHERE type = :type ORDER BY id")
    suspend fun getByType(type: String): List<WidgetCacheEntity>

    @Query("SELECT * FROM widget_cache WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WidgetCacheEntity?
}
