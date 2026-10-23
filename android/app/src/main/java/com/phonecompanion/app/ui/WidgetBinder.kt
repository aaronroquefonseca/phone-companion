package com.phonecompanion.app.ui

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.phonecompanion.app.data.WidgetBindingDao
import com.phonecompanion.app.data.WidgetBindingEntity
import com.phonecompanion.app.widgets.WidgetDirectory
import com.phonecompanion.app.widgets.glanceKey
import kotlinx.coroutines.CancellationException

/**
 * Shared persist-pin-and-push for one home-screen widget instance.
 *
 * Single owner of the binding write + immediate push used by both the
 * Widgets screen and the configuration activity ([WidgetConfigActivity]).
 * The pin is the source of truth; the push is best-effort (the next
 * system update or poll re-pushes). Cancellation is always rethrown;
 * every other failure fails closed and silent — this object never logs
 * (shape-only logging lives in [GlancePusher], which runs on Android).
 * JVM-testable: [WidgetBindingDao] is an interface and [Pusher] is a
 * seam, so tests pin the "binding stored + push requested" contract with
 * fakes.
 */
object WidgetBinder {

    /** Pushes one instance after its binding is stored. */
    interface Pusher {
        suspend fun push(providerClass: String, glanceKey: String)
    }

    suspend fun pin(
        dao: WidgetBindingDao,
        providerClass: String,
        widgetType: String,
        glanceKey: String,
        widgetId: String?,
    ) {
        dao.upsert(
            WidgetBindingEntity(
                providerClass = providerClass,
                glanceKey = glanceKey,
                widgetType = widgetType,
                widgetId = widgetId,
            ),
        )
    }

    /**
     * Stores the binding, then requests an immediate push of that
     * instance. A failed pin skips the push; a failed push keeps the pin
     * (the next update renders it). Null [widgetId] stores an explicitly
     * unassociated binding — the widget renders "Not associated" instead
     * of another row's content.
     */
    suspend fun pinAndPush(
        dao: WidgetBindingDao,
        pusher: Pusher,
        providerClass: String,
        widgetType: String,
        glanceKey: String,
        widgetId: String?,
    ) {
        try {
            pin(dao, providerClass, widgetType, glanceKey, widgetId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        try {
            pusher.push(providerClass, glanceKey)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort: the pin is stored; the next update renders it.
        }
    }

    /**
     * Real pusher: updates the bound instance matching [glanceKey].
     * Unknown provider or no matching bound instance is a silent no-op
     * (placement flow: the system's post-config update renders the pin).
     * Shape-only logging, never ids/titles/bodies/keys... (the glance key
     * is an internal instance id — never logged).
     */
    class GlancePusher(private val context: Context) : Pusher {
        override suspend fun push(providerClass: String, glanceKey: String) {
            val entry = WidgetDirectory.forProviderClass(providerClass) ?: return
            val ids = try {
                GlanceAppWidgetManager(context).getGlanceIds(entry.widgetClass)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "chooser push lookup-failed error=${e.javaClass.simpleName}")
                return
            }
            val target = ids.firstOrNull { glanceKey(it) == glanceKey } ?: return
            try {
                entry.newWidget().update(context, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "chooser push update-failed error=${e.javaClass.simpleName}")
            }
        }

        companion object {
            private const val TAG = "WidgetBinder"
        }
    }
}
