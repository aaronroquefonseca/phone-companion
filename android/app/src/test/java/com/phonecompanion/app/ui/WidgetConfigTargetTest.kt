package com.phonecompanion.app.ui

import android.appwidget.AppWidgetManager
import com.phonecompanion.app.widgets.CompanionWidget
import com.phonecompanion.app.widgets.WidgetDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Configuration routing: placement (system `android:configure`) and
 * re-configure (unassociated tap / Widgets-screen button) both resolve to
 * the single widget entry, or nothing (activity finishes cancelled and
 * stores nothing — the widget stays explicitly unassociated).
 */
class WidgetConfigTargetTest {

    private val provider = CompanionWidget.PROVIDER

    @Test
    fun `tap mode resolves provider plus glance key`() {
        val (entry, key) = WidgetConfigActivity.resolveTarget(
            providerExtra = provider,
            keyExtra = "AppWidgetId(appWidgetId=51)",
            appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
            managerProviderClass = null,
        )!!
        assertEquals("all", entry.type)
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
                providerExtra = provider,
                keyExtra = null,
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                managerProviderClass = null,
            ),
        )
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = provider,
                keyExtra = "",
                appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                managerProviderClass = null,
            ),
        )
    }

    @Test
    fun `tap mode rejects the removed per-type providers`() {
        for (old in listOf("TextWidget", "SecretWidget", "ListWidget", "StatusWidget")) {
            assertNull(
                WidgetConfigActivity.resolveTarget(
                    providerExtra = "com.phonecompanion.app.widgets.$old",
                    keyExtra = "AppWidgetId(appWidgetId=51)",
                    appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID,
                    managerProviderClass = null,
                ),
            )
        }
    }

    @Test
    fun `placement mode resolves via the single receiver class`() {
        val (entry, key) = WidgetConfigActivity.resolveTarget(
            providerExtra = null,
            keyExtra = null,
            appWidgetId = 51,
            managerProviderClass = "com.phonecompanion.app.widgets.CompanionWidgetReceiver",
        )!!
        assertEquals("all", entry.type)
        assertNull(key)
    }

    @Test
    fun `placement mode rejects widget class names that are not receivers`() {
        // The manager reports CompanionWidgetReceiver, never the widget
        // class binding key — a widget class name must not resolve here.
        assertNull(
            WidgetConfigActivity.resolveTarget(
                providerExtra = null,
                keyExtra = null,
                appWidgetId = 51,
                managerProviderClass = "com.phonecompanion.app.widgets.CompanionWidget",
            ),
        )
    }

    @Test
    fun `placement mode rejects the removed per-type receivers`() {
        for (old in listOf("TextWidgetReceiver", "SecretWidgetReceiver", "ListWidgetReceiver", "StatusWidgetReceiver")) {
            assertNull(
                WidgetConfigActivity.resolveTarget(
                    providerExtra = null,
                    keyExtra = null,
                    appWidgetId = 51,
                    managerProviderClass = "com.phonecompanion.app.widgets.$old",
                ),
            )
        }
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
    fun `directory holds exactly the single widget entry`() {
        val entries = WidgetDirectory.entries
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("all", entry.type)
        assertEquals(provider, entry.providerClass)
        assertEquals("Widget", entry.label)
        assertNotNull(WidgetDirectory.forType("all"))
        assertNotNull(WidgetDirectory.forProviderClass(provider))
        assertNotNull(WidgetDirectory.forReceiverClass(entry.receiverClass.name))
        assertNull(WidgetDirectory.forType("text"))
        assertNull(WidgetDirectory.forType("teleport"))
        assertNull(WidgetDirectory.forProviderClass("com.example.Nope"))
        assertNull(WidgetDirectory.forReceiverClass("com.example.Nope"))
    }
}
