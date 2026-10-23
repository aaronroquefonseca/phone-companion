package com.phonecompanion.app.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.text.Text

/**
 * Sample text-widget stub proving the Glance wiring compiles.
 * TODO (widgets PR): one implementation per widget type
 * (text/secret/list/status); shared polling plumbing feeds them.
 */
class HelloWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // TODO (widgets PR): render cached payload for this widget id.
            Text("Phone Companion (skeleton)")
        }
    }
}

class HelloWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HelloWidget()
}
