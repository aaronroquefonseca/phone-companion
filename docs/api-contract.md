# API contract (P0 BASE — frozen surface for later PRs)

Base URL: `https://<your-host>` (reverse proxy + TLS in front; the backend
itself serves plain HTTP on `$PORT`). All `/api/v1/*` request/response
bodies are JSON unless noted. Time fields are RFC 3339 UTC.

P0 BASE pins the shapes below. Stubs return `501 Not Implemented` until
their PR lands.

## Auth schemes

Two independent schemes; they are never interchangeable:

1. **WebUI session** — cookie-based. `POST /login` (WebUI PR) sets an
   `HttpOnly; Secure; SameSite=Lax` session cookie signed with
   `SESSION_SECRET`. Used by the browser WebUI: pairing QR page, device
   list, notify composer. Never sent by the Android app.
2. **Device token (Bearer)** — per-device opaque token issued at enroll,
   sent as `Authorization: Bearer <token>`. Used by the Android app for
   `GET /api/v1/widgets` (and future device calls). Tokens are individually
   revocable; the server stores only a hash (`token_hash`, see `db.go`).

3. **Operator API token (Bearer)** — machine credential for operator
   automation, sent as `Authorization: Bearer <token>`
   (`OPERATOR_API_TOKEN`). Accepted on the operator POST endpoints
   (`POST /api/v1/notify`, `POST /api/v1/app-push`) as an alternative to
   the WebUI session. It is never a device token: a live device Bearer on
   an operator endpoint is rejected with `403`.

Unauthenticated responses use `401` (missing/invalid credential) and `403`
(valid credential, not allowed). On the operator endpoints
(`POST /api/v1/notify`, `POST /api/v1/app-push`) any Bearer value other
than the operator token is `403` — only a missing credential is `401`. Error bodies: `{"error": "<code>"}` with a
stable machine-readable code plus HTTP status. No scheme substitutes for
another: a device token never authorizes operator endpoints, and a
session/token mix-up across surfaces fails closed.

## Endpoints

### `POST /api/v1/enroll` (pairing PR)

Enrolls a phone scanned from the WebUI pairing QR. Auth: short-lived,
single-use enroll token (QR payload) — NOT the admin session, NOT a device
token.

Request:

```json
{
  "enroll_token": "single-use-opaque-token",
  "device_name": "Pixel 8",
  "fcm_token": "fcm-registration-token"
}
```

Response `201`:

```json
{
  "device_id": "opaque-server-issued-id",
  "device_token": "raw-bearer-token-shown-once",
  "device_key": "base64-256-bit-per-device-key-shown-once"
}
```

The raw `device_token` is returned exactly once; the server keeps only its
hash. Errors: `410` expired/used enroll token. (Errata: the P0 draft's
`409 device already enrolled` is reserved, never emitted at v1 — re-pair
returns `201` with key rotation instead. Do not branch on `409`.)

### Key bootstrap at enrollment (pairing P2 / widgets P5 / notify P10)

So `secret` widgets are decryptable per device without rework, enrollment
also bootstraps a per-device encryption key (documentation-only contract
here; generation and storage land in the pairing PR):

- The server generates a random 256-bit key per device at enroll time and
  returns it once inside the `201` enroll response as `device_key`
  (base64, RFC 4648 §4 standard alphabet with `=` padding and no line
  breaks — the same base64 alphabet as the envelope fields below),
  delivered only over TLS alongside `device_token`.
- The server stores the key as `secret_key` on the device row; it is never
  logged, never returned by any other endpoint, and never embedded in
  widget payloads or notifications.
- The app persists `device_key` in encrypted storage on first receipt and
  never transmits it again. Loss of the app-side key = re-pair (see
  rotation below).
- Key identification: every encrypted field carries a `kid`
  (key id = `device_id` at v1). The app selects its stored key by `kid`
  and refuses to decrypt under any other key.
- Rotation on re-pair: re-enrolling a device (new QR scan) generates a
  fresh 256-bit key, overwrites `secret_key`, and invalidates ciphertexts
  encrypted under the old key. Payloads encrypted under a rotated-out key
  fail closed (widget shows "needs refresh", never plaintext, never a
  crash). Key history is NOT kept at v1.
- Rationale and at-rest storage choices are the threat-model doc's job
  (`docs/threat-model.md` stub); this section defines the wire contract so
  P2/P5/P10 implement compatibly.

### Token-issuance rate limit (additive note)

Minting enroll tokens — `POST /api/v1/enroll-tokens` and `POST
/webui/pair/mint` (the WebUI's Generate-token button) — shares one bucket:
30 per `RemoteAddr` per minute (in-memory, best-effort; a restart clears
it). `GET /webui/pair` never mints, so page loads cost nothing.
Exhaustion returns `429 rate_limited` (`{"error":"rate_limited"}` JSON on
`/api/*`, an in-shell operator page naming `rate_limited` with
`Retry-After` on `/webui/*`). The key is `RemoteAddr` only; no
`X-Forwarded-For` is trusted, so behind a reverse proxy all callers share
the proxy's bucket (see `docs/threat-model.md`). `POST /api/v1/enroll`
(consumption) is NOT rate-limited; replays lose the atomic consume race
with `410`.

### `GET /api/v1/widgets` (widgets PR)

Poll endpoint for the app. Auth: device Bearer token.

Response `200`:

```json
{
  "widgets": [
    {
      "id": "hello",
      "type": "text",
      "title": "Hello",
      "updated_at": "2026-10-23T00:00:00Z",
      "payload": { "body": "# Hello widget\n..." }
    }
  ]
}
```

Polling is client-driven: manual refresh on open + roughly hourly
background, screen-on aware, no exact alarms (see brief). The response may
include `Cache-Control` / `ETag` hints; conditional polling semantics are
the widgets PR's call.

### `POST /api/v1/notify` (notifications PR)

Enqueues fan-out to devices. Auth (v1): WebUI session (operator) or the
operator API token (`OPERATOR_API_TOKEN`, sent as
`Authorization: Bearer <token>`) — never a device token. Any Bearer
value that is not the operator token is rejected with `403` (no lookup,
no fallthrough to the session, even alongside one); no credential at all
is `401`. The token path needs no
write-guard header; the session path requires `X-Requested-With:
XMLHttpRequest` (or a session-bound CSRF token), same as the other
operator POST routes.

Request:

```json
{
  "target": { "all": true },
  "kind": "note",
  "title": "Dinner",
  "body": "Ready in 10 minutes",
  "priority": "default",
  "channel": "general",
  "actions": [
    { "id": "ack", "title": "ACK" }
  ],
  "image_url": "https://example.com/dinner.jpg"
}
```

- `target`: either `{ "all": true }` or `{ "device_ids": ["<id>", ...] }`
  (exactly one form; empty `device_ids` is `422`).
- `kind`: extensible string (`note`, `alert`, …). Unknown kinds are
  accepted and rendered generically so new kinds don't require app
  updates.
- `priority`: `min | low | default | high | max` (default `default`).
- `channel`: Android notification channel id (default `general`).
- `actions`: max 3, each `{ "id": "<stable-id>", "title": "<label>" }`.
  Action tap behavior is the notifications PR's contract extension.
- `image_url` (optional): HTTPS attachment URL; the app downloads and
  shows it where sensible.

Response `202`: `{"notification_id": "<opaque-id>", "accepted": 2}`.

### `POST /api/v1/app-push` (remote APK install push, additive v1)

Enqueues a remote APK install directive to devices. Auth (v1): WebUI
session (operator) with the write-guard header, OR the operator API
token (`OPERATOR_API_TOKEN`, sent as `Authorization: Bearer <token>`,
no write-guard header needed) — never a device token. Any Bearer value
that is not the operator token is rejected with `403` (no lookup, no
fallthrough to the session, even alongside one); no credential at all
is `401`; a session without the write-guard header is `403`.

Request:

```json
{
  "target": { "all": true },
  "apk_url": "https://example.com/app-release.apk",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "package": "com.example.app",
  "version": "1.2.3",
  "label": "Test App"
}
```

- `target`: `{ "all": true }` wins outright — `device_ids` alongside it
  are ignored entirely (even a non-empty list), normalizing to All. (This
  differs from `POST /api/v1/notify`, where the mix is `422`
  `ambiguous_target`.) Otherwise exactly `{ "device_ids": ["<id>", ...] }`
  with a non-empty list; empty `device_ids` is `422`. Unknown ids are
  tolerated and skipped, revoked devices are always excluded — same
  semantics as `POST /api/v1/notify`.
- `apk_url`: required, `https://` only (no other scheme, no credentials
  in the URL), max 2048 characters.
- `sha256`: required, exactly 64 lowercase hex characters (the expected
  digest of the APK bytes; the backend stores the digest, never the APK).
- `package`: required, reverse-DNS shape (at least one dot, only
  `[A-Za-z0-9_.]`), max 256 characters.
- `version`: optional, max 64 characters; absent rides as an empty
  string in the data message.
- `label`: required, 1..100 characters (operator display name).
- The marshaled data message must fit the FCM size budget (same ~3.5KB
  bound as notify): over-budget requests are `422` `payload_too_long`.

Validation failures are `422` with a machine-readable `{"error":
"<code>"}` (`empty_target`, `too_many_targets`,
`missing_apk_url`, `invalid_apk_url`, `missing_sha256`,
`invalid_sha256`, `missing_package`, `invalid_package`,
`invalid_version`, `missing_label`, `invalid_label`,
`payload_too_long`). Undecodable bodies are `400` `invalid_json`
(unknown fields are tolerated); oversize bodies are `413`
`body_too_large`.

Response `202`: `{"push_id": "<opaque-id>", "accepted": 2}` where
`accepted` counts devices the message was actually handed to the
driver for. `accepted: 0` is a normal `202` strictly when no devices
resolve (unknown ids, or an empty registry) — not an error. Every
attempted push is recorded in the server-side `app_pushes` audit table
(`push_id`, timestamp, operator identity, target, the request fields,
and the final `accepted` count); `notification_history` is untouched by
this endpoint. Audit is written BEFORE fan-out: the row is inserted
first (a DB failure answers `500` with nothing sent), then the driver
sends, then `accepted` is UPDATEd. A stub driver (`log`/`noop`) facing
real targets is rejected with `500` `driver_unavailable` and writes no
audit row at all; a real driver failing every send answers `500`
`driver_unavailable` with the audit row left at `accepted: 0`.

Fan-out is a data-only message through the same driver as notify, with
exactly these flat string fields (no `notification` block):

```json
{
  "type": "app_install",
  "push_id": "<opaque-id>",
  "apk_url": "https://example.com/app-release.apk",
  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "package": "com.example.app",
  "version": "1.2.3",
  "label": "Test App"
}
```

Unknown `type` values must be ignored (not crash) by the app, so the
backend can add install-adjacent kinds later without app updates.

## Widget types + envelope

Every widget served by `GET /api/v1/widgets` uses this envelope:

```json
{
  "id": "hello",
  "type": "text",
  "title": "Hello",
  "updated_at": "2026-10-23T00:00:00Z",
  "payload": {},
  "secret_ref": "optional-opaque-ref"
}
```

| Field        | Meaning                                                      |
| ------------ | ------------------------------------------------------------ |
| `id`         | Stable id = widget directory name.                           |
| `type`       | One of `text \| secret \| list \| status`.                   |
| `title`      | Display title from `widget.toml`.                            |
| `updated_at` | Last backend-observed change; drives client refresh.         |
| `payload`    | Type-specific content (shapes below).                        |
| `secret_ref` | Present only for `secret`; `kid` of the per-device key that   |
|              | encrypted this widget's payload (v1: the target `device_id`).  |
|              | Lets the app pick the right stored key before decrypting.      |

Payload shapes (v1):

- `text`: `{ "body": "<markdown/plain text>" }` from `body.md`.
- `secret`: the encrypted envelope below — decryptable only by the target
  device; never logged, never cached unencrypted.
- `list`: `{ "items": [{ "id": "<stable>", "text": "<label>", "done": false }] }`.
- `status`: `{ "state": "ok | warn | alert | unknown", "detail": "<text>" }`.

### Encrypted envelope for `secret` widgets (widgets P5, notify P10)

All secret-bearing fields use AES-256-GCM with a fresh random 96-bit nonce
per message. The envelope is JSON with base64 values. Base64 everywhere in
this section (and `device_key` above) means RFC 4648 §4: standard alphabet,
`=` padding, no line breaks:

```json
{
  "alg": "AES-256-GCM",
  "kid": "<device_id whose secret_key encrypted this>",
  "nonce": "<base64 12-byte nonce>",
  "ciphertext": "<base64 AES-GCM ciphertext+tag>"
}
```

Plaintext byte encoding: the plaintext is the UTF-8 bytes of the secret
string value itself (unquoted — not JSON-quoted, not base64). The empty
string (zero-length plaintext) is legal. Decryptors decode the output as
UTF-8 and fail closed on invalid UTF-8.

Payload placement (`payload` == envelope): for `secret` widgets the widget
`payload` object IS exactly this envelope (four fields, no others) — it
does not contain it under a sub-key. The top-level `secret_ref` MUST equal
`payload.kid` MUST equal the target `device_id`. The app selects its stored
key by `secret_ref`, then checks `payload.kid` agrees; any mismatch fails
closed with no decrypt attempt.

Rules:

- `alg` is fixed to `AES-256-GCM` at v1. Unknown `alg` values fail closed
  (no decrypt attempt, "needs refresh" UI, never a crash) so a future
  algorithm can be introduced without breaking old apps.
- `nonce` MUST be freshly random (crypto-strength RNG) per encrypted
  message: 12 bytes, base64-encoded as one field. Reuse across two messages
  under the same key is a correctness/security bug.
- `ciphertext` is the raw AES-GCM output (ciphertext concatenated with the
  16-byte auth tag), base64-encoded as one field.
- Associated data (exact byte construction): `AAD = UTF-8(kid) || 0x00 ||
  UTF-8(binding)` — the UTF-8 bytes of `kid`, one zero byte, then the UTF-8
  bytes of the binding. `kid` and `binding` are non-empty and never contain
  `0x00`. Every implementation (server, app, reference
  `backend/crypto/`) MUST construct these exact bytes, so cross-platform
  ciphertexts verify. Where a platform API makes AAD awkward,
  implementations MUST still verify `kid`/binding match before rendering
  (fail closed on mismatch).
- Binding values: for secret widgets, `binding` is the widget `id`, so a
  ciphertext cannot be transplanted across devices or widgets. For notify
  fan-out there is no widget `id`: each per-device copy encrypts each
  secret-bearing field (e.g. `title`, `body`) as its own envelope under
  that device's key with `binding =
  "notify/<notification_id>/<field>"` (field = the JSON field name being
  encrypted), so a ciphertext cannot move across devices, notifications,
  or fields either. Notification ids and field names are server-issued,
  non-empty, and contain neither `/` nor `0x00`, so distinct pairs never
  produce the same binding string. The v1 `POST /api/v1/notify` request shape above stays
  plaintext; this rule governs the per-device encrypted copies the
  notifications PR fans out.
- Key selection and rotation follow "Key bootstrap at enrollment" above:
  decrypt only under the stored key matching `kid`; on mismatch or auth
  failure, fail closed. Re-pair rotates the key; old ciphertexts become
  undecryptable by design.

Unknown `type` values must be ignored (not crash) by the app so the
backend can add types later.

## Versioning

Breaking changes go under `/api/v2/*`. Additive changes (new kinds,
optional fields, new widget types) stay in v1 per the extensibility rules
above.
