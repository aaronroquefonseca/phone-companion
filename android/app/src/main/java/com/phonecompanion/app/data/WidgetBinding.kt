package com.phonecompanion.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Per-instance widget selection (Glance all-fallback follow-up).
 *
 * One row pins one home-screen Glance instance to one cached widget row:
 * (`providerClass`, `glanceKey`) is the composite primary key, `widgetId`
 * NULL means "default" (first row of the type — single-widget users see
 * zero change). Duplicates are allowed: two instances may point at the
 * same `widgetId` (no unique constraint on it, no foreign key to
 * `widget_cache` — a binding must survive its row disappearing so the UI
 * can show a `was-<old id>` re-point hint instead of silently dropping
 * the choice).
 *
 * - `providerClass`: `TextWidget::class.java.name` etc. (stable: no
 *   obfuscation — release builds keep `minifyEnabled false`).
 * - `glanceKey`: stable per-instance key from
 *   [com.phonecompanion.app.widgets.glanceKey] (`GlanceId.toString()`).
 * - `widgetType`: the cache type (`text|secret|list|status`), mirrored so
 *   the assignment UI can group without joining.
 */
@Entity(tableName = "widget_binding", primaryKeys = ["providerClass", "glanceKey"])
data class WidgetBindingEntity(
    val providerClass: String,
    val glanceKey: String,
    val widgetType: String,
    val widgetId: String? = null,
)

@Dao
interface WidgetBindingDao {
    @Query("SELECT * FROM widget_binding WHERE providerClass = :provider AND glanceKey = :key LIMIT 1")
    suspend fun get(provider: String, key: String): WidgetBindingEntity?

    @Query("SELECT * FROM widget_binding WHERE providerClass = :provider")
    suspend fun getForProvider(provider: String): List<WidgetBindingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: WidgetBindingEntity)

    @Query("DELETE FROM widget_binding WHERE providerClass = :provider AND glanceKey = :key")
    suspend fun delete(provider: String, key: String)
}
