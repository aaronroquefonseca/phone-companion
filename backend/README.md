# Backend (skeleton)

Go service (stdlib-first) serving the WebUI, the pairing API, widget
definitions, and notification fan-out. P0 BASE is the skeleton + contracts
only: config, routes, DB stub, container.

## Layout

- `main.go` — entrypoint: config → open/migrate DB → serve.
- `config.go` — env-based config (`ADMIN_USER`, `ADMIN_PASSWORD_HASH` or
  `ADMIN_PASSWORD`, `SESSION_SECRET`, `DB_PATH`, `WIDGET_DIR`, `FCM_*`).
- `db.go` — sqlite open + minimal `devices` schema stub (no auth logic).
- `api.go` — `GET /healthz` live; `/api/v1/*` 501 stubs per
  `docs/api-contract.md`.
- `widgets/` — mounted folder-per-widget dir (`widget.toml` + `body.md`).

## Run

```sh
# Local (Go toolchain, from the backend/ directory):
cd backend && go run .

# Docker:
docker build ./backend
docker compose --env-file /dev/null up --build   # with your own compose file;
                                                 # see docker-compose.example.yml
```

Health check: `GET /healthz` → `{"status":"ok"}`.

## Notifications (`POST /api/v1/notify`)

Operator-only: gated by the WebUI session cookie (missing/invalid → `401
{"error":"unauthorized"}`); any request carrying a device `Authorization:
Bearer` header is rejected with `403 {"error":"forbidden"}` — device tokens
never authorize operator actions. Request/response shapes follow
`docs/api-contract.md` ("notify" section); the contract is unchanged.

- Caps kept from P4: 1 MiB request body (B1), at most 500 explicit
  `device_ids` (B2), plus field-length validation and HTTPS-only `image_url`.
- `202 {"notification_id","accepted"}`: `accepted` counts per-device sends
  that succeeded. `accepted: 0` is a normal 202, not an error — it means no
  devices matched (unknown `device_ids`, or an empty registry for
  `{"all": true}`).
- The FCM driver (`NOTIFY_DRIVER=fcm`) is still a skeleton that fails closed
  until the Wave-2 push implementation lands; default is `log`, `noop` for
  CI. When push is wired, set `FCM_PROJECT_ID` and mount the
  service-account JSON at `FCM_CREDENTIALS_FILE` (compose secret, never
  committed). Fan-out stays sequential; delivery history
  (`notification_history`) is bounded: rows older than 90 days are pruned
  and the table is capped at 20,000 newest rows (best-effort after fan-out).

## TODOs (later PRs)

Auth/session handling, pairing + QR enroll, widget serving, FCM fan-out,
per-device payload encryption, WebUI. See `docs/api-contract.md` and the
`TODO` comments in code.
