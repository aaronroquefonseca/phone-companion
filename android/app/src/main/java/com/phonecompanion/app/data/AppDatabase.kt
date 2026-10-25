package com.phonecompanion.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

/**
 * Local storage: the widget cache.
 *
 * Device credentials live in [EnrollmentStore]/[SecureStorage] (encrypted
 * shared preferences) — this database never holds secret-bearing material
 * (see docs/threat-model.md). The legacy `devices` entity below is kept only
 * as a Room schema placeholder and is not read or written at v1.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val name: String,
    // Not read or written at v1: raw tokens must never be stored here —
    // only hashes plus encrypted key material (see SecureStorage and
    // docs/threat-model.md). Kept so the v1 schema stays stable.
    val tokenHash: String,
)

@Dao
interface DeviceDao {
    // No queries at v1: pairing state is owned by SecureStorage.
}

@Database(
    entities = [DeviceEntity::class, WidgetCacheEntity::class, WidgetBindingEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun widgetCacheDao(): WidgetCacheDao
    abstract fun widgetBindingDao(): WidgetBindingDao
}
