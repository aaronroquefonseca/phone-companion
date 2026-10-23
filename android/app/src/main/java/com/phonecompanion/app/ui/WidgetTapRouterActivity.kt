package com.phonecompanion.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.phonecompanion.app.data.SecureStorage

/**
 * Tap router for home-screen widgets (finding 32).
 *
 * Glance `actionStartActivity` needs one fixed target class, but the right
 * destination depends on pairing state: the Widgets screen when paired,
 * Pair when unpaired. This transparent activity makes that branch and
 * finishes without rendering anything.
 *
 * Position-binding note (user-visible in MainActivity's About explainer):
 * each widget slot shows the cached row matching its position among the
 * bound widgets of its type (see
 * [com.phonecompanion.app.widgets.WidgetInstancePicker]) — add a second
 * text widget and it shows the second text row. A per-instance
 * configuration activity (picking a specific row per widget) is out of
 * scope at v1.
 */
class WidgetTapRouterActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paired = try {
            SecureStorage.getInstance(this).isPaired
        } catch (_: Exception) {
            false
        }
        startActivity(Intent(this, routeFor(paired)))
        finish()
    }

    companion object {
        /**
         * Pure routing decision, JVM-unit-testable without Android.
         */
        fun routeFor(paired: Boolean): Class<*> =
            if (paired) WidgetsActivity::class.java else PairActivity::class.java
    }
}
