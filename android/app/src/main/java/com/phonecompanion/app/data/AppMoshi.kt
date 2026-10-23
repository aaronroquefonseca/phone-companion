package com.phonecompanion.app.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * Single owner of the network Moshi instance (P-widget-parse).
 *
 * [ApiClient]'s Retrofit converter and the widget parse-path regression
 * test both go through here, so the test and production parsing can never
 * drift apart: same builder, same config, same adapters. Lenient by
 * design — Moshi ignores unknown JSON fields, so additive backend changes
 * (new fields, new widget types) parse instead of crashing.
 */
object AppMoshi {
    val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    val widgetsResponseAdapter: JsonAdapter<WidgetsResponse> by lazy {
        moshi.adapter(WidgetsResponse::class.java)
    }

    val envelopeAdapter: JsonAdapter<WidgetEnvelope> by lazy {
        moshi.adapter(WidgetEnvelope::class.java)
    }
}
