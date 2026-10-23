# Phone Companion

Self-hosted backend + native Android app for home-screen widgets and push
notifications. The backend runs in Docker behind a reverse proxy; phones
pair by scanning a QR code in the WebUI, then poll widget definitions and
receive targeted notifications.

- `backend/` — Go service (stdlib-first, distroless image). See
  `backend/README.md`. Config via env, sqlite state in `/data`, widget
  definitions in `/widgets`.
- `android/` — Native Kotlin app. See `android/README.md`. `minSdk 26`,
  builds with `./gradlew assembleDebug`.
- `docs/setup.md` — operator guide: secrets, first boot, backup, upgrades.
- `docs/pairing.md` — pairing, revocation, and re-pair in detail.
- `docs/api-contract.md` — frozen v1 wire contract (auth schemes, enroll,
  widgets poll, notify fan-out, widget envelope + types).
- `docs/threat-model.md` — trust boundaries, secrets inventory, and
  non-goals. Read before exposing the server.

## Architecture

```text
                    HTTPS (reverse proxy: Caddy/nginx)
                    |
            +-------v--------+
            |    backend     |  Go, :8080 plain HTTP internally
            |  /login        |  WebUI session (browser, operator)
            |  /webui/pair  |  pairing QR + device list + revoke
            |  /api/v1/*    |  enroll / widgets / devices / notify
            +---+--------+---+
                |        |
      sqlite /data    /widgets (read-only mount,
      devices/sessions folder-per-widget files)
      history/tokens
                |
   +------------+------------+
   |                         |
Browser WebUI          Android app (per device)
login-gated pages       Bearer token + per-device key
operator actions        in encrypted storage;
                        polls widgets, receives FCM
```

Two independent auth schemes, never interchangeable: the browser WebUI
uses a session cookie (`pc_session`, signed with `SESSION_SECRET`);
the app uses a per-device Bearer token issued once at enroll time.
Device tokens can never authorize operator APIs (`403`), and the admin
session can never enroll a phone. Details: `docs/api-contract.md`.

## Quickstart: zero to paired phone

Prerequisites: Docker + `docker compose`, `openssl`, and (for a password
hash) `apache2-utils` (`htpasswd`).

```sh
# 1. Copy the example compose file to the repo root (build context
#    ./backend only resolves from here) and generate secrets:
cp backend/docker-compose.example.yml docker-compose.yml
openssl rand -hex 32   # -> SESSION_SECRET (min 32 bytes)

# 2. Hash the admin password. Bcrypt only; in docker-compose.yml every
#    `$` in the hash MUST be doubled to `$$` or compose interpolates it
#    away and login fails:
htpasswd -bnBC 10 admin 'choose-a-long-password' | cut -d: -f2
```

Edit `docker-compose.yml`:

| Key | Value |
| --- | --- |
| `ADMIN_USER` | `admin` (any non-empty, non-`CHANGEME` name) |
| `ADMIN_PASSWORD_HASH` | bcrypt hash from above, with `$` → `$$` |
| `SESSION_SECRET` | `openssl rand -hex 32` output |
| `EXTERNAL_BASE_URL` | `https://<your-public-host>` in production; may stay commented for localhost testing |

```sh
# 3. Boot and check health:
docker compose up -d --build
curl http://127.0.0.1:8080/healthz   # {"status":"ok"}
docker compose logs backend          # "loaded 1 widget(s)", notify driver
```

```sh
# 4. Sign in (success is 303; the 302 to /login is the RequireAdmin
#    "not signed in" redirect on gated pages, not the login result):
curl -c jar.txt -d 'username=admin&password=choose-a-long-password' \
  -w "\nPOST /login -> %{http_code}\n" \
  http://127.0.0.1:8080/login
```

Open `http://127.0.0.1:8080/webui/pair` in a browser and sign in there to
see the pairing QR and device list.

```sh
# 5. Pair a phone: open the app's Pair screen, scan the QR (or type the
#    base URL + enroll token manually), tap Enroll. The server returns
#    device_id + device_token + device_key exactly once; the app stores
#    them in encrypted storage. Tokens are single-use, 15 min expiry.

# 6. Send a test notification (operator session required + write-guard
#    proof: X-Requested-With or a session-bound CSRF token, same as
#    enroll-tokens). curl does not
#    resend the Secure-flagged cookie over plain HTTP, so pass it explicitly:
COOKIE=$(grep pc_session jar.txt | awk '{print $NF}')
curl -H "Cookie: pc_session=$COOKIE" -H 'Content-Type: application/json' \
  -H 'X-Requested-With: XMLHttpRequest' \
  -d '{"target":{"all":true},"kind":"note","title":"Hello","body":"First test"}' \
  http://127.0.0.1:8080/api/v1/notify
# -> {"notification_id":"...","accepted":N} (202; accepted:0 with no
#    devices paired is normal, not an error)
```

```sh
# 7. Add a widget: create /srv/phonecompanion/widgets/<id>/ (or edit the
#    mounted ./backend/widgets for testing) with widget.toml + body.md,
#    then reload (mtime recheck also picks it up on the next poll):
docker kill -s HUP <backend-container>
```

Open the app's Widgets screen (manual refresh on open, "Refresh now"
button, plus hourly background poll), then long-press the home screen to
place a Phone Companion widget. Widget format: `backend/README.md`;
pairing edge cases: `docs/pairing.md`.

## Reverse proxy (TLS)

Production MUST terminate TLS at a reverse proxy and set
`EXTERNAL_BASE_URL=https://<your-public-host>` so pairing QR codes point
at the true public origin. Without it the QR builder falls back to the
request `Host`, which is client-controlled (dev-only; the boot log warns).

Caddy (`example.com` → local backend on `127.0.0.1:8080`):

```text
example.com {
    reverse_proxy 127.0.0.1:8080
}
```

nginx (same shape; `X-Forwarded-Proto` preserves the TLS signal):

```text
server {
    listen 443 ssl;
    server_name example.com;
    ssl_certificate     /etc/letsencrypt/live/example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/example.com/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Keep the compose `127.0.0.1:8080:8080` bind so only the proxy is public.

## Configuration

All backend config is environment (same image everywhere). Fail-closed:
the backend refuses to boot on weak/missing secrets.

| Var | Default | Notes |
| --- | ------- | ----- |
| `PORT` | `8080` | HTTP listen port inside the container |
| `ADMIN_USER` | — (required) | WebUI login name; non-empty, not `CHANGEME*` |
| `ADMIN_PASSWORD_HASH` | — | Preferred credential: bcrypt (`htpasswd -bnBC 10`), `$` → `$$` in compose |
| `ADMIN_PASSWORD` | — | Bootstrap-only fallback, min 12 chars. One of hash/password required |
| `SESSION_SECRET` | — (required) | HMAC key for WebUI sessions + CSRF tokens; min 32 bytes |
| `DB_PATH` | `/data/phone-companion.db` | sqlite file; back up `/data` (see `docs/setup.md`) |
| `WIDGET_DIR` | `/widgets` | Folder-per-widget mount (see `backend/README.md`) |
| `EXTERNAL_BASE_URL` | — | `https://<public-host>` bare origin. Required in production; dev falls back to request Host |
| `NOTIFY_DRIVER` | `log` | `log` (default, logs deliveries), `noop` (silent, CI), `fcm` (real FCM HTTPv1 push) |
| `FCM_PROJECT_ID` | — | Needed only for `fcm` driver |
| `FCM_CREDENTIALS_FILE` | — | Path to mounted service-account JSON; never committed or baked into the image |

## Security notes

- Login-gated WebUI, per-device revocable tokens, per-device payload
  encryption for secret widgets (AES-256-GCM, fresh nonce per message).
- Revocation takes effect on the next request; re-pair rotates the
  per-device key (old ciphertexts fail closed by design).
- See `docs/threat-model.md` for the full story, including explicit
  non-goals (no metadata hiding, no DB at-rest encryption at v1).

## Verification status

Verified in this PR's environment: `docker build ./backend`, full
compose quickstart (`up -d --build`, `/healthz`, bcrypt-hash login →
`303`, session-cookie + `X-Requested-With` `POST /api/v1/notify` → `202`),
`go vet` + `go test` clean. NOT run here: Android build (no SDK in this environment) and the
Caddy/nginx snippets above (standard proxy config, adjust paths/hosts).
