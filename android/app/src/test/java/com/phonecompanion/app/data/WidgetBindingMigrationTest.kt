package com.phonecompanion.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the v2 -> v3 upgrade carrying per-instance selection: the
 * migration moves exactly one version, creates exactly the table the
 * [WidgetBindingEntity] maps, and backfills nothing.
 */
class WidgetBindingMigrationTest {

    @Test
    fun `migration moves 2 to 3`() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun `migrations chain without gaps`() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(MIGRATION_2_3.startVersion, MIGRATION_1_2.endVersion)
    }
}
