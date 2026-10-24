package com.phonecompanion.app.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.WidgetBindingResolver
import com.phonecompanion.app.widgets.WidgetDirectory
import com.phonecompanion.app.widgets.WidgetProviderEntry
import com.phonecompanion.app.widgets.glanceKey
import com.phonecompanion.app.widgets.glanceKeyForAppWidgetId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Widget configuration activity: choose which cached row one home-screen
 * instance shows.
 *
 * Two entry modes, one chooser:
 * - Placement: the launcher opens this (see `android:configure` on all
 *   four widget_info XML files) with `EXTRA_APPWIDGET_ID`. The
 *   provider is looked up from [AppWidgetManager.getAppWidgetInfo]; the
 *   user picks a row (or leaves the widget unassociated), the binding is
 *   stored, an immediate push is requested, and the activity returns
 *   `RESULT_OK` so the system adds the widget. Cancelling (back/Cancel)
 *   returns `RESULT_CANCELED` and stores nothing — the widget stays
 *   explicitly unassociated.
 * - Re-configure: an unassociated widget's tap, or a Widgets-screen
 *   instance button, opens this via [editIntent] with the provider class
 *   and Glance key. Saving re-points the binding and pushes the instance.
 *
 * A stale pin (cached row gone from the server) shows its `was-<old id>`
 * re-point hint so the user knows which row disappeared. An empty cache
 * explains itself and offers only "Not associated". Secret rows are listed
 * by title like every other type (titles only, never values).
 */
class WidgetConfigActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var hintView: TextView
    private lateinit var noteView: TextView
    private lateinit var optionsGroup: RadioGroup
    private lateinit var saveButton: Button

    /**
     * The user's pending (not yet saved) pick. Rebuilt from the stored
     * pin on first open; every user tap updates it; rotation persists it
     * through [onSaveInstanceState] so the rebuilt chooser checks the
     * pending pick — never the stale stored pin — and Save pins it.
     */
    private var pendingPick: WidgetChooser.PendingPick = WidgetChooser.PendingPick.None

    /** Rows backing the current radio buttons (for pick tracking). */
    private var currentRows: List<WidgetCacheEntity> = emptyList()

    /**
     * True once the options rendered from a successful DB read. Save is
     * gated on it twice (disabled button plus the [WidgetConfigActions]
     * check) so a failed render can never wipe a pin.
     */
    private var optionsLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPick = WidgetChooser.PendingPick.restore(
            hasPending = savedInstanceState?.getBoolean(KEY_HAS_PENDING, false) == true,
            widgetId = savedInstanceState?.getString(KEY_PENDING_WIDGET_ID),
        )

        val appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        val managerProviderClass = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            try {
                AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        val resolved = resolveTarget(
            providerExtra = intent.getStringExtra(EXTRA_PROVIDER_CLASS),
            keyExtra = intent.getStringExtra(EXTRA_GLANCE_KEY),
            appWidgetId = appWidgetId,
            managerProviderClass = managerProviderClass,
        )
        if (resolved == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val (entry, keyExtra) = resolved
        val glanceKey = keyExtra ?: run {
            // Placement flow: prefer the real Glance id; the canonical
            // format string is only the pre-first-bind fallback.
            try {
                glanceKey(GlanceAppWidgetManager(this).getGlanceIdBy(appWidgetId))
            } catch (_: Exception) {
                glanceKeyForAppWidgetId(appWidgetId)
            }
        }
        val resultAppWidgetId = if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            appWidgetId
        } else {
            null
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        titleView = TextView(this).apply {
            text = "Choose ${entry.label} content"
            textSize = 18f
        }
        hintView = TextView(this)
        noteView = TextView(this)
        val scroll = ScrollView(this)
        optionsGroup = RadioGroup(this)
        scroll.addView(optionsGroup)
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val saveButton = Button(this).apply {
            text = "Save"
            // Enabled only after the options load (see renderOptions): a
            // failed read must never reach the save path, where the
            // no-check fallback would wipe a pin.
            isEnabled = false
            setOnClickListener {
                save(entry, glanceKey, resultAppWidgetId)
            }
        }
        this.saveButton = saveButton
        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                // Stores nothing, requests nothing (see
                // WidgetConfigActions.cancel): the widget stays explicitly
                // unassociated.
                WidgetConfigActions.cancel()
                setResult(RESULT_CANCELED)
                finish()
            }
        }
        buttons.addView(saveButton)
        buttons.addView(cancelButton)
        layout.addView(titleView)
        layout.addView(hintView)
        layout.addView(noteView)
        layout.addView(scroll)
        layout.addView(buttons)
        setContentView(layout)

        renderOptions(entry, glanceKey)
    }

    private fun renderOptions(entry: WidgetProviderEntry, glanceKey: String) {
        lifecycleScope.launch {
            val rows: List<WidgetCacheEntity>
            val pinned: String?
            try {
                val db = DatabaseHolder.get(this@WidgetConfigActivity)
                rows = db.widgetCacheDao().getByType(entry.type)
                pinned = db.widgetBindingDao().get(entry.providerClass, glanceKey)?.widgetId
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                noteView.text = "Could not read cached widgets — try again."
                optionsLoaded = false
                saveButton.isEnabled = false
                return@launch
            }
            val staleHint = WidgetBindingResolver.staleHint(rows, pinned)
            hintView.text = if (staleHint != null) {
                "$staleHint is gone from the server — pick a row to re-point this widget."
            } else {
                ""
            }
            hintView.visibility = if (staleHint != null) View.VISIBLE else View.GONE
            noteView.text = if (rows.isEmpty()) {
                "No cached ${entry.type} rows yet — open Widgets to refresh first."
            } else {
                ""
            }
            noteView.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            optionsGroup.removeAllViews()
            val options = WidgetChooser.options(rows)
            val checked = pendingPick.checkedIndex(rows, pinned)
            options.forEachIndexed { index, option ->
                optionsGroup.addView(
                    RadioButton(this@WidgetConfigActivity).apply {
                        text = option.line
                        id = View.generateViewId()
                        tag = index
                        isChecked = index == checked
                    },
                )
            }
            currentRows = rows
            // Track taps (after building, so the initial check above does
            // not count as a user pick): every change updates the pending
            // pick that rotation restores and Save pins.
            optionsGroup.setOnCheckedChangeListener { group, checkedId ->
                val position = (group.findViewById<RadioButton>(checkedId)?.tag as? Int) ?: 0
                pendingPick = WidgetChooser.PendingPick.Chosen(
                    WidgetChooser.chosenId(currentRows, position),
                )
            }
            // Options are showing: Save may run.
            optionsLoaded = true
            saveButton.isEnabled = true
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val pick = pendingPick as? WidgetChooser.PendingPick.Chosen
        outState.putBoolean(KEY_HAS_PENDING, pick != null)
        pick?.widgetId?.let { outState.putString(KEY_PENDING_WIDGET_ID, it) }
    }

    private fun save(entry: WidgetProviderEntry, glanceKey: String, resultAppWidgetId: Int?) {
        lifecycleScope.launch {
            val freshRows: List<WidgetCacheEntity>
            try {
                freshRows = DatabaseHolder.get(this@WidgetConfigActivity).widgetCacheDao()
                    .getByType(entry.type)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setResult(RESULT_CANCELED)
                finish()
                return@launch
            }
            val checkedId = optionsGroup.checkedRadioButtonId
            val fallbackPosition = if (checkedId == View.NO_ID) {
                0
            } else {
                (optionsGroup.findViewById<RadioButton>(checkedId)?.tag as? Int) ?: 0
            }
            val outcome = try {
                WidgetConfigActions.save(
                    dao = DatabaseHolder.get(this@WidgetConfigActivity).widgetBindingDao(),
                    pusher = WidgetBinder.GlancePusher.forContext(this@WidgetConfigActivity),
                    entry = entry,
                    glanceKey = glanceKey,
                    freshRows = freshRows,
                    pending = pendingPick,
                    fallbackPosition = fallbackPosition,
                    snapshotRows = currentRows,
                    optionsLoaded = optionsLoaded,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setResult(RESULT_CANCELED)
                finish()
                return@launch
            }
            if (outcome !is WidgetConfigActions.Outcome.Saved) {
                // Unavailable (options never loaded): report cancelled.
                setResult(RESULT_CANCELED)
                finish()
                return@launch
            }
            if (resultAppWidgetId != null) {
                setResult(
                    RESULT_OK,
                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, resultAppWidgetId),
                )
            } else {
                setResult(RESULT_OK)
            }
            finish()
        }
    }

    companion object {
        /** Re-configure extra: `widget_binding.providerClass` of the instance. */
        const val EXTRA_PROVIDER_CLASS = "pc_provider_class"

        /** Re-configure extra: `widget_binding.glanceKey` of the instance. */
        const val EXTRA_GLANCE_KEY = "pc_glance_key"

        private const val KEY_HAS_PENDING = "pc_pending_has"
        private const val KEY_PENDING_WIDGET_ID = "pc_pending_widget_id"

        /**
         * Re-configure intent for one bound instance (unassociated widget
         * tap, Widgets-screen instance button).
         */
        fun editIntent(context: Context, providerClass: String, glanceKey: String): Intent =
            Intent(context, WidgetConfigActivity::class.java)
                .putExtra(EXTRA_PROVIDER_CLASS, providerClass)
                .putExtra(EXTRA_GLANCE_KEY, glanceKey)

        /**
         * Pure target resolution, JVM-testable. The placement flow supplies
         * a valid [appWidgetId] plus the manager's provider class name;
         * re-configure supplies [providerExtra]/[keyExtra]. Returns the
         * entry plus the re-configure glance key (null for placement —
         * the caller derives it from the Glance id), or null when the
         * target cannot be determined.
         */
        fun resolveTarget(
            providerExtra: String?,
            keyExtra: String?,
            appWidgetId: Int,
            managerProviderClass: String?,
        ): Pair<WidgetProviderEntry, String?>? {
            if (providerExtra != null) {
                val entry = WidgetDirectory.forProviderClass(providerExtra) ?: return null
                if (keyExtra.isNullOrEmpty()) return null
                return entry to keyExtra
            }
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
            // The manager reports the *receiver* class (TextWidgetReceiver),
            // while bindings key on the widget class (TextWidget.PROVIDER).
            val entry = managerProviderClass?.let(WidgetDirectory::forReceiverClass)
                ?: return null
            return entry to null
        }
    }
}
