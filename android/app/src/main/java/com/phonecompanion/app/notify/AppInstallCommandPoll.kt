package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import com.phonecompanion.app.data.ApiClient
import com.phonecompanion.app.data.CommandsAckRequest
import com.phonecompanion.app.data.SecureStorage
import kotlinx.coroutines.CancellationException

/**
 * Poll-triggered install commands (FCM-free install path).
 *
 * Runs inside the existing poll cycle ([com.phonecompanion.app.work.WidgetPoller.refresh]
 * — same worker/cadence as the widget poll, no new periodic work): each
 * poll GETs `/api/v1/commands` with the device Bearer token, posts the
 * SAME local notification flow as the install prompt
 * ([AppInstallNotifications.notifyPrompt] on the shared general channel;
 * tap opens the pre-install UI), then acks prompted ids via
 * `POST /api/v1/commands/ack` so they are not re-offered.
 *
 * Idempotence story: prompted ids persist in a plain-prefs set
 * ([AppInstallPostedStore], pruned). If the app dies before acking, the
 * next poll re-offers and the persisted set suppresses a duplicate
 * notification (the re-offered id is still acked — a harmless no-op
 * server-side). Ack failures likewise retry on the next poll.
 *
 * Auth failures here (401/403) only skip the commands step with a
 * shape-only log line — revocation policy belongs to the widget poll,
 * which owns the credential wipe. Nothing here touches widgets (no
 * Glance, no cache writes): this step posts notifications only.
 * Cancellation is always rethrown.
 */
object AppInstallCommandPoll {

    internal const val TAG = "AppInstallCommands"

    /** One commands-fetch outcome, mapped from Retrofit/transport. */
    internal sealed interface FetchOutcome {
        data class Success(val parsed: AppInstallCommands.ParsedCommands) : FetchOutcome
        data class HttpError(val code: Int) : FetchOutcome
        data object TransportFailure : FetchOutcome
    }

    /** Collaborators for [pollOnceWith]; the Android [pollOnce] wires the real edges. */
    internal interface Collaborators {
        suspend fun fetch(): FetchOutcome

        /**
         * Posts the install prompt for one fresh payload (purge stale
         * APKs, then the prompt notification).
         */
        suspend fun postPrompt(payload: AppInstallPayload)

        /** Acks prompted push ids; true when the server accepted. */
        suspend fun ack(ids: List<String>): Boolean
    }

    /** One commands-poll outcome (counts only, never ids/content). */
    internal data class PollResult(
        val offered: Int,
        val posted: Int,
        val skipped: Int,
        val unknownKinds: Int,
        val malformed: Int,
        val acked: Boolean,
    )

    /**
     * Android entry: fetch commands, prompt fresh ids, ack prompted ids.
     * Best-effort — never throws except [CancellationException] (rethrown).
     * Skips silently when unpaired (no credentials, no status change).
     */
    suspend fun pollOnce(context: Context) {
        val app = context.applicationContext
        val storage = SecureStorage.getInstance(app)
        val baseUrl = storage.baseUrl
        val token = storage.deviceToken
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) return
        val posted: AppInstallPostedStore = PrefsAppInstallPostedStore(app)
        val deps = object : Collaborators {
            override suspend fun fetch(): FetchOutcome {
                return try {
                    val service = ApiClient.create(baseUrl) { storage.deviceToken }
                    val response = service.getCommandsRaw()
                    if (!response.isSuccessful) {
                        FetchOutcome.HttpError(response.code())
                    } else {
                        val raw = try {
                            response.body()?.string().orEmpty()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.i(TAG, "commands poll body-read-failure error=${e.javaClass.simpleName}")
                            return FetchOutcome.TransportFailure
                        }
                        val parsed = AppInstallCommands.parseResponse(raw)
                        if (parsed.syntaxError) {
                            Log.i(
                                TAG,
                                "commands poll parse-failure " +
                                    "bodyBytes=${raw.toByteArray(Charsets.UTF_8).size}",
                            )
                            FetchOutcome.TransportFailure
                        } else {
                            FetchOutcome.Success(parsed)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    FetchOutcome.TransportFailure
                }
            }

            override suspend fun postPrompt(payload: AppInstallPayload) {
                // Drop superseded/abandoned APKs before prompting so a
                // stale file never sits next to this command's download.
                AppInstallFiles.purgeStaleCache(app, payload.pushId)
                AppInstallNotifications.notifyPrompt(app, payload)
            }

            override suspend fun ack(ids: List<String>): Boolean {
                return try {
                    val service = ApiClient.create(baseUrl) { storage.deviceToken }
                    service.ackCommands(CommandsAckRequest(ids)).isSuccessful
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
            }
        }
        try {
            pollOnceWith(deps, posted) { Log.i(TAG, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(TAG, "commands poll aborted error=${e.javaClass.simpleName}")
        }
    }

    /**
     * Testable commands-poll core: prompts every valid payload not already
     * posted, then acks every valid offered id already prompted (this
     * round or an earlier one — the ack is idempotent, so failed acks
     * converge on the next poll). A prompt failure skips that id for both
     * the posted set and the ack (the server re-offers it next poll).
     * Cancellation is always rethrown.
     */
    internal suspend fun pollOnceWith(
        deps: Collaborators,
        posted: AppInstallPostedStore,
        logInfo: (String) -> Unit,
    ): PollResult {
        val outcome = try {
            deps.fetch()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FetchOutcome.TransportFailure
        }
        if (outcome !is FetchOutcome.Success) {
            val reason = when (outcome) {
                is FetchOutcome.HttpError -> "httpCode=${outcome.code}"
                FetchOutcome.TransportFailure -> "httpCode=none result=network-error"
                else -> "httpCode=none result=network-error"
            }
            logInfo("commands poll $reason offered=0 posted=0 skipped=0")
            return PollResult(0, 0, 0, 0, 0, acked = true)
        }
        val parsed = outcome.parsed
        // Dupes suppressed: already prompted before this round.
        val skipped = parsed.valid.count { posted.isPosted(it.pushId) }
        var postedCount = 0
        for (payload in parsed.valid) {
            if (posted.isPosted(payload.pushId)) continue
            try {
                deps.postPrompt(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shape only (counts + class): never ids, URLs, or hashes.
                logInfo("commands poll prompt-failed error=${e.javaClass.simpleName}")
                continue
            }
            posted.markPosted(listOf(payload.pushId))
            postedCount++
        }
        // Ack every valid offered id already prompted (this round or an
        // earlier one): the ack is idempotent, so failed acks converge
        // on the next poll. Never-acked ids (prompt failed) are left
        // for the server to re-offer.
        val ackIds = parsed.valid.map { it.pushId }.filter { posted.isPosted(it) }
        val acked = if (ackIds.isEmpty()) {
            true
        } else {
            try {
                deps.ack(ackIds)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }
        logInfo(
            "commands poll result=ok offered=${parsed.valid.size} " +
                "posted=$postedCount skipped=$skipped " +
                "unknown=${parsed.unknownKinds} malformed=${parsed.malformed} " +
                "acked=$acked",
        )
        return PollResult(
            offered = parsed.valid.size,
            posted = postedCount,
            skipped = skipped,
            unknownKinds = parsed.unknownKinds,
            malformed = parsed.malformed,
            acked = acked,
        )
    }
}
