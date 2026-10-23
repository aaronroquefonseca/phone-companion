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
     * Serializes a widgets-poll payload map for the cache. Throws on
     * serialization failure (never stores a fallback): the poll path keeps
     * the last cache on error instead of clobbering it with "{}".
     */
    fun toJson(payload: Map<String, Any?>?): String {
        if (payload == null) return "{}"
        return mapAdapter.toJson(payload) ?: "{}"
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
