package com.phonecompanion.app.notify

import android.content.Context
import android.util.Log
import com.phonecompanion.app.data.ApiClient
import com.phonecompanion.app.data.CommandsAckRequest
import com.phonecompanion.app.data.SecureStorage
import com.phonecompanion.app.data.ShellResultRequest
import kotlinx.coroutines.CancellationException

/**
 * Poll-triggered install commands (poll-delivered install path; FCM is
 * only a silent wake hint that triggers this same poll immediately).
 *
 * Runs inside the existing poll cycle ([com.phonecompanion.app.work.WidgetPoller.refresh]
 * — same worker/cadence as the widget poll, no new periodic work): each
 * poll GETs `/api/v1/outbox` with the device Bearer token, then every
 * fresh command id immediately starts its auto-download
 * ([AppInstallAutoDownload]: progress notification first, download →
 * verify → grant-gate → commit detached from the worker), then acks
 * resolved command ids via `POST /api/v1/commands/ack` (per-device
 * `command_id`s) so they are not re-offered.
 *
 * Idempotence story: resolved command ids persist in a plain-prefs set
 * ([AppInstallPostedStore], pruned). If the app dies before acking, the
 * next poll re-offers and the persisted set suppresses a duplicate
 * download (the re-offered id is still acked — a harmless no-op
 * server-side). Ack failures likewise retry on the next poll. Unknown
 * kinds are acked-and-ignored (never downloaded) so the server stops
 * re-offering them.
 *
 * Auth failures here (401/403) only skip the commands step with a
 * shape-only log line — revocation policy belongs to the widget poll,
 * which owns the credential wipe. Nothing here touches widgets (no
 * Glance, no cache writes): this step starts downloads and posts
 * notifications only.
 * Cancellation is always rethrown.
 */
object AppInstallCommandPoll {

    internal const val TAG = "AppInstallCommands"

    /**
     * Silent server wake type (frozen cross-boundary contract with the
     * backend's app-push wake hint): a data message of this type carries
     * no user content — it only tells the app to poll the outbox NOW.
     * Older builds without the intercept render it generically (accepted
     * degradation: one odd notification instead of instant delivery).
     */
    const val WAKE_TYPE = "app_push_wake"

    /** True when an FCM data map is the app-push wake (never rendered). */
    fun isWake(data: Map<String, String>): Boolean =
        data["type"]?.trim() == WAKE_TYPE

    /** One commands-fetch outcome, mapped from Retrofit/transport. */
    internal sealed interface FetchOutcome {
        data class Success(val parsed: AppInstallCommands.ParsedCommands) : FetchOutcome
        data class HttpError(val code: Int) : FetchOutcome
        data object TransportFailure : FetchOutcome
    }

    /** One executed shell command (already decoded; bounded by the service). */
    internal data class ShellExecution(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val truncated: Boolean,
    )

    /** Collaborators for [pollOnceWith]; the Android [pollOnce] wires the real edges. */
    internal interface Collaborators {
        suspend fun fetch(): FetchOutcome

        /**
         * Starts the auto-download for one fresh payload (purge stale
         * APKs, post progress, offer the foreground dialog, launch the
         * detached transfer).
         */
        suspend fun startDownload(payload: AppInstallPayload)

        /**
         * Executes one operator shell command through Shizuku. Never
         * throws: a missing grant or transport failure is reported as a
         * non-zero exit with a shape-only stderr reason.
         */
        suspend fun executeShell(command: AppInstallCommands.ShellCommand): ShellExecution =
            ShellExecution(-1, "", "shell_unsupported", false)

        /** Uploads one shell result; true when the server accepted it. */
        suspend fun postShellResult(
            commandId: String,
            execution: ShellExecution,
        ): Boolean = false

        /** Acks started command ids; true when the server accepted. */
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
     * Android entry: fetch commands, auto-download fresh ids, ack started ids.
     * Best-effort — never throws except [CancellationException] (rethrown).
     * Skips silently when unpaired (no credentials, no status change).
     */
    suspend fun pollOnce(context: Context) {
        val app = context.applicationContext
        val storage = SecureStorage.getInstance(app)
        val baseUrl = storage.baseUrl
        val token = storage.deviceToken
        if (baseUrl.isNullOrBlank() || token.isNullOrBlank()) return
        // Bounded retention sweep: abandoned/superseded APKs (cache and the
        // Shizuku staged dir) age out on every poll, not just when a new
        // command arrives. Never deletes a file a live flow still uses.
        AppInstallFiles.sweepStale(app)
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

            override suspend fun startDownload(payload: AppInstallPayload) {
                // Drop superseded/abandoned APKs before starting so a
                // stale file never sits next to this command's download —
                // cache and the staged dir alike.
                AppInstallFiles.purgeStaleCache(app, payload.pushId)
                AppInstallFiles.purgeStaleStaged(app, payload.pushId)
                val attempt = AppInstallSessions.startAttempt(payload.pushId)
                AppInstallAutoDownload.begin(app, payload, attempt)
            }

            override suspend fun executeShell(
                command: AppInstallCommands.ShellCommand,
            ): ShellExecution {
                return when (val r = ShizukuRemoteShell.runOnce(app, command.command, command.timeoutMs)) {
                    is ShizukuRemoteShell.Result.Done -> ShellExecution(
                        r.outcome.exitCode,
                        r.outcome.stdout,
                        r.outcome.stderr,
                        r.outcome.truncated,
                    )
                    ShizukuRemoteShell.Result.NotGranted ->
                        ShellExecution(-1, "", "shizuku_not_granted", false)
                    ShizukuRemoteShell.Result.TransportFailure ->
                        ShellExecution(-1, "", "shell_transport_failure", false)
                }
            }

            override suspend fun postShellResult(
                commandId: String,
                execution: ShellExecution,
            ): Boolean {
                return try {
                    val service = ApiClient.create(baseUrl) { storage.deviceToken }
                    service.postShellResult(
                        ShellResultRequest(
                            commandId = commandId,
                            exitCode = execution.exitCode,
                            stdout = execution.stdout,
                            stderr = execution.stderr,
                            truncated = execution.truncated,
                        ),
                    ).isSuccessful
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
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
     * Testable commands-poll core: starts the download for every valid
     * payload not already resolved, then acks every valid offered command
     * id already resolved
     * (this round or an earlier one — the ack is idempotent, so failed
     * acks converge on the next poll) plus every unknown-kind command id
     * (acked-and-ignored, never downloaded). A start failure skips that id
     * for both the posted set and the ack (the server re-offers it next
     * poll). Cancellation is always rethrown.
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
        // Dupes suppressed: already resolved before this round (keyed by
        // per-device command id — the same id the ack body carries).
        val skipped = parsed.valid.count { posted.isPosted(it.commandId) }
        var postedCount = 0
        for (payload in parsed.valid) {
            if (posted.isPosted(payload.commandId)) continue
            try {
                deps.startDownload(payload)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Shape only (counts + class): never ids, URLs, or hashes.
                logInfo("commands poll download-start-failed error=${e.javaClass.simpleName}")
                continue
            }
            posted.markPosted(listOf(payload.commandId))
            postedCount++
        }
        // Remote shell: execute each command once, report its result, and
        // ack it. The ack is independent of the result upload — a shell
        // command is not idempotent (`input tap`, `settings put`), so
        // re-offering it would repeat a side effect. If the upload fails
        // the operator sees a pending command and can re-issue knowingly.
        var shellResults = 0
        for (command in parsed.shell) {
            if (posted.isPosted(command.commandId)) continue
            // At-most-once: mark before executing. A shell command is not
            // idempotent (`rm`, `input tap`), so a process death between
            // execution and ack must NOT re-run it; the operator sees a
            // pending command and can re-issue knowingly instead.
            posted.markPosted(listOf(command.commandId))
            val execution = try {
                deps.executeShell(command)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ShellExecution(-1, "", "shell_exec_failed", false)
            }
            val uploaded = try {
                deps.postShellResult(command.commandId, execution)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (uploaded) shellResults++
        }
        // Ack every valid offered id already resolved (this round or an
        // earlier one): the ack is idempotent, so failed acks converge
        // on the next poll. Never-resolved ids (download failed to start)
        // are left for the server to re-offer. Unknown kinds are
        // acked-and-ignored (never downloaded) so the server stops
        // re-offering them.
        val ackIds = (parsed.valid.map { it.commandId }.filter { posted.isPosted(it) } +
            parsed.shell.map { it.commandId }.filter { posted.isPosted(it) } +
            parsed.unknownIds).distinct()
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
                "shell=${parsed.shell.size} shellResults=$shellResults " +
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
