package com.phonecompanion.app.data

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException

/**
 * Shared Moshi JSON adapter for widget payload maps (P7).
 *
 * Single owner for the `Map<String, Any?>` adapter used by both the poll
 * path ([com.phonecompanion.app.work.WidgetPoller]) and the parse path
 * ([WidgetPayloadParser]): one Moshi instance, one adapter, no behavior
 * change. Payloads stay length-bounded at the parser; this helper only
 * serializes/deserializes the raw envelope map. Cancellation is always
 * rethrown, never converted to a fallback.
 */
object WidgetJson {
    val moshi: Moshi by lazy { Moshi.Builder().build() }

    val mapAdapter: JsonAdapter<Map<String, Any?>> by lazy {
        moshi.adapter(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )
    }

    /**
     * Serializes a widgets-poll payload map for the cache.
     *
     * A null payload (the wire format allows a missing `payload`) is a
     * programmer error at the call site: caching it as `"{}"` poisons the
     * row — `getAll()` looks populated while every per-type parse yields
     * null and the widget falls back with no log or UI signal. Throw so
     * the poll path keeps the last good cache instead of clobbering it
     * with an unparseable row. Serialization failure of a non-null map
     * throws the Moshi exception for the same reason.
     */
    fun toJson(payload: Map<String, Any?>?): String {
        requireNotNull(payload) { "refusing to cache a null payload as \"{}\"" }
        return mapAdapter.toJson(payload) ?: error("payload serialization returned null")
    }

    /** Raw payload object, or null when [payloadJson] is blank/malformed. */
    fun fromJson(payloadJson: String): Map<String, Any?>? {
        if (payloadJson.isBlank()) return null
        return try {
            mapAdapter.fromJson(payloadJson)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
