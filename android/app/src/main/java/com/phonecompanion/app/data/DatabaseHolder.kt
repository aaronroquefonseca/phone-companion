package com.phonecompanion.app.data

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Shared Room database file name. */
internal const val DB_NAME = "phone-companion.db"

/**
 * v1 -> v2: the widget-cache table was added alongside `devices`.
 * Must match [WidgetCacheEntity] exactly — Room validates migrations
 * against the entity schema when the database is opened.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `widget_cache` (" +
                "`id` TEXT NOT NULL, " +
                "`type` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, " +
                "`updatedAt` TEXT NOT NULL, " +
                "`payloadJson` TEXT NOT NULL, " +
                "`secretRef` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
    }
}

/**
 * Process-wide Room singleton shared by the widgets and the poller.
 *
 * Uses the real [MIGRATION_1_2] — never fallbackToDestructiveMigration, so a
 * version bump cannot silently wipe the widget cache. Callers must NOT close
 * the returned instance.
 */
object DatabaseHolder {
    @Volatile
    private var instance: AppDatabase? = null

    fun get(context: Context): AppDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DB_NAME,
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
}
