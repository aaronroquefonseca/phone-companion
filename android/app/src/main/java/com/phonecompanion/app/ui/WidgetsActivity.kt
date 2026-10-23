package com.phonecompanion.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.phonecompanion.app.work.RefreshWorker

/**
 * Widget list screen (widgets PR / P7).
 *
 * Manual refresh on open + explicit refresh button. Hourly background poll
 * is scheduled idempotently here (see [RefreshWorker.scheduleHourly]).
 * Rendering of cached rows per type lives in widgets/; data-layer fetch and
 * pairing stay in their owner PRs.
 */
class WidgetsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RefreshWorker.scheduleHourly(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val status = TextView(this).apply {
            text = "Loading widgets…"
        }
        val refreshButton = Button(this).apply {
            text = "Refresh now"
            setOnClickListener {
                RefreshWorker.requestManualRefresh(this@WidgetsActivity)
                status.text = "Refresh requested."
            }
        }
        layout.addView(status)
        layout.addView(refreshButton)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        // Manual refresh on open: re-pull cached state on every foreground.
        RefreshWorker.requestManualRefresh(this)
    }
}
