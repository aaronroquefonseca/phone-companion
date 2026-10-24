package com.phonecompanion.app.work

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.phonecompanion.app.data.ApiClient
import com.phonecompanion.app.data.AppMoshi
import com.phonecompanion.app.data.DatabaseHolder
import com.phonecompanion.app.data.FcmTokenBackfill
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.WidgetCacheEntity
import com.phonecompanion.app.data.WidgetEnvelope
import com.phonecompanion.app.data.WidgetJson
import com.phonecompanion.app.widgets.ListWidget
import com.phonecompanion.app.widgets.SecretWidget
import com.phonecompanion.app.widgets.StatusWidget
import com.phonecompanion.app.widgets.TextWidget
import com.phonecompanion.app.widgets.WidgetBindingResolver
import com.phonecompanion.app.widgets.glanceKey
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
 * Revocation policy ([PollAuthPolicy]): HTTP 401/403 increments a
 * *persisted consecutive-auth-failure streak* (plain pref, not encrypted
 * storage). A single 401/403 only records `auth-failed-attempt-1-of-2`
 * and keeps credentials + cache — one mismatched poll used to wipe a
 * physical tablet permanently. The wipe (forget credentials, drop the
 * widget cache, push fail-closed states) runs only after
 * [PollAuthPolicy.MAX_CONSECUTIVE_AUTH_FAILURES] consecutive auth
 * failures, so genuine revocation still converges fast (two polls) and
 * stays fail-closed until re-pair. Any successful poll resets the streak;
 * non-auth failures (network errors, other HTTP codes) never increment it
 * and keep the last cache.
 *
 * Every poll records a [PollStatus] (outcome + HTTP code + timestamp,
 * never tokens/bodies) and logs the HTTP code at INFO for field
 * diagnosis.
 */
object WidgetPoller {

    internal const val TAG = "WidgetPoller"

    /**
     * Serializes a widgets-poll payload map for the cache. Throws on
     * serialization failure so [pollOnce] keeps the last cache instead of
     * upserting a fallback.
     */
    internal fun payloadToJson(payload: Map<String, Any?>?): String = WidgetJson.toJson(payload)

    suspend fun refresh(context: Context) {
        if (pollOnce(context)) {
            // Wiped after N consecutive auth failures: fail-closed states
            // already pushed by handleRevoked.
            return
        }
        // Push backfill: a device that paired before FCM was configured
        // (or whose token rotated while the immediate upload failed) heals
        // its server row here, on the next successful poll. Best-effort and
        // silent — it never fails the poll or touches the widget push.
        try {
            FcmTokenBackfill.maybeBackfill(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Transport/storage failure: the next poll retries.
        }
        updateAllWidgets(context)
    }

    /** One poll fetch outcome, mapped from Retrofit/transport by the caller. */
    internal sealed interface PollFetchOutcome {
        data class Success(val widgets: List<WidgetEnvelope>) : PollFetchOutcome
        data class HttpError(val code: Int) : PollFetchOutcome
        data object TransportFailure : PollFetchOutcome
    }

    /**
     * Collaborators for [pollOnceWith]. The Android [pollOnce] wires the
     * real SecureStorage/Room/Glance edges; tests inject fakes.
     */
    internal interface PollCollaborators {
        suspend fun fetch(): PollFetchOutcome

        /**
         * Upserts one poll batch. Returns the row count written. An empty
         * batch writes nothing and returns 0 (never wipes the cache).
         */
        suspend fun saveWidgets(widgets: List<WidgetEnvelope>): Int
        suspend fun clearCache()
        suspend fun clearCredentials()
        suspend fun pushFailClosed()
    }

    /**
     * One fetch + cache cycle.
     *
     * @return true when credentials were wiped after reaching the
     * consecutive-auth-failure threshold (handled: credentials cleared,
     * cache dropped, fail-closed widgets pushed); false otherwise. A
     * single 401/403 returns false and keeps credentials + cache.
     */
    internal suspend fun pollOnce(context: Context): Boolean {
        val app = context.applicationContext
        val storage = SecureStorage.getInstance(app)
        val state: PollStateStore = SharedPrefsPollStateStore(app)
        val baseUrl = storage.baseUrl
        val token = storage.deviceToken
        val deps = object : PollCollaborators {
            override suspend fun fetch(): PollFetchOutcome {
                return try {
                    val service = ApiClient.create(baseUrl ?: "") { storage.deviceToken }
                    val response = service.getWidgetsRaw()
                    if (!response.isSuccessful) {
                        PollFetchOutcome.HttpError(response.code())
                    } else {
                        // Raw body so a 200-with-garbage parse failure can
                        // log the byte length (shape only, never content):
                        // the old Moshi-converter path swallowed the body
                        // and degraded to a silent TransportFailure.
                        val raw = try {
                            response.body()?.string().orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.i(TAG, "widget poll body-read-failure error=${e.javaClass.simpleName}")
                            return PollFetchOutcome.TransportFailure
                        }
                        try {
                            val parsed = AppMoshi.widgetsResponseAdapter.fromJson(raw)
                            PollFetchOutcome.Success(parsed?.widgets.orEmpty())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.i(
                                TAG,
                                "widget poll parse-failure " +
                                    "bodyBytes=${raw.toByteArray(Charsets.UTF_8).size} " +
                                    "error=${e.javaClass.simpleName}",
                            )
                            PollFetchOutcome.TransportFailure
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PollFetchOutcome.TransportFailure
                }
            }

            override suspend fun saveWidgets(widgets: List<WidgetEnvelope>): Int {
                // Empty poll must never wipe the cache: Room @Insert with an
                // empty list issues no statements (verified semantics), but
                // guard explicitly so a future DAO change cannot turn
                // "server sent zero widgets" into "cached widgets vanished".
                // Envelopes with a null payload are dropped before the DAO
                // for the same reason: caching one as "{}" poisons the row
                // (per-type parses yield null while getAll looks populated,
                // i.e. the all-fallback symptom). Dropped envelopes keep
                // their last cached row; counts only, never ids/titles.
                val batch = toCacheEntities(widgets)
                if (batch.dropped > 0) {
                    Log.d(TAG, "widget poll drop-null-payload dropped=${batch.dropped} kept=${batch.kept.size}")
                }
                if (batch.kept.isEmpty()) return 0
                DatabaseHolder.get(app).widgetCacheDao().upsertAll(batch.kept)
                return batch.kept.size
            }

            override suspend fun clearCache() {
                try {
                    DatabaseHolder.get(app).widgetCacheDao().clearAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cache drop is best-effort; credentials are already forgotten.
                }
            }

            override suspend fun clearCredentials() {
                storage.clearCredentials()
            }

            override suspend fun pushFailClosed() {
                updateAllWidgets(app)
            }
        }
        return pollOnceWith(
            deps = deps,
            state = state,
            hasCredentials = !baseUrl.isNullOrBlank() && !token.isNullOrBlank(),
            nowMs = System::currentTimeMillis,
            logInfo = { Log.i(TAG, it) },
        )
    }

    /**
     * Testable poll core: applies [PollAuthPolicy] to one [PollFetchOutcome].
     *
     * Success resets the streak and upserts the cache. An auth failure
     * (401/403) increments the persisted streak and wipes only at the
     * threshold. Non-auth failures never touch the streak and keep the
     * cache. Every path persists a [PollStatus] and logs the HTTP code at
     * INFO (codes/outcomes only — never tokens or bodies). Cancellation is
     * always rethrown.
     *
     * @return true iff the wipe ran (credentials cleared, cache dropped,
     * fail-closed pushed); false otherwise.
     */
    internal suspend fun pollOnceWith(
        deps: PollCollaborators,
        state: PollStateStore,
        hasCredentials: Boolean,
        nowMs: () -> Long,
        logInfo: (String) -> Unit,
    ): Boolean {
        if (!hasCredentials) {
            state.lastStatus = PollStatus(
                result = PollResult.NO_CREDENTIALS,
                httpCode = null,
                timestampMs = nowMs(),
                consecutiveAuthFailures = state.consecutiveAuthFailures,
            )
            logInfo("widget poll httpCode=none result=not-paired")
            return false
        }
        val outcome = try {
            deps.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PollFetchOutcome.TransportFailure
        }
        return when (outcome) {
            is PollFetchOutcome.Success -> {
                // An empty batch skips the DAO entirely (see the saveWidgets
                // guard): the last cache survives a zero-widget poll.
                val upserted = if (outcome.widgets.isEmpty()) {
                    0
                } else {
                    try {
                        deps.saveWidgets(outcome.widgets)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Cache write failed: keep the last cache, streak untouched.
                        state.lastStatus = PollStatus(
                            result = PollResult.NON_AUTH_ERROR,
                            httpCode = null,
                            timestampMs = nowMs(),
                            consecutiveAuthFailures = state.consecutiveAuthFailures,
                        )
                        logInfo("widget poll httpCode=none result=network-error")
                        return false
                    }
                }
                state.consecutiveAuthFailures = 0
                state.lastStatus = PollStatus(
                    result = PollResult.OK,
                    httpCode = 200,
                    timestampMs = nowMs(),
                    consecutiveAuthFailures = 0,
                )
                // Shape only (counts, never ids/titles/bodies): distinguishes
                // "parsed 4, cached 4" from "parsed 0" in field reports of
                // the all-fallback symptom.
                logInfo("widget poll httpCode=200 result=ok parsed=${outcome.widgets.size} upserted=$upserted")
                false
            }
            is PollFetchOutcome.HttpError -> {
                if (PollAuthPolicy.isAuthFailureCode(outcome.code)) {
                    val streak = PollAuthPolicy.nextConsecutiveAuthFailures(
                        current = state.consecutiveAuthFailures,
                        success = false,
                        authFailure = true,
                    )
                    state.consecutiveAuthFailures = streak
                    state.lastStatus = PollStatus(
                        result = PollResult.AUTH_FAILED,
                        httpCode = outcome.code,
                        timestampMs = nowMs(),
                        consecutiveAuthFailures = streak,
                    )
                    logInfo(
                        "widget poll httpCode=${outcome.code} " +
                            "result=auth-failed-attempt-$streak-of-" +
                            PollAuthPolicy.MAX_CONSECUTIVE_AUTH_FAILURES,
                    )
                    if (!PollAuthPolicy.shouldWipe(streak)) return false
                    handleRevoked(deps, state)
                    true
                } else {
                    // Other HTTP errors: keep the last cache (fail-closed
                    // renderers cover the stale state until the next poll).
                    state.lastStatus = PollStatus(
                        result = PollResult.NON_AUTH_ERROR,
                        httpCode = outcome.code,
                        timestampMs = nowMs(),
                        consecutiveAuthFailures = state.consecutiveAuthFailures,
                    )
                    logInfo("widget poll httpCode=${outcome.code} result=network-error")
                    false
                }
            }
            PollFetchOutcome.TransportFailure -> {
                // Transport failure: keep the last cache, streak untouched.
                state.lastStatus = PollStatus(
                    result = PollResult.NON_AUTH_ERROR,
                    httpCode = null,
                    timestampMs = nowMs(),
                    consecutiveAuthFailures = state.consecutiveAuthFailures,
                )
                logInfo("widget poll httpCode=none result=network-error")
                false
            }
        }
    }

    /**
     * Post-threshold reset: forget local credentials, drop the widget
     * cache, and push the fail-closed widget states (secret widgets render
     * MASKED once the key is gone). Resets the auth-failure streak so a
     * later re-pair starts clean.
     */
    internal suspend fun handleRevoked(context: Context) {
        val app = context.applicationContext
        val storage = SecureStorage.getInstance(app)
        val deps = object : PollCollaborators {
            override suspend fun fetch(): PollFetchOutcome = PollFetchOutcome.TransportFailure
            override suspend fun saveWidgets(widgets: List<WidgetEnvelope>): Int = 0
            override suspend fun clearCache() {
                try {
                    DatabaseHolder.get(app).widgetCacheDao().clearAll()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Cache drop is best-effort; credentials are already forgotten.
                }
            }

            override suspend fun clearCredentials() {
                storage.clearCredentials()
            }

            override suspend fun pushFailClosed() {
                updateAllWidgets(app)
            }
        }
        handleRevoked(deps, SharedPrefsPollStateStore(app))
    }

    /** Wipe via collaborators (single path for the Android edge and tests). */
    internal suspend fun handleRevoked(deps: PollCollaborators, state: PollStateStore) {
        deps.clearCredentials()
        deps.clearCache()
        state.consecutiveAuthFailures = 0
        deps.pushFailClosed()
    }

    /** Revoked/unknown credential codes per the api-contract (401/403). */
    fun isRevokedHttpCode(code: Int): Boolean = PollAuthPolicy.isAuthFailureCode(code)

    internal fun toCacheEntity(envelope: WidgetEnvelope): WidgetCacheEntity = WidgetCacheEntity(
        id = envelope.id,
        type = envelope.type,
        title = envelope.title,
        updatedAt = envelope.updatedAt,
        payloadJson = payloadToJson(envelope.payload),
        secretRef = envelope.secretRef,
    )

    /**
     * One poll batch split for the DAO: envelopes with a null payload are
     * dropped (never cached as `"{}"` poison — see [WidgetJson.toJson]),
     * everything else maps via [toCacheEntity]. Pure and JVM-testable; the
     * Android [PollCollaborators.saveWidgets] upserts [CacheBatch.kept] and
     * logs [CacheBatch.dropped] (count only, never ids/titles/bodies).
     */
    internal data class CacheBatch(
        val kept: List<WidgetCacheEntity>,
        val dropped: Int,
    )

    internal fun toCacheEntities(widgets: List<WidgetEnvelope>): CacheBatch {
        val kept = ArrayList<WidgetCacheEntity>(widgets.size)
        var dropped = 0
        for (envelope in widgets) {
            if (envelope.payload == null) {
                dropped++
                continue
            }
            kept.add(toCacheEntity(envelope))
        }
        return CacheBatch(kept, dropped)
    }

    /**
     * Push cached state to each per-type widget. No fully-generic renderer.
     *
     * [supervisorScope] with one child per type: a failing type cannot cancel
     * its siblings, while parent cancellation still propagates to every child
     * (no detached jobs, nothing keeps running after the caller is gone).
     * One try/catch per instance id so a broken instance cannot fail its type
     * batch. Cancellation is always rethrown. Failures log the exception
     * class at debug (shape only — never ids, titles, bodies, or keys): the
     * previous zero-log swallows left stuck-fallback states undiagnosable.
     */
    suspend fun updateAllWidgets(context: Context) = supervisorScope {
        listOf(
            async { updateAll(context, TextWidget(), TextWidget.PROVIDER) },
            async { updateAll(context, SecretWidget(), SecretWidget.PROVIDER) },
            async { updateAll(context, ListWidget(), ListWidget.PROVIDER) },
            async { updateAll(context, StatusWidget(), StatusWidget.PROVIDER) },
        ).awaitAll()
    }

    private suspend inline fun <reified T : GlanceAppWidget> updateAll(
        context: Context,
        widget: T,
        provider: String,
    ) {
        val ids = try {
            GlanceAppWidgetManager(context).getGlanceIds(T::class.java)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "widget push lookup-failed provider=${T::class.java.simpleName} error=${e.javaClass.simpleName}")
            return
        }
        pruneStaleBindings(context, provider, ids.mapTo(HashSet(ids.size)) { glanceKey(it) })
        for (id in ids) {
            try {
                widget.update(context, id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One broken instance must not fail its siblings.
                Log.d(TAG, "widget push update-failed provider=${T::class.java.simpleName} error=${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * Drops bindings whose home-screen instance is gone. Runs inside the
     * [updateAllWidgets] enumeration, where the bound set is already in
     * hand; best-effort and silent on storage failure (the next push
     * retries). Cancellation is always rethrown.
     */
    internal suspend fun pruneStaleBindings(
        context: Context,
        provider: String,
        boundKeys: Set<String>,
    ) {
        val dao = try {
            DatabaseHolder.get(context).widgetBindingDao()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "widget bindings prune-skipped error=${e.javaClass.simpleName}")
            return
        }
        val stored = try {
            dao.getForProvider(provider)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "widget bindings prune-skipped error=${e.javaClass.simpleName}")
            return
        }
        val stale = WidgetBindingResolver.staleBindings(boundKeys, stored)
        for (binding in stale) {
            try {
                dao.delete(binding.providerClass, binding.glanceKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "widget bindings prune-skipped error=${e.javaClass.simpleName}")
                return
            }
        }
        // Count only (never keys/ids): distinguishes "nothing to prune"
        // from real churn in field reports of stale selections.
        if (stale.isNotEmpty()) {
            Log.d(TAG, "widget bindings pruned count=${stale.size}")
        }
    }
}
