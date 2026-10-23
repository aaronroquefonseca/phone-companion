package com.phonecompanion.app.widgets

import com.phonecompanion.app.data.WidgetCacheEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetInstancePickerTest {

    private fun row(id: String) = WidgetCacheEntity(
        id = id,
        type = "text",
        title = "t-$id",
        updatedAt = "2026-10-23T00:00:00Z",
        payloadJson = """{"body": "$id"}""",
    )

    @Test
    fun `empty rows yield null`() {
        assertNull(WidgetInstancePicker.pick(emptyList(), 0))
        assertNull(WidgetInstancePicker.pick(emptyList(), -1))
    }

    @Test
    fun `each bound instance gets its own row`() {
        val rows = listOf(row("a"), row("b"), row("c"))
        assertEquals("a", WidgetInstancePicker.pick(rows, 0)!!.id)
        assertEquals("b", WidgetInstancePicker.pick(rows, 1)!!.id)
        assertEquals("c", WidgetInstancePicker.pick(rows, 2)!!.id)
    }

    @Test
    fun `unbound instance falls back to first row only`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals("a", WidgetInstancePicker.pick(rows, -1)!!.id)
    }

    @Test
    fun `more instances than rows wrap around`() {
        val rows = listOf(row("a"), row("b"))
        assertEquals("a", WidgetInstancePicker.pick(rows, 2)!!.id)
        assertEquals("b", WidgetInstancePicker.pick(rows, 3)!!.id)
    }
}
