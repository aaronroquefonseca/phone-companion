package com.phonecompanion.app.ui

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.work.RefreshWorker
import com.phonecompanion.app.work.SharedPrefsPollStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Widget list screen (widgets PR / P7).
 *
 * Manual refresh on open + explicit refresh button. Hourly background poll
 * is scheduled idempotently here (see [RefreshWorker.scheduleHourly]).
 * Shows every cached widget row (id/type/title/updated_at from Room) plus
 * the last poll status/error instead of silent empty states; rendering of
 * cached rows per type lives in widgets/, data-layer fetch and pairing
 * stay in their owner PRs.
 */
class WidgetsActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var rowsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        RefreshWorker.scheduleHourly(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        statusView = TextView(this).apply {
            text = "Loading widgets…"
        }
        val refreshButton = Button(this).apply {
            text = "Refresh now"
            setOnClickListener {
                RefreshWorker.requestManualRefresh(this@WidgetsActivity)
                statusView.text = "Refresh requested."
                renderCached()
            }
        }
        rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(statusView)
        layout.addView(refreshButton)
        layout.addView(rowsContainer)
        setContentView(layout)
        renderCached()
    }

    override fun onResume() {
        super.onResume()
        // Manual refresh on open: re-pull cached state on every foreground.
        RefreshWorker.requestManualRefresh(this)
        renderCached()
    }

    /**
     * Binds the Room widget cache + last poll status to the screen. Cache
     * reads and status reads are best-effort: failures render an explicit
     * error line, never a silent empty state.
     */
    private fun renderCached() {
        lifecycleScope.launch {
            val widgets = try {
                DatabaseHolder.get(this@WidgetsActivity).widgetCacheDao().getAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                statusView.text = "Widget cache unreadable: ${e.message}"
                return@launch
            }
            val status = try {
                SharedPrefsPollStateStore(this@WidgetsActivity).lastStatus
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            statusView.text = WidgetListUi.statusText(status)
            rowsContainer.removeAllViews()
            val rows = WidgetListUi.rowTexts(widgets)
            if (rows.isEmpty()) {
                rowsContainer.addView(TextView(this@WidgetsActivity).apply {
                    text = WidgetListUi.emptyStateText(status)
                })
            } else {
                for (row in rows) {
                    rowsContainer.addView(TextView(this@WidgetsActivity).apply {
                        text = row
                    })
                }
            }
        }
    }
}
