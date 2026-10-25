package com.phonecompanion.app.widgets

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Single registry for the one home-screen Glance widget.
 *
 * Shared by the push path ([com.phonecompanion.app.work.WidgetPoller]),
 * the Widgets screen, the configuration activity, and the chooser — one
 * place mapping provider class ↔ receiver class ↔ human label, so the
 * hosts cannot drift. The [newWidget] factory keeps push call sites from
 * holding a widget singleton.
 *
 * The single instance can show any cached row: per-type rendering lives in
 * [WidgetContent] (dispatched by [CompanionContent.resolveForRow] on the
 * bound row's `type`), and the chooser lists every cached row regardless of
 * type. [KNOWN_TYPES] names the cache types the UI groups by; rows of any
 * other type still list (flagged `unknown-type` on the list screen) and
 * render the unassociated state on the home screen.
 */
data class WidgetProviderEntry(
    /** Logical group for this entry; the single widget uses `"all"`. */
    val type: String,
    /** Human label ("Widget"). */
    val label: String,
    /** Stable key for the `widget_binding` table. */
    val providerClass: String,
    /** Manifest provider used by the placement flow (`android:configure`). */
    val receiverClass: Class<out GlanceAppWidgetReceiver>,
    val widgetClass: Class<out GlanceAppWidget>,
    val newWidget: () -> GlanceAppWidget,
)

object WidgetDirectory {
    val entries: List<WidgetProviderEntry> = listOf(
        WidgetProviderEntry(
            type = "all",
            label = "Widget",
            providerClass = CompanionWidget.PROVIDER,
            receiverClass = CompanionWidgetReceiver::class.java,
            widgetClass = CompanionWidget::class.java,
            newWidget = ::CompanionWidget,
        ),
    )

    /** Cache types with dedicated render paths. */
    val KNOWN_TYPES: List<String> = listOf("text", "secret", "list", "status")

    fun forType(type: String): WidgetProviderEntry? = entries.find { it.type == type }

    fun forProviderClass(providerClass: String): WidgetProviderEntry? =
        entries.find { it.providerClass == providerClass }

    /** Placement-flow lookup: the manager reports the *receiver* class. */
    fun forReceiverClass(receiverClassName: String): WidgetProviderEntry? =
        entries.find { it.receiverClass.name == receiverClassName }
}
