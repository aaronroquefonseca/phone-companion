package com.phonecompanion.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the v2 -> v3 upgrade carrying per-instance selection and the v3 ->
 * v4 single-widget collapse: the v4 migration moves exactly one version,
 * deletes exactly the orphaned provider rows (bindings keyed to the four
 * removed per-type providers), and keeps the chain gapless.
 */
class WidgetBindingMigrationTest {

    @Test
    fun `migration moves 2 to 3`() {
        assertEquals(2, MIGRATION_2_3.startVersion)
        assertEquals(3, MIGRATION_2_3.endVersion)
    }

    @Test
    fun `migration moves 3 to 4`() {
        assertEquals(3, MIGRATION_3_4.startVersion)
        assertEquals(4, MIGRATION_3_4.endVersion)
    }

    @Test
    fun `migrations chain without gaps`() {
        assertEquals(1, MIGRATION_1_2.startVersion)
        assertEquals(MIGRATION_2_3.startVersion, MIGRATION_1_2.endVersion)
        assertEquals(MIGRATION_3_4.startVersion, MIGRATION_2_3.endVersion)
    }

    @Test
    fun `v4 migration deletes orphan provider rows and nothing else`() {
        val statements = mutableListOf<String>()
        val db = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") {
                statements += args!!.filterIsInstance<String>()
                null
            } else {
                throw UnsupportedOperationException("unexpected call: ${method.name}")
            }
        } as SupportSQLiteDatabase
        MIGRATION_3_4.migrate(db)
        assertEquals("one data-only statement, no schema change", 1, statements.size)
        val sql = statements.single()
        assertTrue("must target the binding table: $sql", sql.contains("widget_binding"))
        assertTrue(
            "must keep the single provider: $sql",
            sql.contains("com.phonecompanion.app.widgets.CompanionWidget"),
        )
    }
}
