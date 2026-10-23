# Phone Companion

Self-hosted backend + native Android app for home widgets and push
notifications. Backend runs in Docker; the app pairs over a QR code,
polls widget definitions, and receives targeted notifications.

> P0 BASE status: repo skeleton + frozen contracts only. No auth, pairing,
> notifications, encryption, or widget logic yet — stubs and TODOs only.

## Layout

- `backend/` — Go service (Docker-first). See `backend/README.md`.
  Config via env, sqlite state in `/data`, widget defs in `/widgets`.
- `android/` — Native Kotlin app. See `android/README.md`.
  `minSdk 26`, builds with `./gradlew assembleDebug`.
- `docs/brief.md` — original build brief.
- `docs/api-contract.md` — frozen v1 surface: auth schemes, enroll,
  widgets poll, notify fan-out, widget envelope + types.

## Quick start (skeleton)

```sh
# Backend health check from the container:
docker build ./backend
docker run --rm -p 8080:8080 phone-companion-backend:local
curl localhost:8080/healthz   # {"status":"ok"}

# App (needs JDK 17 + Android SDK):
cd android && ./gradlew assembleDebug
```

Copy `backend/docker-compose.example.yml` to `docker-compose.yml` and fill
in the placeholders to run the stack (never commit secrets). Pairing and
setup docs land with the implementation PRs.
