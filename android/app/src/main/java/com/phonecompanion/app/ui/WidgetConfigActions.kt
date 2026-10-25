package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetBindingDao
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.WidgetProviderEntry

/**
 * Config-flow outcomes for one home-screen widget instance.
 *
 * Single owner of the save/cancel semantics shared by the placement and
 * re-configure paths of [WidgetConfigActivity]: saving resolves the
 * user's pick to a stable binding value and pins+pushes it; cancelling
 * performs no pin and no push by construction (it accepts no dao and no
 * pusher), so the widget stays explicitly unassociated; saving while the
 * options never loaded is unavailable (no write, no push). Pure except
 * for the delegated [WidgetBinder.pinAndPush] — JVM-testable with fakes.
 */
object WidgetConfigActions {

    sealed interface Outcome {
        data object Cancelled : Outcome
        data class Saved(val widgetId: String?) : Outcome
        /** Save ran while options never loaded: no write, no push. */
        data object Unavailable : Outcome
    }

    /**
     * Cancel: stores nothing, requests nothing. Takes no dao and no
     * pusher, so there is no write path to misuse.
     */
    fun cancel(): Outcome = Outcome.Cancelled

    /**
     * Save: resolves the user's pick to a stable binding value, stores
     * it, then requests an immediate push of that instance.
     *
     * Resolution is race-safe: a [WidgetChooser.PendingPick.Chosen] pick
     * (the rotation-safe user intent) pins its stable id, failing closed
     * to null when that id is gone from [freshRows] — a shifted cache
     * never slides the pin onto a neighbor row. Only an untouched
     * chooser ([WidgetChooser.PendingPick.None]) falls back to mapping
     * [fallbackPosition] against [snapshotRows] (the rows shown at render
     * time, which the checked radio position indexes). Save is
     * [Outcome.Unavailable] — no dao write, no push — unless
     * [optionsLoaded] (the options DB read succeeded), so a failed render
     * can never wipe a pin via the no-check fallback.
     *
     * The stored `widgetType` mirrors the picked row's cache type (or
     * `"none"` for the explicitly-unassociated pick) so the assignment UI
     * can group without joining; the renderer itself dispatches on the
     * cached row's type at update time.
     *
     * A failed pin propagates (the activity reports cancelled); a failed
     * push keeps the pin (see [WidgetBinder.pinAndPush]).
     */
    suspend fun save(
        dao: WidgetBindingDao,
        pusher: WidgetBinder.Pusher,
        entry: WidgetProviderEntry,
        glanceKey: String,
        freshRows: List<WidgetCacheEntity>,
        pending: WidgetChooser.PendingPick,
        fallbackPosition: Int,
        snapshotRows: List<WidgetCacheEntity>,
        optionsLoaded: Boolean,
    ): Outcome {
        if (!optionsLoaded) return Outcome.Unavailable
        val widgetId = when (pending) {
            is WidgetChooser.PendingPick.Chosen ->
                pending.widgetId?.takeIf { id -> freshRows.any { it.id == id } }
            WidgetChooser.PendingPick.None ->
                WidgetChooser.chosenId(snapshotRows, fallbackPosition)
        }
        WidgetBinder.pinAndPush(
            dao = dao,
            pusher = pusher,
            providerClass = entry.providerClass,
            widgetType = freshRows.find { it.id == widgetId }?.type ?: "none",
            glanceKey = glanceKey,
            widgetId = widgetId,
        )
        return Outcome.Saved(widgetId)
    }
}
