package com.phonecompanion.app.ui

import com.phonecompanion.app.data.WidgetBindingDao
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.widgets.WidgetProviderEntry

/**
 * Config-flow outcomes for one home-screen widget instance.
 *
 * Single owner of the save/cancel semantics shared by the placement and
 * re-configure paths of [WidgetConfigActivity]: saving resolves the
 * checked option to a binding value and pins+pushes it; cancelling
 * performs no pin and no push by construction (it accepts no dao and no
 * pusher), so the widget stays explicitly unassociated. Pure except for
 * the delegated [WidgetBinder.pinAndPush] — JVM-testable with fakes.
 */
object WidgetConfigActions {

    sealed interface Outcome {
        data object Cancelled : Outcome
        data class Saved(val widgetId: String?) : Outcome
    }

    /**
     * Cancel: stores nothing, requests nothing. Takes no dao and no
     * pusher, so there is no write path to misuse.
     */
    fun cancel(): Outcome = Outcome.Cancelled

    /**
     * Save: resolves [position] (a [WidgetChooser] option index) to the
     * binding value and stores it, then requests an immediate push of
     * that instance. Null stores an explicitly unassociated binding.
     */
    suspend fun save(
        dao: WidgetBindingDao,
        pusher: WidgetBinder.Pusher,
        entry: WidgetProviderEntry,
        glanceKey: String,
        rows: List<WidgetCacheEntity>,
        position: Int,
    ): Outcome {
        val widgetId = WidgetChooser.chosenId(rows, position)
        WidgetBinder.pinAndPush(
            dao = dao,
            pusher = pusher,
            providerClass = entry.providerClass,
            widgetType = entry.type,
            glanceKey = glanceKey,
            widgetId = widgetId,
        )
        return Outcome.Saved(widgetId)
    }
}
