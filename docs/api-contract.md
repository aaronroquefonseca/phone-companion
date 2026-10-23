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
  "device_token": "raw-bearer-token-shown-once"
}
```

The raw `device_token` is returned exactly once; the server keeps only its
hash. Errors: `410` expired/used enroll token, `409` device already
enrolled (re-pair flow, pairing PR decides).

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

Enqueues fan-out to devices. Auth: WebUI session (operator) or future
automation credential — never a device token.

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
| `secret_ref` | Present only for `secret`; opaque ref the app resolves via   |
|              | per-device decryption (threat-model doc, widgets PR).        |

Payload shapes (v1):

- `text`: `{ "body": "<markdown/plain text>" }` from `body.md`.
- `secret`: `{ "ciphertext": "<base64>", "alg": "<name>" }` — decryptable
  only by the target device; never logged, never cached unencrypted.
- `list`: `{ "items": [{ "id": "<stable>", "text": "<label>", "done": false }] }`.
- `status`: `{ "state": "ok | warn | alert | unknown", "detail": "<text>" }`.

Unknown `type` values must be ignored (not crash) by the app so the
backend can add types later.

## Versioning

Breaking changes go under `/api/v2/*`. Additive changes (new kinds,
optional fields, new widget types) stay in v1 per the extensibility rules
above.
