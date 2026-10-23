package com.phonecompanion.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

/**
 * Local storage stub.
 * TODO (pairing PR): device credential storage with revocation support.
 * TODO (widgets PR): widget cache entities.
 * Secret-bearing material must use encrypted storage (threat-model doc).
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    // TODO: never store raw tokens; hash + encrypted key material only.
    val tokenHash: String,
)

@Dao
interface DeviceDao {
    // TODO (pairing PR): insert/find/revoke queries.
}

@Database(entities = [DeviceEntity::class, WidgetCacheEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun widgetCacheDao(): WidgetCacheDao
}
