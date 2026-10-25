package com.phonecompanion.app.widgets

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the launcher-facing widget contract: exactly one provider with
 * `android:configure`, the smallest allowed size in both axes, and no
 * static preview in the picker (label + description only). Reads the
 * shipped XML so manifest-side regressions fail the unit suite, not the
 * field.
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

    private fun providerXml(): String = resFile("widget_info.xml").readText()

    @Test
    fun `exactly one widget_info provider exists`() {
        val xmlDir = resFile("widget_info.xml").parentFile!!
        val infos = xmlDir.listFiles { f -> f.name.endsWith("_widget_info.xml") }.orEmpty()
        assertEquals(
            "one widget_info XML, not one per type: ${infos.map { it.name }}",
            emptyList<String>(),
            infos.map { it.name },
        )
        assertTrue(xmlDir.resolve("widget_info.xml").isFile)
    }

    @Test
    fun `the single provider declares the configuration activity`() {
        assertTrue(
            "missing android:configure",
            providerXml().contains("android:configure=\"com.phonecompanion.app.ui.WidgetConfigActivity\""),
        )
    }

    @Test
    fun `the single provider accepts the smallest allowed size and resizes freely`() {
        val xml = providerXml()
        assertTrue(xml.contains("android:minWidth=\"40dp\""))
        assertTrue(xml.contains("android:minHeight=\"40dp\""))
        assertTrue(xml.contains("android:minResizeWidth=\"40dp\""))
        assertTrue(xml.contains("android:minResizeHeight=\"40dp\""))
        assertTrue(xml.contains("android:resizeMode=\"horizontal|vertical\""))
        assertTrue(xml.contains("android:targetCellWidth="))
        assertTrue(xml.contains("android:targetCellHeight="))
    }

    @Test
    fun `no provider references a static preview`() {
        val xml = providerXml()
        assertFalse("previewLayout", xml.contains("previewLayout"))
        assertFalse("previewImage", xml.contains("previewImage"))
    }

    @Test
    fun `the single provider is the generic widget entry`() {
        val xml = providerXml()
        assertTrue(xml.contains("@string/widget_label"))
        assertTrue(xml.contains("@string/widget_desc"))
    }

    @Test
    fun `widget strings are one generic label plus a plain description`() {
        val strings = resFile("../values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"widget_label\">Widget</string>"))
        assertTrue(strings.contains("<string name=\"widget_desc\">"))
        for (old in listOf("widget_text_label", "widget_list_label", "widget_status_label", "widget_secret_label")) {
            assertFalse("$old still shipped", strings.contains(old))
        }
    }

    @Test
    fun `static preview layouts are gone`() {
        val layoutDir = File(resFile("widget_info.xml").parentFile, "../layout")
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
