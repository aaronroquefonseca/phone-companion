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
 * Bound widget taps land here. Unbound ("Not associated") taps go straight
 * to the configuration activity (see
 * [com.phonecompanion.app.ui.WidgetConfigActivity]), which is also where
 * placement chooses the row (see
 * [com.phonecompanion.app.widgets.WidgetBindingResolver]): each
 * home-screen instance shows exactly the cached row picked for it, or the
 * explicit unassociated state — never another row's content.
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
