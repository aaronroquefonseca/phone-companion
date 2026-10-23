package com.phonecompanion.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * FCM registration-token backfill (Wave-2 push).
 *
 * The server row holds the token the app last reported at enroll time —
 * which may be none (paired before push was configured, or fetch failed)
 * or stale (FCM rotated it via `onNewToken`). Two triggers converge here:
 *
 * - [CompanionMessagingService.onNewToken]: persist + immediate upload
 *   when paired (best-effort; the service may die first).
 * - [com.phonecompanion.app.work.WidgetPoller.refresh]: opportunistic
 *   upload after every successful poll, so any row that missed the
 *   immediate upload heals on the next poll without user action.
 *
 * The endpoint is idempotent (the server no-ops when the stored token
 * already matches), so redundant uploads are cheap. Every failure path
 * returns false and never throws except [CancellationException], which
 * propagates — the next poll retries.
 */
object FcmTokenBackfill {

    /**
     * Uploads the locally stored FCM token when this device is paired and
     * a token is available. No-op otherwise. Never throws (except
     * cancellation).
     */
    suspend fun maybeBackfill(context: Context) {
        val storage = try {
            SecureStorage.getInstance(context)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        val baseUrl = storage.baseUrl
        val bearer = storage.deviceToken
        val fcmToken = storage.fcmToken
        if (baseUrl.isNullOrBlank() || bearer.isNullOrBlank() || fcmToken.isNullOrBlank()) return
        val service = try {
            ApiClient.create(baseUrl) { bearer }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        upload(service, fcmToken)
    }

    /**
     * POSTs one token update. Returns true on HTTP 2xx, false on any
     * rejection or transport failure. Test seam: pure in, no Context.
     */
    internal suspend fun upload(service: ApiService, fcmToken: String): Boolean {
        return try {
            service.updateFcmToken(FcmTokenUpdateRequest(fcmToken)).isSuccessful
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
