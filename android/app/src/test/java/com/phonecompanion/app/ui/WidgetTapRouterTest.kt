package com.phonecompanion.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetTapRouterTest {

    @Test
    fun `paired routes to widgets, unpaired to pair`() {
        assertEquals(
            WidgetsActivity::class.java,
            WidgetTapRouterActivity.routeFor(true),
        )
        assertEquals(
            PairActivity::class.java,
            WidgetTapRouterActivity.routeFor(false),
        )
    }
}
