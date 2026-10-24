package com.phonecompanion.app.widgets

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Single registry for the four per-type Glance widgets.
 *
 * Shared by the push path ([com.phonecompanion.app.work.WidgetPoller]),
 * the Widgets screen, the configuration activity, and the chooser — one
 * place mapping provider class ↔ receiver class ↔ cache type ↔ human
 * label, so the four hosts cannot drift. The [newWidget] factory keeps
 * push call sites from holding widget singletons.
 */
data class WidgetProviderEntry(
    /** Cache type (`text|secret|list|status`). */
    val type: String,
    /** Human label ("Text", "Secret", ...). */
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
            type = "text",
            label = "Text",
            providerClass = TextWidget.PROVIDER,
            receiverClass = TextWidgetReceiver::class.java,
            widgetClass = TextWidget::class.java,
            newWidget = ::TextWidget,
        ),
        WidgetProviderEntry(
            type = "secret",
            label = "Secret",
            providerClass = SecretWidget.PROVIDER,
            receiverClass = SecretWidgetReceiver::class.java,
            widgetClass = SecretWidget::class.java,
            newWidget = ::SecretWidget,
        ),
        WidgetProviderEntry(
            type = "list",
            label = "List",
            providerClass = ListWidget.PROVIDER,
            receiverClass = ListWidgetReceiver::class.java,
            widgetClass = ListWidget::class.java,
            newWidget = ::ListWidget,
        ),
        WidgetProviderEntry(
            type = "status",
            label = "Status",
            providerClass = StatusWidget.PROVIDER,
            receiverClass = StatusWidgetReceiver::class.java,
            widgetClass = StatusWidget::class.java,
            newWidget = ::StatusWidget,
        ),
    )

    fun forType(type: String): WidgetProviderEntry? = entries.find { it.type == type }

    fun forProviderClass(providerClass: String): WidgetProviderEntry? =
        entries.find { it.providerClass == providerClass }

    /** Placement-flow lookup: the manager reports the *receiver* class. */
    fun forReceiverClass(receiverClassName: String): WidgetProviderEntry? =
        entries.find { it.receiverClass.name == receiverClassName }
}
