# API contract (P0 BASE — frozen surface for later PRs)

Base URL: `https://<your-host>` (reverse proxy + TLS in front; the backend
itself serves plain HTTP on `$PORT`). All `/api/v1/*` request/response
bodies are JSON unless noted. Time fields are RFC 3339 UTC.

P0 BASE pins the shapes below. Stubs return `501 Not Implemented` until
their PR lands.

## Auth schemes

Three schemes; they are never interchangeable:

1. **WebUI session** — cookie-based. `POST /login` (WebUI PR) sets an
   `HttpOnly; Secure; SameSite=Lax` session cookie signed with
   `SESSION_SECRET`. Used by the browser WebUI: pairing QR page, device
   list, notify composer. Never sent by the Android app.
2. **Device token (Bearer)** — per-device opaque token issued at enroll,
   sent as `Authorization: Bearer <token>`. Used by the Android app for
   `GET /api/v1/widgets` and the poll-based push outbox
   (`GET /api/v1/events/wait`, `GET /api/v1/outbox`,
   `POST /api/v1/commands/ack`). Tokens are individually
   revocable; the server stores only a hash (`token_hash`, see `db.go`).
3. **Operator token (Bearer)** — single machine credential from
   `OPERATOR_API_TOKEN`, sent as `Authorization: Bearer <token>`. Accepted
   on the operator POST endpoints (`POST /api/v1/notify`,
   `POST /api/v1/app-push`) as an alternative to the WebUI session (a
   valid operator token needs no session and no `X-Requested-With`
   proof — CSRF proofs are for ambient cookies, not Bearers). ANY other
   Bearer value on an operator endpoint is `403` (valid credential, not
   allowed): device tokens, revoked tokens, and garbage are all refused
   with no further lookup and never fall through to the session. On
   device endpoints the operator token is likewise `403` — operator
   credentials never act as a device.

Unauthenticated responses use `401` (missing/invalid credential) and `403`
(valid credential, not allowed). Error bodies: `{"error": "<code>"}` with a
stable machine-readable code plus HTTP status.

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
operator token — never a device token (a request carrying any other Bearer
is rejected with `403`, even alongside a session).

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

Delivery is poll-based (see "Push outbox" below): a valid request ALWAYS
returns `202` with `accepted` counting the devices queued in the outbox —
the outbox is the delivery leg and needs no FCM, no Google services. FCM,
when the server runs the `fcm` driver, is only a best-effort wake hint per
device (failures never fail the request). The old `500
driver_unavailable` stub-driver failure is gone BY DESIGN: `202` now means
"queued", which is literally true, so there is nothing to lie about (this
is an intentional contract change, not a regression — servers without FCM
configured answer `202` exactly like servers with it).

### Push outbox (poll-based delivery, no FCM required)

`POST /api/v1/app-push` and `POST /api/v1/notify` enqueue one pending
command per target device into the same outbox table; devices fetch their
own commands by polling. No step here needs FCM, a service-account key,
or any Google service — a server with the default `log` driver delivers
exactly like one with the `fcm` driver (which only adds a best-effort
wake hint on the notify path).

#### `POST /api/v1/app-push` (remote install push)

Operator-only: WebUI session + `X-Requested-With` (or session CSRF token),
or the operator token (no session needed). Device Bearers are always
`403`; missing credentials are `401`.

Request:

```json
{
  "target": { "device_ids": ["<id>", ...] },
  "apk_url": "https://example.com/app.apk",
  "sha256": "64-lowercase-hex-chars…",
  "package": "com.example.app",
  "version": "1.0",
  "label": "Example"
}
```

- `target`: either `{ "all": true }` or `{ "device_ids": ["<id>", ...] }`
  (empty/absent `device_ids` with `all:true` means all; `all:true` with
  non-empty `device_ids` is `422 ambiguous_target`; neither form is `422
  empty_target`; over 500 explicit ids is `422 too_many_targets`).
  Unknown ids are skipped (never an error); revoked devices are always
  excluded.
- `apk_url` (required): `https` URL with a host, no userinfo, max 2048
  characters (`422 missing_apk_url` / `invalid_apk_url`).
- `sha256` (required): exactly 64 lowercase hex chars
  (`422 missing_sha256` / `invalid_sha256`).
- `package` (required): reverse-DNS-ish — at least one dot, charset
  `[A-Za-z0-9._-]`, max 256 characters (`422 missing_package` /
  `invalid_package`).
- `version` (optional): max 64 characters (`422 invalid_version`;
  absent/empty is stored as `""`).
- `label` (required): 1–100 characters (`422 missing_label` /
  `invalid_label`).
- The marshaled flat command map must fit the shared 3500-byte payload
  budget (`422 payload_too_large`); undecodable bodies are `400
  invalid_json`, oversize bodies `413 body_too_large`.

Response `202`: `{"push_id": "<opaque-id>", "accepted": 2}` where
`accepted` counts the devices queued. Genuinely zero targets (unknown
ids, empty registry) are a normal `202 accepted:0`, never an error. A
device already holding 20 pending commands fails the WHOLE request
closed: `429 too_many_pending` with nothing queued (no partial push, no
audit row for the attempt). Any DB failure before the response is `500`
with nothing queued.

#### `GET /api/v1/events/wait` (device long-poll wake)

Auth: device Bearer only (`401` missing/invalid/revoked, `403`
operator token). Behavior:

- If the caller has pending unacked unexpired commands, returns
  immediately `200 {"events":["outbox"]}`.
- Else holds the request up to 300s for a per-device wake signal, then
  returns `200 {"events":[]}` on timeout. The server holds no DB
  connection while waiting; the wake fires only when an enqueue lands
  for THIS device (a push to other devices never wakes this waiter).
- Revoked devices authenticate nowhere: they get `401` and their
  waiters never fire.

#### `GET /api/v1/outbox` (device poll)

Auth: device Bearer only (`401` / `403` as above). Returns `200` with
ONLY the caller's own pending unexpired commands in queue order:

```json
{
  "commands": [
    {
      "command_id": "<opaque-per-device-id>",
      "type": "app_install",
      "push_id": "<opaque-id>",
      "apk_url": "https://example.com/app.apk",
      "sha256": "64-lowercase-hex-chars…",
      "package": "com.example.app",
      "version": "1.0",
      "label": "Example"
    }
  ]
}
```

`notify`-type rows carry the same envelope (`command_id`, `push_id` =
notification id, `type`) with the notification data map as their other
keys; the app ignores unknown `type` values. Empty poll is `200
{"commands":[]}` (never null). Headers pin `Cache-Control: no-store,
private`.

#### `POST /api/v1/commands/ack` (device acknowledge)

Auth: device Bearer only. Request `{"ids": ["<command_id>", ...]}` marks
the caller's OWN pending unexpired rows `received` (idempotent —
re-acking yields `acked:0`). Unknown ids, other devices' ids, and expired
rows are silently ignored. Response `200 {"acked": N}` with N counting
owned rows flipped. Malformed bodies are `400`; over 1000 ids are `400`.

#### Lifecycle, ordering, caps (all four endpoints)

- Poll → act → ack: a device polls `GET /api/v1/outbox`, performs the
  commands (e.g. download → verify sha256 → install), then acks their
  `command_id`s. Acked commands never poll again; unacked commands
  re-poll until acked or expired.
- Wake shortcut: instead of polling blind, a device may hold `GET
  /api/v1/events/wait` (up to 300s); `["outbox"]` means "poll now".
  Wake is a hint, not a guarantee — a device that missed it just polls.
- Ordering: commands poll in queue (rowid) order per device; no ordering
  is promised across devices or across pushes.
- Caps/TTL/prune: max 20 pending commands per device (app-push: `429`;
  notify: capped devices are skipped, still `202`); 7-day TTL per row;
  expired rows are pruned on every enqueue AND every outbox fetch.
- Auditing: every accepted operator request writes one `pushes` row
  (id, type, operator label — never a secret, canonical target JSON,
  final accepted count) before any per-device row (a DB failure answers
  `500` with nothing queued).
- Explicit no-FCM statement: no endpoint in this section requires, reads,
  or returns anything from Google services. `google-services.json` must
  not exist anywhere in the tree (pinned by test).

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
