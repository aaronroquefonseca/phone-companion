package com.phonecompanion.app.work

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.phonecompanion.app.data.ApiClient
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetEnvelope
import com.phonecompanion.app.data.WidgetJson
import com.phonecompanion.app.widgets.ListWidget
import com.phonecompanion.app.widgets.SecretWidget
import com.phonecompanion.app.widgets.StatusWidget
import com.phonecompanion.app.widgets.TextWidget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

/**
 * Shared generic polling plumbing (widgets PR / P7).
 *
 * Single code path used by both the hourly [RefreshWorker] and the manual
 * refresh entrypoint: fetch GET /api/v1/widgets with the device Bearer
 * token, upsert into the Room widget cache, then push it to every per-type
 * Glance widget. Per-type rendering lives in widgets/ — this file never
 * renders; it only fetches and fans out.
 *
 * Revocation: HTTP 401/403 means the credential is revoked (or unknown).
 * The poll then forgets local credentials, drops the widget cache, and
 * pushes the fail-closed widget states — a revoked phone shows
 * needs-refresh/empty states until it re-pairs. Any other failure keeps
 * the last cache.
 */
object WidgetPoller {

    /**
     * Serializes a widgets-poll payload map for the cache. Throws on
     * serialization failure so [pollOnce] keeps the last cache instead of
     * upserting a fallback.
     */
    internal fun payloadToJson(payload: Map<String, Any?>?): String = WidgetJson.toJson(payload)

    suspend fun refresh(context: Context) {
        if (pollOnce(context)) {
            // Revoked: fail-closed states already pushed by handleRevoked.
            return
        }
        updateAllWidgets(context)
    }

    /**
     * One fetch + cache cycle.
     *
     * @return true when the credential was revoked (handled: credentials
     * cleared, cache dropped, widgets pushed); false otherwise.
     */
    internal suspend fun pollOnce(context: Context): Boolean {
        val storage = SecureStorage.getInstance(context)
        val baseUrl = storage.baseUrl
        val token = storage.deviceToken
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) return false
        try {
            val service = ApiClient.create(baseUrl) { storage.deviceToken }
            val response = service.getWidgets()
            if (response.isSuccessful) {
                val widgets = response.body()?.widgets.orEmpty()
                DatabaseHolder.get(context).widgetCacheDao().upsertAll(widgets.map { toCacheEntity(it) })
                return false
            }
            if (isRevokedHttpCode(response.code())) {
                handleRevoked(context)
                return true
            }
            // Other HTTP errors: keep the last cache (fail-closed renderers
            // cover the stale state until the next poll).
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transport failure: keep the last cache.
            return false
        }
    }

    /**
     * Post-revoke reset: forget local credentials, drop the widget cache,
     * and push the fail-closed widget states (secret widgets render
     * MASKED once the key is gone).
     */
    internal suspend fun handleRevoked(context: Context) {
        SecureStorage.getInstance(context).clearCredentials()
        try {
            DatabaseHolder.get(context).widgetCacheDao().clearAll()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Cache drop is best-effort; credentials are already forgotten.
        }
        updateAllWidgets(context)
    }

    /** Revoked/unknown credential codes per the api-contract (401/403). */
    fun isRevokedHttpCode(code: Int): Boolean = code == 401 || code == 403

    internal fun toCacheEntity(envelope: WidgetEnvelope): WidgetCacheEntity = WidgetCacheEntity(
        id = envelope.id,
        type = envelope.type,
        title = envelope.title,
        updatedAt = envelope.updatedAt,
        payloadJson = payloadToJson(envelope.payload),
        secretRef = envelope.secretRef,
    )

    /**
     * Push cached state to each per-type widget. No fully-generic renderer.
     *
     * [supervisorScope] with one child per type: a failing type cannot cancel
     * its siblings, while parent cancellation still propagates to every child
     * (no detached jobs, nothing keeps running after the caller is gone).
     * One try/catch per instance id so a broken instance cannot fail its type
     * batch. Cancellation is always rethrown.
     */
    suspend fun updateAllWidgets(context: Context) = supervisorScope {
        listOf(
            async { updateAll(context, TextWidget()) },
            async { updateAll(context, SecretWidget()) },
            async { updateAll(context, ListWidget()) },
            async { updateAll(context, StatusWidget()) },
        ).awaitAll()
    }

    private suspend inline fun <reified T : GlanceAppWidget> updateAll(context: Context, widget: T) {
        val manager = GlanceAppWidgetManager(context)
        val ids = try {
            manager.getGlanceIds(T::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        for (id in ids) {
            try {
                widget.update(context, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One broken instance must not fail its siblings.
            }
        }
    }
}
