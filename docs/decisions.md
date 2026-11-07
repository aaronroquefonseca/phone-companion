# Decision record — Phone Companion v1

Why each major choice was made, and what is left. Detail lives in the
linked docs; this file is the index of decisions.

## Stack

- **Backend: Go, stdlib-first** (`backend/go.mod`: `go-qrcode`,
  `golang.org/x/crypto`, `modernc.org/sqlite` pure-Go + indirect only).
  Single static binary in a **distroless** image. Reason: smallest
  long-lived footprint, no libc/cgo surprises, `go vet` + `gofmt` as the
  whole style gate. See `backend/README.md`.
- **Android: native Kotlin**, `minSdk 26`, `compileSdk/targetSdk 34`,
  Gradle wrapper `8.9`, JDK 17. Retrofit/Moshi/OkHttp (API), Room
  (widget cache + bindings), WorkManager (polling), Glance (home-screen
  widget), `security-crypto` (credential store). Firebase BoM `33.2.0`
  (messaging only) + ML Kit barcode-scanning `18.3.0` / code-scanner
  `16.1.0` are the only proprietary deps — see F-Droid below.
  See `android/README.md`.
- **State:** sqlite file at `/data` (compose volume,
  `phone-companion-data:/data`), widget definitions at `/widgets`
  (read-only bind). No hardcoded hosts/users/credentials — everything is
  env config with fail-closed boot validation.
- **State ops**: back up `/data` as a stopped-stack file/volume-tar copy
  (losing it means re-pairing every phone — keys live only there and in
  phone storage); schema auto-migrates at boot, rollback is not
  supported (restore the backup instead). See `docs/setup.md` §§3–4.

## Pairing

- WebUI behind login: bcrypt `ADMIN_PASSWORD_HASH` preferred (with the
  compose `$$` escaping this forces), `ADMIN_PASSWORD` bootstrap-only
  fallback (min 12 chars). Session cookie `pc_session` signed with
  `SESSION_SECRET` (24h TTL, hashes only in DB) + domain-separated CSRF
  tokens. Detail: `docs/pairing.md`, `docs/setup.md`.
- Pairing QR encodes `{enroll_url, enroll_token}`. Tokens are single-use,
  15 min expiry, issuance bucket 30/min/`RemoteAddr` shared between the
  API and WebUI mint (page loads never mint). Enroll returns
  `device_id + device_token + device_key` exactly once; the app keeps
  them in `EncryptedSharedPreferences`.
- Multiple phones, individually revocable (`DELETE /api/v1/devices/{id}`,
  idempotent), revoked-only purge, re-pair rotates the per-device key.
- `EXTERNAL_BASE_URL=https://<public-host>` required in production so
  QRs encode the true origin (fail-closed: plain `http://` to a
  non-loopback host refuses to boot). `ALLOW_INSECURE_COOKIES=1` and
  `EXTERNAL_BASE_URL_ALLOW_HTTP=1` are dev-only escape hatches, never
  production. TLS terminates at the reverse proxy (Caddy/nginx snippets
  in `README.md`); the backend serves plain HTTP on `:8080` bound to
  `127.0.0.1` in compose.

## Notifications

- Operator-only `POST /api/v1/notify` (WebUI session + write-guard proof,
  or `OPERATOR_API_TOKEN` Bearer; device Bearers get `403`). Targets:
  `all` or up to 500 explicit `device_ids`; 1 MiB cap; `image_url`
  HTTPS-only. Extensible kinds registry (`note`/`alert`, generic
  fallback for unknown kinds so new kinds don't need app updates);
  Android renders channels/priority/actions, `BigPictureStyle` with
  fail-closed to `BigTextStyle`. Contract: `docs/api-contract.md`.
- **Secret notifications**: `POST /api/v1/notify` with `"secret": true`
  (also a WebUI checkbox) per-device encrypts title/body into
  `title_env`/`body_env` P5 envelopes bound with
  `crypto.NotifyBinding(notification_id, field)` (fresh nonce per
  field/device; ~3500B data budget). FCM/log drivers only ever see
  ciphertext + the `"secret":"true"` flag; history rows are sanitized.
  Without the flag, notify content is plaintext to the push leg — see
  `docs/threat-model.md` ("Secret notifications") and
  `docs/api-contract.md` (envelope rules).
- **Delivery leg is the poll-based server outbox** (7-day TTL, max 20
  pending/device, `GET /api/v1/outbox` + 300s long-poll
  `GET /api/v1/events/wait` + idempotent ack). FCM (`NOTIFY_DRIVER=fcm`)
  is an optional best-effort **wake hint only** (data-only payloads;
  secret content sealed per-device *before* the driver sees it). Drivers:
  `log` (default) / `noop` (CI) / `fcm`. History pruned to 90d / 20k rows.
- **Remote APK install** rides the same outbox (`app_install` commands):
  explicit-tap install only (never auto, never on boot/poll), HTTPS-only
  download via shared OkHttp (100 MB cap), SHA-256 verified *before*
  install (mismatch = hard refuse), `PackageInstaller` session with
  unknown-sources onboarding gate, cached APK deleted on every terminal
  path. No FCM/`google-services.json` anywhere in this flow.

## Widgets

- **Folder-per-widget**: `$WIDGET_DIR/<id>/` with `widget.toml`
  (`id/type/title`, optional `secret_ref`) + `body.md` (text) or
  `payload.json` (list/status/secret). Registry keeps the last-good
  snapshot, skips invalid dirs with a log line, caps reads (~1 MB),
  refuses symlinks, reloads on `SIGHUP` + best-effort mtime recheck.
- **Polling**: manual refresh on open + "Refresh now" + hourly
  `WorkManager` (needs network + battery-not-low, screen-off returns
  retry, no exact alarms). Shared `WidgetPoller` plumbing, **per-type
  render paths** (text/list/status/secret builders — never one
  fully-generic renderer). One Glance provider entry; per-instance
  binding table with configure-on-add and explicit unassociated state.
- **Secrets**: operator-authored plaintext in `payload.json` is *never*
  served verbatim — at serve time it is sealed per-device (AES-256-GCM,
  fresh 96-bit nonce per response, `kid == secret_ref == device_id`).
  One secret widget targets exactly one device (duplicate the directory
  per device for N phones). Foreign/unknown ids return byte-identical
  `404` (no existence oracle); re-pair re-keys (old ciphertexts fail
  closed by design). Threat model: `docs/threat-model.md`.

## Security defaults

- Three non-interchangeable credentials: WebUI session, per-device
  Bearer, operator API token. Revocation takes effect on the next
  request. FCM service account least privilege
  (`cloudmessaging.messages.create`). No secrets in repo/logs/chat:
  `google-services.json` gitignored, credentials file must be `0600`,
  logs carry metadata only (never token/key/nonce/plaintext).
- **Explicit non-goals at v1** (`docs/threat-model.md`): no hiding of
  notification/pairing metadata, no device attestation (the enroll
  token is a bearer secret, not attestation), no sqlite at-rest
  encryption (rely on filesystem/volume controls), no cross-device key
  history (rotation overwrites).

## CI / release / F-Droid

- **CI** (`.github/workflows/ci.yml`): backend `gofmt` + `go vet` +
  `go test` + `docker build`; Android `assembleDebug` +
  `testDebugUnitTest` + managed-device (ATD, API 30) widget runtime
  suite. Every step echoes its local repro into the job log;
  `cancel-in-progress` keeps PR checks fast.
- **Release** (`.github/workflows/release.yml`): push a `v*` tag →
  backend image to GHCR (default, overridable via `REGISTRY` var) +
  versioned APK + machine-verifiable `SHA256SUMS.txt` +
  `image-digest.txt` attached to the GitHub Release.
  `workflow_dispatch` dry-runs the builds. No release keystore yet, so
  the artifact is the debug APK renamed with the tag — release signing
  is tracked future work.
- **F-Droid: consciously NOT enabled at v1** (`docs/fdroid.md`). Three
  proprietary deps block inclusion (`firebase-messaging`, ML Kit
  barcode-scanning, code-scanner). The documented path: an F-Droid
  flavor (poll-only notifications + OSS scanner), fastlane metadata,
  upstream recipe, reproducibility confirm (already pinned: Gradle 8.9,
  SDK 34, JDK 17, no committed `google-services.json`/keystores,
  `google-services` plugin not applied).

## Verification status

- `go test ./...` green, including `TestE2EDoneCriteria`
  (`backend/e2e_done_test.go`): two devices pair via the real
  enroll-token flow, targeted notify queues exactly one row for the
  target and none for the other, broadcast reaches both, the secret
  widget serves ciphertext-only P5 that decrypts under the owner's key
  (and nothing else), foreign/unknown ids are oracle-identical 404s,
  unauth is 401, revocation takes effect immediately.
- `docker build ./backend` + boot + `GET /healthz → {"status":"ok"}`
  verified; `gofmt`/`go vet` clean; secrets-pattern scan clean.
- Android: covered by CI (`assembleDebug`, `testDebugUnitTest`,
  managed-device ATD API-30 Glance suite) — last green Nov 4 (PR #43);
  NOT re-verified today: every repo CI run today fails at job startup
  repo-wide (infra — the workflow file is unchanged since the last
  green run). See `android/README.md#ci`.
- Still needs physical hardware (no phones/emulator with Play here):
  camera QR scan, launcher placement per device, production FCM
  delivery, proxy deployment. Manual checklists:
  `android/README.md` (poll-triggered install), `backend/README.md`
  (operator curls), `docs/setup.md`.
