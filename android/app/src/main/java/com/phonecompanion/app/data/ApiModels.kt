package com.phonecompanion.app.data

import com.squareup.moshi.Json

/**
 * Wire models for docs/api-contract.md v1.
 *
 * Field names match the contract's snake_case JSON. This file owns the
 * enroll/widgets/notify/commands *shapes*; widget rendering and caching
 * belong to the widgets PR (widgets/, work/), FCM receipt to notify/.
 */

// --- POST /api/v1/enroll ---

data class EnrollRequest(
    @Json(name = "enroll_token") val enrollToken: String,
    @Json(name = "device_name") val deviceName: String,
    // FCM registration token; null when push is not configured yet
    // (e.g. google-services.json absent in dev builds).
    @Json(name = "fcm_token") val fcmToken: String?,
)

data class EnrollResponse(
    @Json(name = "device_id") val deviceId: String,
    @Json(name = "device_token") val deviceToken: String,
    // Base64 256-bit per-device key, returned once. Persisted to encrypted
    // storage on receipt, never transmitted again, never logged.
    @Json(name = "device_key") val deviceKey: String,
)

// --- POST /api/v1/device/fcm-token ---

// FCM registration-token backfill (Wave-2 push). Sent with the device
// Bearer token after FCM rotation (onNewToken) and opportunistically on
// the next widgets poll when the local token is present but the server row
// may hold none (paired before push was configured) or a stale one.
data class FcmTokenUpdateRequest(
    @Json(name = "fcm_token") val fcmToken: String,
)

data class FcmTokenUpdateResponse(
    @Json(name = "updated") val updated: Boolean = false,
)

// --- GET /api/v1/widgets ---

data class WidgetsResponse(
    @Json(name = "widgets") val widgets: List<WidgetEnvelope> = emptyList(),
)

/**
 * Generic widget envelope. The widgets PR owns per-type parsing/rendering;
 * the foundation layer keeps [payload] as an untyped map so unknown types
 * and future fields degrade gracefully instead of crashing.
 */
data class WidgetEnvelope(
    @Json(name = "id") val id: String,
    @Json(name = "type") val type: String,
    @Json(name = "title") val title: String,
    @Json(name = "updated_at") val updatedAt: String,
    @Json(name = "payload") val payload: Map<String, Any?>? = null,
    // Present only for `secret`; kid of the per-device key that encrypted it.
    @Json(name = "secret_ref") val secretRef: String? = null,
)

// --- GET /api/v1/outbox + POST /api/v1/commands/ack ---

// Poll-triggered command queue (FCM-free install path). The app polls
// GET with the device Bearer token; each entry is an untyped string map
// on the wire (see AppInstallCommands: unknown kinds acked-and-ignored,
// malformed app_install entries skipped with shape-only logging). The ack
// body lists the per-device command ids the app has resolved (prompted
// valid entries plus ignored unknown kinds); the server treats it as
// idempotent (unknown/foreign ids silently ignored, re-acks are harmless
// no-ops).
data class CommandsAckRequest(
    @Json(name = "ids") val ids: List<String> = emptyList(),
)

// --- Error bodies: {"error": "<code>"} ---

data class ErrorBody(
    @Json(name = "error") val error: String? = null,
)
