package com.phonecompanion.app.ui

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.ListWidget
import com.phonecompanion.app.widgets.SecretWidget
import com.phonecompanion.app.widgets.StatusWidget
import com.phonecompanion.app.widgets.TextWidget
import com.phonecompanion.app.widgets.WidgetBindingResolver
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
 * Per-instance selection lives here too (no separate config activity):
 * each home-screen Glance instance gets a button showing its current row
 * (or the default first row, plus a `was-<old id>` hint when its pinned
 * row is gone); tapping opens a row picker that pins the choice into the
 * `widget_binding` table and pushes the instance immediately.
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
            renderInstances(widgets)
        }
    }

    /**
     * Per-instance assignment section: one header + one picker button per
     * bound home-screen instance of each type. Binding reads and Glance
     * enumeration are best-effort (miss reads as unpinned); a failed
     * section renders its note instead of blocking the others.
     */
    private suspend fun renderInstances(cached: List<WidgetCacheEntity>) {
        instancesContainer.removeAllViews()
        val rowsByType = cached.groupBy { it.type }
        for (provider in PROVIDERS) {
            val rows = rowsByType[provider.type].orEmpty()
            val header = TextView(this).apply {
                text = "${provider.label} widgets on home screen"
            }
            instancesContainer.addView(header)
            val bound = boundInstances(provider)
            if (bound == null) {
                instancesContainer.addView(TextView(this).apply {
                    text = "(${provider.label} instances unreadable — showing cached rows only)"
                })
                continue
            }
            if (bound.isEmpty()) {
                instancesContainer.addView(TextView(this).apply {
                    text = "(none — long-press home screen → Widgets to add one)"
                })
                continue
            }
            val bindings = bindingsFor(provider.provider)
            for (id in bound) {
                val key = glanceKey(id)
                val pinned = bindings[key]
                val hint = WidgetBindingResolver.staleHint(rows, pinned)
                val button = Button(this).apply {
                    text = "${provider.label} • " +
                        WidgetListUi.instanceLine(key, rows, pinned, hint)
                    setOnClickListener {
                        showPicker(provider, id, rows, pinned)
                    }
                }
                instancesContainer.addView(button)
            }
        }
    }

    private data class BoundProvider(
        val type: String,
        val label: String,
        val provider: String,
        val clazz: Class<out GlanceAppWidget>,
        val widget: GlanceAppWidget,
    )

    /** Glance ids bound for [provider], or null when unreadable. */
    private suspend fun boundInstances(provider: BoundProvider): List<GlanceId>? {
        return try {
            GlanceAppWidgetManager(this).getGlanceIds(provider.clazz)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "instances lookup-failed provider=${provider.label} error=${e.javaClass.simpleName}")
            null
        }
    }

    /** Pinned widget ids by Glance key; miss reads as unpinned. */
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

    /**
     * Row picker for one instance: "Default (first row)" plus one entry
     * per cached row of the type (duplicates allowed — two instances may
     * pin the same row). A stale pin shows its `was-<old id>` hint in the
     * instance button behind the dialog; choosing re-points it.
     */
    private fun showPicker(
        provider: BoundProvider,
        id: GlanceId,
        rows: List<WidgetCacheEntity>,
        pinned: String?,
    ) {
        if (rows.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("${provider.label} widget")
                .setMessage("No cached ${provider.type} rows yet — refresh first.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val items = listOf("Default (first row: ${rows.first().title})") +
            rows.map { "${it.title} • ${it.id}" }
        val checked = rows.indexOfFirst { it.id == pinned }.let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(this)
            .setTitle("Show in this ${provider.label} widget")
            .setSingleChoiceItems(items.toTypedArray(), checked, null)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val chosen = (dialog as AlertDialog).listView.checkedItemPosition
                val widgetId = if (chosen <= 0) null else rows[chosen - 1].id
                pinRow(provider, id, widgetId)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Persists the pin, pushes the instance, and re-renders. */
    private fun pinRow(
        provider: BoundProvider,
        id: GlanceId,
        widgetId: String?,
    ) {
        lifecycleScope.launch {
            try {
                DatabaseHolder.get(this@WidgetsActivity).widgetBindingDao().upsert(
                    WidgetBindingEntity(
                        providerClass = provider.provider,
                        glanceKey = glanceKey(id),
                        widgetType = provider.type,
                        widgetId = widgetId,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "binding save-failed error=${e.javaClass.simpleName}")
                return@launch
            }
            try {
                provider.widget.update(this@WidgetsActivity, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "instance push-failed provider=${provider.label} error=${e.javaClass.simpleName}")
            }
            renderCached()
        }
    }

    companion object {
        private const val TAG = "WidgetsActivity"

        private val PROVIDERS = listOf(
            BoundProvider(
                type = "text",
                label = "Text",
                provider = TextWidget.PROVIDER,
                clazz = TextWidget::class.java,
                widget = TextWidget(),
            ),
            BoundProvider(
                type = "secret",
                label = "Secret",
                provider = SecretWidget.PROVIDER,
                clazz = SecretWidget::class.java,
                widget = SecretWidget(),
            ),
            BoundProvider(
                type = "list",
                label = "List",
                provider = ListWidget.PROVIDER,
                clazz = ListWidget::class.java,
                widget = ListWidget(),
            ),
            BoundProvider(
                type = "status",
                label = "Status",
                provider = StatusWidget.PROVIDER,
                clazz = StatusWidget::class.java,
                widget = StatusWidget(),
            ),
        )
    }
}
