package com.phonecompanion.app.widgets

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the launcher-facing widget sizing + configuration contract: all
 * four providers declare `android:configure`, accept the smallest allowed
 * size in both axes, and show no static preview in the picker (label +
 * description only). Reads the shipped XML so manifest-side regressions
 * fail the unit suite, not the field.
 */
class WidgetInfoResTest {

    private fun resFile(name: String): File {
        var dir = File("src/main/res/xml").absoluteFile
        repeat(6) {
            val candidate = File(dir, name)
            if (candidate.isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError("missing resource file: $name (looked under ${File("src/main/res/xml").absolutePath})")
    }

    private fun providerXml(type: String): String =
        resFile("${type}_widget_info.xml").readText()

    @Test
    fun `all four providers declare the configuration activity`() {
        for (type in listOf("text", "secret", "list", "status")) {
            val xml = providerXml(type)
            assertTrue(
                "$type: missing android:configure",
                xml.contains("android:configure=\"com.phonecompanion.app.ui.WidgetConfigActivity\""),
            )
        }
    }

    @Test
    fun `all four providers accept the smallest allowed size and resize freely`() {
        for (type in listOf("text", "secret", "list", "status")) {
            val xml = providerXml(type)
            assertTrue("$type: minWidth", xml.contains("android:minWidth=\"40dp\""))
            assertTrue("$type: minHeight", xml.contains("android:minHeight=\"40dp\""))
            assertTrue("$type: minResizeWidth", xml.contains("android:minResizeWidth=\"40dp\""))
            assertTrue("$type: minResizeHeight", xml.contains("android:minResizeHeight=\"40dp\""))
            assertTrue(
                "$type: resizeMode",
                xml.contains("android:resizeMode=\"horizontal|vertical\""),
            )
            assertTrue("$type: targetCellWidth", xml.contains("android:targetCellWidth="))
            assertTrue("$type: targetCellHeight", xml.contains("android:targetCellHeight="))
        }
    }

    @Test
    fun `no provider references a static preview`() {
        for (type in listOf("text", "secret", "list", "status")) {
            val xml = providerXml(type)
            assertFalse("$type: previewLayout", xml.contains("previewLayout"))
            assertFalse("$type: previewImage", xml.contains("previewImage"))
        }
    }

    @Test
    fun `static preview layouts are gone`() {
        val layoutDir = File(resFile("text_widget_info.xml").parentFile, "../layout")
        for (name in listOf(
            "preview_text_widget.xml",
            "preview_secret_widget.xml",
            "preview_list_widget.xml",
            "preview_status_widget.xml",
        )) {
            assertFalse("$name still shipped", File(layoutDir, name).exists())
        }
    }
}
