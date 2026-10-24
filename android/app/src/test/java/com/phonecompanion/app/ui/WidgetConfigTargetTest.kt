package com.phonecompanion.app.ui

import android.appwidget.AppWidgetManager
import com.phonecompanion.app.widgets.WidgetDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Configuration routing: placement (system `android:configure`) and
 * re-configure (unassociated tap / Widgets-screen button) both resolve to
 * exactly one provider entry, or nothing (activity finishes cancelled and
 * stores nothing — the widget stays explicitly unassociated).
 */
class WidgetConfigTargetTest {

    private val textProvider = "com.phonecompanion.app.widgets.TextWidget"

    @Test
    fun `tap mode resolves provider plus glance key`() {
        val (entry, key) = WidgetConfigActivity.resolveTarget(
            providerExtra = textProvider,
            keyExtra = "AppWidgetId(appWidgetId=51)",
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
            managerProviderClass = null,
        )!!
        assertEquals("text", entry.type)
        assertEquals("AppWidgetId(appWidgetId=51)", key)
    }

    @Test
    fun `tap mode rejects unknown provider and missing key`() {
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = "com.example.Nope",
                keyExtra = "AppWidgetId(appWidgetId=51)",
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                managerProviderClass = null,
            ),
        )
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = textProvider,
                keyExtra = null,
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                managerProviderClass = null,
            ),
        )
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = textProvider,
                keyExtra = "",
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                managerProviderClass = null,
            ),
        )
    }

    @Test
    fun `placement mode resolves via the managers receiver class`() {
        val (entry, key) = WidgetConfigActivity.resolveTarget(
            providerExtra = null,
            keyExtra = null,
            appWidgetId = 51,
            managerProviderClass = "com.phonecompanion.app.widgets.SecretWidgetReceiver",
        )!!
        assertEquals("secret", entry.type)
        assertNull(key)
    }

    @Test
    fun `placement mode rejects widget class names that are not receivers`() {
        // The manager reports TextWidgetReceiver, never the TextWidget
        // binding key — a widget class name must not resolve here.
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = null,
                keyExtra = null,
                appWidgetId = 51,
                managerProviderClass = "com.phonecompanion.app.widgets.SecretWidget",
            ),
        )
    }

    @Test
    fun `placement mode rejects invalid id and unknown provider`() {
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = null,
                keyExtra = null,
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                managerProviderClass = null,
            ),
        )
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = null,
                keyExtra = null,
                appWidgetId = 51,
                managerProviderClass = null,
            ),
        )
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = null,
                keyExtra = null,
                appWidgetId = 51,
                managerProviderClass = "com.example.Nope",
            ),
        )
    }

    @Test
    fun `directory covers all four types exactly once`() {
        val entries = WidgetDirectory.entries
        assertEquals(listOf("text", "secret", "list", "status"), entries.map { it.type })
        assertEquals(4, entries.map { it.providerClass }.toSet().size)
        assertEquals(4, entries.map { it.receiverClass }.toSet().size)
        assertEquals(4, entries.map { it.label }.toSet().size)
        for (entry in entries) {
            assertNotNull(WidgetDirectory.forType(entry.type))
            assertNotNull(WidgetDirectory.forProviderClass(entry.providerClass))
            assertNotNull(WidgetDirectory.forReceiverClass(entry.receiverClass.name))
        }
        assertNull(WidgetDirectory.forType("teleport"))
        assertNull(WidgetDirectory.forProviderClass("com.example.Nope"))
        assertNull(WidgetDirectory.forReceiverClass("com.example.Nope"))
    }
}
