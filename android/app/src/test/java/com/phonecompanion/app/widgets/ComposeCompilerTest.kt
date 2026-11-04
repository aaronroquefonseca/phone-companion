package com.phonecompanion.app.widgets

import androidx.compose.runtime.Composer
import org.junit.Assert.assertTrue
import org.junit.Test

/** A normal Kotlin build succeeds without the Compose compiler but ships unusable widgets. */
class ComposeCompilerTest {
    @Test
    fun `production widget composition has the Compose runtime ABI`() {
        val composition = Class.forName("com.phonecompanion.app.widgets.CompanionWidgetKt")
            .declaredMethods.single { it.name == "CompanionComposition" }
        assertTrue(
            "Glance composables must be transformed by the Compose compiler",
            composition.parameterTypes.contains(Composer::class.java),
        )
    }
}
