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
- `notify_handler.go` / `notify_auth.go` — `POST /api/v1/notify`.
- `notify/` — message validation, fan-out drivers, delivery history.
- `crypto/` — AES-256-GCM per-device encryption (shared with the app's
  Kotlin side byte-for-byte via AAD construction).
- `webui/` — login/logout, pairing page + QR, CSRF protection.

## Routes

| Method + path | Auth | Notes |
| --- | --- | --- |
| `GET /healthz` | none | `{"status":"ok"}` |
| `GET /login`, `POST /login` | form `username`+`password` | Sets session cookie; failures are generic (no user enumeration) |
| `POST /logout` | session | Clears session |
| `GET /webui/pair` | admin session (else `302 /login`) | Issues single-use enroll token, renders QR + device list with CSRF-guarded revoke forms; shares the 30/min issuance bucket (see below) |
| `POST /webui/pair/revoke` | admin session + CSRF token | Form `device_id`; unknown id redirects with `?error=device_not_found` |
| `POST /api/v1/enroll` | enroll token only (phone) | `201 {device_id, device_token, device_key}` shown once |
| `POST /api/v1/enroll-tokens` | admin session | Issues token via API; shares the 30/min issuance bucket (see below) |
| `GET /api/v1/devices` | admin session | Device registry |
| `DELETE /api/v1/devices/{id}` | admin session | Revoke; idempotent; unknown id → `404 device_not_found` |
| `GET /api/v1/widgets` | device Bearer | `{"widgets":[...]}`; ETag/`If-None-Match` → `304` (shapes: see Widgets below) |
| `GET /api/v1/widgets/{id}` | device Bearer | Single envelope; foreign secrets → `404` (no oracle); own-secret failures → `500 needs_refresh` (shapes: see Widgets below) |
| `POST /api/v1/notify` | admin session (`401` without; device Bearer → `403`) | `202 {notification_id, accepted}`; `accepted:0` is a normal 202 |

Issuance rate limit (shared bucket): `POST /api/v1/enroll-tokens` and
`GET /webui/pair` share 30 token mints per `RemoteAddr` per minute
(in-memory; restarts clear it). Exhaustion is `429 rate_limited`
(JSON `{"error":"rate_limited"}` on `/api/*`, plain text on
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
# fill ADMIN_USER / ADMIN_PASSWORD_HASH (preferred) / SESSION_SECRET
docker compose up -d --build
docker compose logs backend
```

The image carries empty `/data` + `/widgets`; compose mounts the sqlite
volume (`phone-companion-data:/data`) and the widget dir
(`./backend/widgets:/widgets:ro`) over them.

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

## Notifications (`POST /api/v1/notify`)

Operator-only (see Routes). Caps: 1 MiB body, max 500 explicit
`device_ids`, field-length validation, HTTPS-only `image_url`. Drivers via
`NOTIFY_DRIVER`: `log` (default), `noop` (CI), `fcm` (skeleton that fails
closed until Wave-2 push; needs `FCM_PROJECT_ID` +
`FCM_CREDENTIALS_FILE`). Fan-out is sequential; delivery history is
pruned to 90 days / 20,000 newest rows best-effort after fan-out.

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
