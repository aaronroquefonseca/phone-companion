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

## TODOs (later PRs)

Auth/session handling, pairing + QR enroll, widget serving, FCM fan-out,
per-device payload encryption, WebUI. See `docs/api-contract.md` and the
`TODO` comments in code.
