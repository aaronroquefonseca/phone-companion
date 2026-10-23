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
 * each home-screen instance shows the cached row pinned for it in the
 * Widgets screen (see
 * [com.phonecompanion.app.widgets.WidgetBindingResolver]) — add a second
 * text widget and pin a different text row to it. Unpinned instances show
 * the first row of their type. A per-instance configuration activity
 * (picking UI outside the Widgets screen) is out of scope at v1.
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
