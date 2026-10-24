package com.phonecompanion.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.WidgetBindingResolver
import com.phonecompanion.app.widgets.WidgetDirectory
import com.phonecompanion.app.widgets.WidgetProviderEntry
import com.phonecompanion.app.widgets.glanceKey
import com.phonecompanion.app.work.RefreshWorker
import com.phonecompanion.app.work.SharedPrefsPollStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Widget list screen (widgets PR / P7).
 *
 * Manual refresh on open + explicit refresh button. Hourly background poll
 * is scheduled idempotently here (see [RefreshWorker.scheduleHourly]).
 * Shows every cached widget row (id/type/title/updated_at plus a
 * payload-shape health marker from Room) plus the last poll
 * status/error instead of silent empty states; rendering of cached rows
 * per type lives in widgets/, data-layer fetch and pairing stay in their
 * owner PRs.
 *
 * Per-instance selection lives here too: each home-screen Glance instance
 * gets a human-labelled button (type + chosen row title or
 * "Not associated", never a raw Glance id); tapping it opens the same
 * chooser as the placement configuration ([WidgetConfigActivity]), which
 * stores the binding and pushes the instance immediately.
 */
class WidgetsActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var rowsContainer: LinearLayout
    private lateinit var instancesContainer: LinearLayout

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
        instancesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        layout.addView(statusView)
        layout.addView(refreshButton)
        layout.addView(rowsContainer)
        layout.addView(instancesContainer)
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
            val revokedAtMs = try {
                SharedPrefsPollStateStore(this@WidgetsActivity).revokedAtMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            statusView.text = WidgetListUi.statusText(status, revokedAtMs)
            rowsContainer.removeAllViews()
            val rows = WidgetListUi.rowTexts(widgets)
            if (rows.isEmpty()) {
                rowsContainer.addView(TextView(this@WidgetsActivity).apply {
                    text = WidgetListUi.emptyStateText(status, revokedAtMs)
                })
            } else {
                for (row in rows) {
                    rowsContainer.addView(TextView(this@WidgetsActivity).apply {
                        text = row
                    })
                }
            }
            renderInstances(widgets)
        }
    }

    /**
     * Per-instance assignment section: one header + one human-labelled
     * button per bound home-screen instance of each type. Tapping a button
     * opens the configuration chooser for that instance; saving there
     * re-renders this screen on resume. Binding reads and Glance
     * enumeration are best-effort (miss reads as unassociated); a failed
     * section renders its note instead of blocking the others.
     */
    private suspend fun renderInstances(cached: List<WidgetCacheEntity>) {
        instancesContainer.removeAllViews()
        val rowsByType = cached.groupBy { it.type }
        for (entry in WidgetDirectory.entries) {
            val rows = rowsByType[entry.type].orEmpty()
            val header = TextView(this).apply {
                text = "${entry.label} widgets on home screen"
            }
            instancesContainer.addView(header)
            val bound = boundInstances(entry)
            if (bound == null) {
                instancesContainer.addView(TextView(this).apply {
                    text = "(${entry.label} instances unreadable — showing cached rows only)"
                })
                continue
            }
            if (bound.isEmpty()) {
                instancesContainer.addView(TextView(this).apply {
                    text = "(none — long-press home screen → Widgets to add one)"
                })
                continue
            }
            val bindings = bindingsFor(entry.providerClass)
            for (id in bound) {
                val key = glanceKey(id)
                val pinned = bindings[key]
                val hint = WidgetBindingResolver.staleHint(rows, pinned)
                val button = Button(this).apply {
                    text = WidgetListUi.instanceLabel(entry.label, rows, pinned, hint)
                    setOnClickListener {
                        startActivity(
                            WidgetConfigActivity.editIntent(
                                this@WidgetsActivity,
                                entry.providerClass,
                                key,
                            ),
                        )
                    }
                }
                instancesContainer.addView(button)
            }
        }
    }

    /** Glance ids bound for [entry], or null when unreadable. */
    private suspend fun boundInstances(entry: WidgetProviderEntry): List<GlanceId>? {
        return try {
            GlanceAppWidgetManager(this).getGlanceIds(entry.widgetClass)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "instances lookup-failed provider=${entry.label} error=${e.javaClass.simpleName}")
            null
        }
    }

    /** Pinned widget ids by Glance key; miss reads as unassociated. */
    private suspend fun bindingsFor(providerClass: String): Map<String, String?> {
        return try {
            DatabaseHolder.get(this).widgetBindingDao()
                .getForProvider(providerClass)
                .associate { it.glanceKey to it.widgetId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "bindings lookup-failed error=${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    companion object {
        private const val TAG = "WidgetsActivity"
    }
}
