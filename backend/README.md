# Backend

Go service (stdlib-first) serving the WebUI, the pairing API, widget
definitions, and notification fan-out. Ships as a distroless static
image; config is env-only. Contract: `docs/api-contract.md`. Security
model: `docs/threat-model.md`.

## Layout

- `main.go` — entrypoint: config → open/migrate DB → notify driver →
  routes → serve.
- `config.go` — env-based config + fail-closed `Validate` (weak/missing
  secrets refuse to boot; error text never echoes secret material).
- `db.go` — sqlite open + migrations (`devices`, `sessions`,
  `enroll_tokens`).
- `api.go` / `pair_wire.go` — route wiring (see "Routes" below).
- `devices.go` — enroll-token issue/consume, device registry, revocation.
- `session.go` — WebUI cookie sessions (`pc_session`, 24h TTL, only
  token hashes in DB).
- `widgets.go` — `$WIDGET_DIR` loader + device-authed poll endpoints.
- `notify_handler.go` / `notify_auth.go` — `POST /api/v1/notify`
  (operator-only chain: WebUI session or `OPERATOR_API_TOKEN`).
- `app_push.go` / `outbox.go` / `outbox_handlers.go` — `POST
  /api/v1/app-push` + the device poll surface (`GET /api/v1/outbox`,
  `GET /api/v1/events/wait`, `POST /api/v1/commands/ack`); the outbox
  table is the poll-based delivery leg (no FCM required).
- `notify/` — message validation, fan-out drivers, delivery history.
- `crypto/` — AES-256-GCM per-device encryption (shared with the app's
  Kotlin side byte-for-byte via AAD construction).
- `webui/` — login/logout, pairing page + QR, CSRF protection.

## Routes

| Method + path | Auth | Notes |
| --- | --- | --- |
| `GET /healthz` | none | `{"status":"ok"}` |
| `GET /login`, `POST /login` | form `username`+`password` | Sign-in surface: authed `GET /login` (and `POST /login` while authed) `303` to `/`; success `303` to `/`; logout lands on `/login?signed_out=1` with a signed-out flash; failures are generic (no user enumeration), username echoes, 429/CSRF failures render in-shell |
| `POST /logout` | session + CSRF token | Clears session, `303` to `/login?signed_out=1`; `GET /logout` answers `405` in-shell (sign-out is POST-only) |
| `GET /webui/pair` | admin session (else `303 /login`) | Device registry only — never mints (page loads cost nothing from the issuance bucket). Active devices by default; `?show=revoked` reveals dimmed revoked rows with purge controls. Two-step revoke confirm + success flash (`?revoked=`); revoked-only two-step purge (`?purged=`) |
| `POST /webui/pair/mint` | admin session + CSRF token | Mints one single-use enroll token (15 min), renders the QR panel: human countdown, Host-fallback warning, copy-to-clipboard, Issue-a-fresh-token refresh, single-shot auto-refresh fetch (JSON via `Accept: application/json`). Shares the 30/min issuance bucket; exhaustion is an in-shell `429` page |
| `POST /webui/pair/revoke` | admin session + CSRF token | Two-step confirm form `device_id` (+`confirm=yes`); success flashes `?revoked=<name>`; unknown id redirects with `?error=device_not_found`; active rows only |
| `POST /webui/pair/purge` | admin session + CSRF token | Two-step confirm hard-delete of REVOKED rows only (`?purged=<name>`); active rows refused (`?error=not_revoked`); purged tokens `401` exactly like revoked ones |
| `POST /api/v1/enroll` | enroll token only (phone) | `201 {device_id, device_token, device_key}` shown once |
| `POST /api/v1/enroll-tokens` | admin session | Issues token via API; shares the 30/min issuance bucket (see below) |
| `GET /api/v1/devices` | admin session | Device registry |
| `DELETE /api/v1/devices/{id}` | admin session + write-guard proof (missing `X-Requested-With`/CSRF → `403`) | Revoke; idempotent; unknown id → `404 device_not_found` |
| `GET /api/v1/widgets` | device Bearer | `{"widgets":[...]}`; ETag/`If-None-Match` → `304` (shapes: see Widgets below) |
| `GET /api/v1/widgets/{id}` | device Bearer | Single envelope; foreign secrets → `404` (no oracle); own-secret failures → `500 needs_refresh` (shapes: see Widgets below) |
| `POST /api/v1/notify` | operator session + write-guard proof, or `OPERATOR_API_TOKEN` Bearer (`401` without either; any other Bearer → `403`) | `202 {notification_id, accepted}`; `accepted:0` is a normal 202. Delivery leg is the poll outbox (no FCM required); FCM driver (if configured) is only a best-effort wake hint |
| `POST /api/v1/app-push` | operator session + write-guard proof, or `OPERATOR_API_TOKEN` Bearer (same `401`/`403` split) | `202 {push_id, accepted}`; per-device cap overflow is `429 too_many_pending` with nothing queued; best-effort FCM wake hint per token device when `NOTIFY_DRIVER=fcm` (poll otherwise) |
| `GET /api/v1/outbox` | device Bearer (`401` missing/invalid/revoked; operator token → `403`) | `200 {"commands":[...]}` — caller's own pending unexpired commands only; `Cache-Control: no-store, private` |
| `GET /api/v1/events/wait` | device Bearer (same `401`/`403`) | `200 {"events":["outbox"]}` immediately when pending, else held up to 300s, `{"events":[]}` on timeout |
| `POST /api/v1/commands/ack` | device Bearer (same `401`/`403`) | `200 {"acked":N}`; unknown/other-device ids silently ignored |
| `GET /webui/notify` | admin session (else `303 /login`) | Test-notification form (target dropdown from the live registry; revoked devices excluded) + PRG confirmation banner. `accepted:0` renders neutral guidance (pair first), never green |
| `POST /webui/notify` | admin session + CSRF token | PRG: `303` to `/webui/notify?sent=<id>&accepted=<n>` (whitelisted params); refreshing the confirmation never re-sends |

Issuance rate limit (shared bucket): `POST /api/v1/enroll-tokens` and
`POST /webui/pair/mint` share 30 token mints per `RemoteAddr` per minute
(in-memory; restarts clear it). `GET /webui/pair` never mints, so page
loads and refreshes cost nothing from the bucket. Exhaustion is
`429 rate_limited` (JSON `{"error":"rate_limited"}` on `/api/*`, an
in-shell operator page naming `rate_limited` with `Retry-After` on
`/webui/*`). The key is `RemoteAddr` only — no `X-Forwarded-For` is
trusted — so behind a reverse proxy all callers share the proxy's
bucket. Rationale: `docs/threat-model.md` ("Enroll-token lifecycle").

## Build and run

```sh
# Local (Go toolchain, from backend/):
cd backend && go run .

# Docker (from the repo root):
docker build -t phone-companion-backend:local ./backend
docker run --rm -p 8080:8080 \
  -e ADMIN_USER=admin -e ADMIN_PASSWORD='at-least-12-chars' \
  -e SESSION_SECRET="$(openssl rand -hex 32)" \
  phone-companion-backend:local
curl localhost:8080/healthz   # {"status":"ok"}
```

Compose (from the repo root — the example's `build: ./backend` only
resolves here):

```sh
cp backend/docker-compose.example.yml docker-compose.yml
# fill ADMIN_USER / ADMIN_PASSWORD_HASH (preferred) / SESSION_SECRET /
# OPERATOR_API_TOKEN
docker compose up -d --build
docker compose logs backend
```

The image carries empty `/data` + `/widgets`; compose mounts the sqlite
volume (`phone-companion-data:/data`) and the widget dir
(`./backend/widgets:/widgets:ro`) over them. Health: `GET /healthz` (no in-image HEALTHCHECK — distroless has no shell, so probe externally).

## Widgets (`WIDGET_DIR`)

One directory per widget, named by widget id
(`^[A-Za-z0-9][A-Za-z0-9_-]*$`, must equal `widget.toml`'s `id`).
Invalid widgets are skipped with a log line; the registry keeps serving
the last good snapshot. File reads are capped (~1MB) and symlinks refused.

`backend/widgets/hello/` (shipped example, `text` type):

```ini
# widget.toml
id = "hello"
type = "text"   # text | secret | list | status
title = "Hello"
```

```md
# body.md  (text widgets: served as payload {"body": "<file content>"})
# Hello widget
...
```

Other types use `payload.json` instead of `body.md`:

- `list`: `{"items": [{"id": "<stable>", "text": "<label>", "done": false}]}` (unknown fields tolerated).
- `status`: `{"state": "ok|warn|alert|unknown", "detail": "<text>"}`.
- `secret`: operator-authored plaintext `{"secret": "<utf-8 string>"}` —
  strict shape, exactly this field, empty string legal. NEVER served
  verbatim: at serve time it is encrypted per-device (AES-256-GCM, fresh
  96-bit nonce per response) into the `{alg,kid,nonce,ciphertext}`
  envelope with `kid == secret_ref == device_id`. `widget.toml` MUST
  declare `secret_ref = "<target device_id>"`; other devices get `404`
  (list omits it entirely).

Cardinality (v1): one secret widget directory targets exactly one
device (`secret_ref` is a single id). To show the same secret on N
phones, duplicate the directory per device — each copy is its own
widget (own id, own `secret_ref`); there are no secret groups and no
key history (re-pair re-keys only that row).

Reload: `docker kill -s HUP <container>` triggers an explicit reload,
plus a best-effort mtime recheck on every poll (no inotify — the
distroless image stays stdlib-only). `updated_at` is the newest relevant
file mtime (RFC 3339).

## Admin bootstrap

One of `ADMIN_PASSWORD_HASH` (preferred) or `ADMIN_PASSWORD` (bootstrap,
min 12 chars) is required; both may be set (hash wins for verification).
Generate the hash — note the compose `$$` escaping:

```sh
htpasswd -bnBC 10 admin 'your-password' | cut -d: -f2
# in docker-compose.yml: ADMIN_PASSWORD_HASH: "<hash with every $ doubled>"
```

`SESSION_SECRET`: `openssl rand -hex 32` (min 32 bytes, no `CHANGEME`
prefix). It signs session cookies AND (domain-separated) CSRF tokens.

`OPERATOR_API_TOKEN`: `openssl rand -hex 32` (min 32 bytes, no `CHANGEME`
prefix, never logged, never returned). Machine credential for the
operator POST endpoints (`POST /api/v1/notify`, `POST /api/v1/app-push`)
as an alternative to the WebUI session: `Authorization: Bearer
<token>`. The backend refuses to boot without it (fail closed, same as
`SESSION_SECRET`).

Session cookies are `Secure` by default (browsers only send them over
HTTPS — correct behind the TLS proxy). Plain-HTTP development (loopback /
LAN without TLS) needs the explicit escape hatch or the browser will not
send `pc_session` and sign-in will loop:

```sh
ALLOW_INSECURE_COOKIES=1   # dev-only, never in production
```

Fail-closed rules: unset/any other value keeps always-`Secure`; the boot
log warns when the hatch is open. See `docs/setup.md` "Plain-HTTP
development" for the full pointer.

## Pairing QR origin (`EXTERNAL_BASE_URL`)

Production must set `EXTERNAL_BASE_URL=https://<public-host>` (bare origin:
no userinfo, path, query, or fragment). Fail-closed rule: plain `http://`
to a non-loopback host is rejected at boot — enroll URLs, tokens, and
device credentials must ride TLS. Loopback `http://` (`localhost`,
`127/8`, `::1`) stays allowed for dev. LAN testing without TLS is possible
with the dev-only override `EXTERNAL_BASE_URL_ALLOW_HTTP=1` (never enable
in production).

## Notifications (`POST /api/v1/notify`) and app push (`POST /api/v1/app-push`)

Operator-only (see Routes). Caps: 1 MiB body, max 500 explicit
`device_ids`, field-length validation, HTTPS-only `image_url`. Delivery
is poll-based: every valid request `202`s with the devices queued in the
server-side outbox, which devices fetch via `GET /api/v1/outbox` (wake
via `GET /api/v1/events/wait`, confirm via `POST /api/v1/commands/ack`).
Drivers via `NOTIFY_DRIVER`: `log` (default), `noop` (CI), `fcm` (real
FCM HTTPv1 wake hint; needs `FCM_PROJECT_ID` +
`FCM_CREDENTIALS_FILE`). Fan-out is sequential; notification history is
pruned to 90 days / 20,000 newest rows best-effort after fan-out; the
outbox is pruned to its 7-day TTL on every enqueue and every poll, with
max 20 pending commands per device. `POST /api/v1/app-push` sends the
same wake-hint shape (`{"type":"app_push_wake","push_id":...}`, silent,
log-only failures, no history rows) so the app polls instantly when it
has an FCM token and on its normal cadence otherwise.

Operator curls (placeholder token — never commit a real one):

```sh
# Queue a notification for every device (session cookie or token):
curl -X POST localhost:8080/api/v1/notify \
  -H 'Authorization: Bearer OPERATOR_API_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"target":{"all":true},"title":"Dinner","body":"Ready in 10 minutes"}'
# → {"notification_id":"…","accepted":2}

# Queue a remote install push for two devices:
curl -X POST localhost:8080/api/v1/app-push \
  -H 'Authorization: Bearer OPERATOR_API_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"target":{"device_ids":["dev-id-1","dev-id-2"]},
       "apk_url":"https://example.com/app.apk",
       "sha256":"<64-lowercase-hex>",
       "package":"com.example.app","version":"1.0","label":"Example"}'
# → {"push_id":"…","accepted":2}

# Device poll / ack (device Bearer from enroll, shown once at pairing):
curl localhost:8080/api/v1/outbox -H 'Authorization: Bearer DEVICE_TOKEN'
curl -X POST localhost:8080/api/v1/commands/ack \
  -H 'Authorization: Bearer DEVICE_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"ids":["<command_id>"]}'
```

### Push via FCM (`NOTIFY_DRIVER=fcm`)

The driver POSTs each per-device **data-only** payload to FCM HTTPv1
(`POST /v1/projects/<project>/messages:send`) with a short-lived OAuth2
access token minted from the service-account JSON at
`FCM_CREDENTIALS_FILE` (scope:
`https://www.googleapis.com/auth/firebase.messaging`). Nothing in this
package touches client-side FCM artifacts.

- **Credentials file.** Mount the Firebase console's service-account JSON
  at runtime (compose secret or read-only bind) and point
  `FCM_CREDENTIALS_FILE` at it. Boot refuses group/other-readable files
  (want `0600`; `0400` also passes) and refuses to boot without the file
  when the driver is `fcm`. The file is never committed, baked into the
  image, or logged — only the failure class is ("cannot read FCM
  credentials file", never contents).
- **IAM minimum.** The service account needs the
  `cloudmessaging.messages.create` permission on the Firebase project
  (Firebase console: Project settings → Service accounts → Manage service
  account permissions). A custom role granting exactly that permission is
  the least-privilege choice; the Firebase Admin predefined role includes
  it. Setup walkthrough: `docs/setup.md` ("Push (FCM)").
- **Data-only contract.** Secret messages (`"secret": true`) are sealed
  into per-device P5 envelopes by the handler *before* `Send`, so the FCM
  leg carries ciphertext under `title_env`/`body_env` — never plaintext
  `title`/`body`. The driver POSTs the payload map verbatim (data block
  only, no `notification` block) and logs metadata only
  (`notification_id`, device id, `kind`).
- **Failure model.** Every send failure becomes a per-device `failed`
  history row (sanitized). Token death (`UNREGISTERED` / `INVALID_ARGUMENT`
  on the token, or a row with no stored token) returns a typed
  re-register signal recorded in the row — grep history for
  `fcm-unregistered` / `fcm-invalid-token` / `fcm-missing-token` to find
  devices that need a fresh token. The driver **never revokes**: healing
  is the token backfill (`POST /api/v1/device/fcm-token`, re-pair, or the
  app's post-poll backfill). The FCM leg is a hint only: hint failures are
  logged, never fail the operator request — the outbox row (not the hint)
  is the delivery leg, so valid requests always `202` whether or not FCM
  is configured.

## Log viewing

```sh
docker compose logs backend          # stack
docker compose logs -f backend       # follow
docker logs <container>              # plain docker run
```

Logs carry request outcomes and widget skip/reload lines only — never
token, key, nonce, or plaintext secret material.

## CI

PR checks (`.github/workflows/ci.yml`) run these exact commands; run them
locally before pushing:

```sh
cd backend && gofmt -l .   # empty output = clean
cd backend && go vet ./...
cd backend && go test ./...

# from the repo root:
docker build -t phone-companion-backend:local ./backend
```
