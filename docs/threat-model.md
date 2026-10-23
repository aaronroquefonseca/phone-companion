# Threat model — Phone Companion (pairing P8)

This doc owns the pairing security story: enroll-token lifecycle, device
registry and revocation, WebUI session gating, and the QR trust boundary.
Wire contract lives in `docs/api-contract.md` (frozen); this file explains
*why* the wiring is the way it is and what we explicitly do not promise.

## Trust boundaries

- **Browser WebUI** (operator): login-gated pages (`/webui/pair`) and
  operator APIs (`POST /api/v1/enroll-tokens`, `GET /api/v1/devices`,
  `DELETE /api/v1/devices/{id}`). Trusts the backend session cookie and
  the TLS-terminating reverse proxy in front of it.
- **Backend**: sqlite state (`devices`, `enroll_tokens`, `sessions`),
  per-device `secret_key` material, session HMAC secret. Fails closed on
  miswiring (nil DB, missing secret).
- **Reverse proxy / TLS terminator**: owns the public origin and
  `X-Forwarded-Proto`. The backend never terminates public TLS itself.
- **Android app**: holds one `device_token` + one `device_key` per
  pairing in encrypted storage. Never sees the admin session.
- **FCM transport**: untrusted for confidentiality of secret-bearing
  content. Secret notification title/body are E2E-encrypted per device
  (P5 envelopes, `secret:true` fan-out — see "Secret notifications"
  below); non-secret notifications are plaintext on this leg by operator
  choice. Either way FCM sees routing metadata (destination tokens,
  timing, sizes) — hiding that is a non-goal (see below).
- **Widget source files**: mounted read-only; secret widgets carry
  operator-authored plaintext (`payload.json` = `{"secret": "..."}`),
  never committed, never logged — volume filesystem permissions are the
  v1 control (see "Secret widgets" below).

## Secrets inventory

| Secret | Lives | Notes |
| --- | --- | --- |
| `SESSION_SECRET` | env only; HMAC key for session cookies AND (domain-separated) CSRF tokens | ≥32 bytes, validated at boot |
| Admin credential | `ADMIN_PASSWORD_HASH` (bcrypt, preferred) or `ADMIN_PASSWORD` bootstrap | Login failures are generic; no user enumeration |
| Session token | cookie `pc_session` (`token.hmac`); only SHA-256 hash in `sessions` table | 24h TTL, HttpOnly/Secure/SameSite=Lax |
| Enroll token | QR PNG + `enroll_tokens.token_hash` (SHA-256 of raw) | Single-use, 15 min TTL (`EnrollTokenTTL`, single constant) |
| Device Bearer token | app storage; only `token_hash` in `devices` | Revocable per device; never in lists/logs |
| Per-device `secret_key` | `devices.secret_key` (32 bytes), app encrypted storage | Returned ONCE at enroll as `device_key`; never again |

## Secret widgets (P10 round-trip)

What is encrypted where:

- **At rest (server volume)**: operator-authored plaintext
  (`payload.json` = `{"secret": "<utf-8 string>"}`), strict shape
  (`secret` only, empty string legal). The snapshot holds these bytes in
  memory; they are never served verbatim and never logged. Trust = the
  mounted volume (read-only, operator-controlled, never committed).
- **At serve (per-device)**: for `type == secret` the handler encrypts the
  plaintext with the requesting device's `secret_key` via `backend/crypto`
  (AES-256-GCM, fresh 96-bit nonce per response), emitting the P5 wire
  envelope `{alg,kid,nonce,ciphertext}` as the widget payload with
  `kid == secret_ref == device_id` and `binding == widget id`
  (`AAD = UTF-8(kid) || 0x00 || UTF-8(binding)`, byte-identical
  cross-platform). Non-secrets are served verbatim. The envelope `kid` is
  a key selector, not secret material.
- **In transit**: TLS (reverse proxy terminator) plus the per-device layer
  above (defense in depth: proxy logs, caches, and ETag validators never
  see plaintext for secrets). Widget polling is HTTPS `GET`; the same
  crypto package serves notify fan-out where FCM is the untrusted leg.
- **At rest (app)**: the Room widget cache stores the encrypted envelope
  exactly as received (never decrypted-at-cache); the per-device
  `device_key` lives in `EncryptedSharedPreferences`. Decryption happens
  in memory per Glance render; plaintext is never cached or logged.

Oracle policy (single needs-refresh outcome):

- Missing/revoked/unknown device credential → `401 unauthorized` (same
  for all callers; no token material in logs).
- Foreign secret id (authenticated device B asking for device A's
  `secret_ref`) → `404 widget_not_found`, byte-identical to an unknown
  id (no existence oracle). The list omits foreign secrets entirely.
- Unwired device DB (`deviceDB == nil`, server misconfiguration) →
  `500 server misconfigured` on both list and single fetch, before auth
  (fail closed, never a panic, never plaintext).
- Own secret that cannot be encrypted (NULL/malformed `secret_key`,
  RNG/crypto failure) → `500 needs_refresh` on single fetch; the list
  omits that widget (fail closed, never plaintext, never a crash).
  `needs_refresh` is only ever an authenticated per-secret outcome —
  never the misconfiguration signal. Server logs note only the widget
  id, never key/nonce/plaintext.
- App decrypt: `kid`/binding mismatch, unknown `alg`, bad base64, wrong
  nonce length, auth-tag failure, and non-UTF-8 output ALL map to the one
  masked `•••••• needs refresh` UI state with no log distinction
  (kid-mismatch is indistinguishable from auth failure on screen and in
  logcat).
- Rotation (re-pair overwrites `secret_key`, no key history): old
  ciphertexts fail closed under the new key on both sides; the next poll
  fetches a freshly-encrypted envelope that decrypts. Cached pre-rotation
  envelopes show needs-refresh until then.

## Secret notifications (notify fan-out)

The `POST /api/v1/notify` request body stays operator plaintext (over
TLS), but the per-device fan-out copies obey an optional `"secret": true`
flag with the same envelope discipline as secret widgets:

- **At fan-out (per-device)**: when the flag is set, the server encrypts
  `title` and `body` separately with the target device's `secret_key` via
  `backend/crypto` (AES-256-GCM, fresh 96-bit nonce per field), with
  `kid == device_id` and `binding == "notify/<notification_id>/<field>"`
  (`NotifyBinding`; `AAD = UTF-8(kid) || 0x00 || UTF-8(binding)`,
  byte-identical cross-platform). The data payload carries the two P5
  envelopes as `title_env`/`body_env` plus `secret: "true"` — the plaintext
  `title`/`body` keys are absent, so Sender/FCM/logs never see them. Kind,
  priority, channel, image_url, and actions ride in the clear (not
  secret-bearing). Without the flag the payload is the plaintext
  `DataPayload` exactly as before.
- **Size budget**: the request validator measures the worst-case marshaled
  data payload (~3500B cap, FCM fits ~4KB) including envelope expansion,
  and rejects over-cap messages with `payload_too_long` — a truncated
  ciphertext must never ship.
- **In transit**: FCM is the untrusted leg (same posture as widgets): proxy
  logs, FCM retention, and transport caches see envelopes only. The app
  decrypts via the generalized `SecretCrypto` against its stored
  `device_key`; EVERY failure (missing key, kid/binding mismatch, bad
  envelope, auth failure) renders the existing blank-notification fallback
  (app-name title, no body) — one outcome, no oracle, nothing logged.
- **At rest (server)**: delivery history stores notification_id, device,
  kind, status, and sanitized error text only — driver error strings pass
  an allowlist + 500-rune truncation before storage, so transport output
  can never smuggle secrets or control bytes into the table. History
  pruning is COUNT-gated (skipped under 1000 rows) so quiet servers pay no
  scan cost.
- **Driver honesty**: stub drivers (`log`, `noop`) deliver nothing, so
  with real targets they answer `500 driver_unavailable` instead of lying
  with `accepted > 0`; a fully failing real driver (e.g. misconfigured
  FCM) does the same. `202 accepted: 0` is reserved for genuinely zero
  targets (unknown device_ids, empty registry). The default `LogSender`
  logs only notification_id/device/kind — full content dumps exist only
  behind an explicit `NOTIFY_DEBUG=1` (local debugging, never production).
Multi-secret cardinality (v1): one widget directory targets exactly one
device — `secret_ref` is a single `device_id`, required at load and
re-verified at serve. To show the same secret on N phones, duplicate the
directory per device (each copy is its own widget: own directory name =
own widget id, own `secret_ref`); there are no secret groups and no key
history. Rotation is per-row: re-pairing one device re-keys only that
row, and the other copies keep working under their own keys.

## Enroll-token lifecycle (P8)

1. Operator opens `GET /webui/pair` (session-gated). The server mints a
   32-byte random token, stores only its SHA-256 hash with `expires_at =
   now + EnrollTokenTTL` (15 min) and `used = 0`, and renders the QR for
   the canonical payload `{"enroll_url": ..., "enroll_token": ...}`.
2. Expired (`expires_at <= now`) and used rows are pruned on every issue;
   at most 10 outstanding unused tokens are kept (oldest dropped). A
   Harlem-style token-spray therefore cannot grow the table.
3. Issuance is rate-limited in memory: 30 per client IP per minute across
   `POST /api/v1/enroll-tokens` and `GET /webui/pair` (shared bucket).
   Exhaustion returns `429 rate_limited` (JSON on `/api/*`, plain text
   on `/webui/*`). The limiter is best-effort (restart clears it);
   correctness never depends on it. The key is `RemoteAddr` only
   (`enrollClientIP` in `pair_wire.go`); no `X-Forwarded-For` is
   trusted — deliberately, since that header is client-spoofable (same
   reason the Host fallback above is dev-only). Consequence behind a
   proxy: every client arrives with the proxy's IP, so all operators
   share the one bucket. At v1 pairing volumes that is harmless (a
   legitimate pairing needs ~1 token per scan), but the limiter is not
   per-operator attribution.
4. The phone POSTs the token to `POST /api/v1/enroll` with NO session
   cookie. Consumption is a single atomic
   `UPDATE ... SET used = 1 WHERE hash AND used = 0 AND unexpired` —
   exactly one concurrent winner; losers (and replays) get `410
   enroll_token_invalid`. The admin session is neither required nor
   accepted as a substitute: a logged-in browser without the QR token
   cannot enroll, and a phone with the token needs no session.
5. On success the server returns `device_id + device_token + device_key`
   exactly once (TLS-only) and rotates on re-pair: a scan whose
   `fcm_token` matches a live row overwrites that row's token hash and
   `secret_key` in place (old ciphertexts fail closed by design).

## Revocation immediacy

- `DELETE /api/v1/devices/{id}` and the WebUI revoke form set
  `revoked_at = now`. `AuthenticateDevice` only matches rows with
  `revoked_at IS NULL`, so revocation takes effect on the very next
  request against the same DB — no caches, no grace window.
- Revoke is idempotent: re-revoking an already-revoked device still
  returns success (204 / redirect). Unknown IDs return `404
  device_not_found` (API) or redirect to `/webui/pair?error=
  device_not_found` with an operator notice (WebUI) — never a silent
  no-op.
- Stolen-DB abuse: hashes alone do not yield Bearer tokens or enroll
  tokens; `secret_key` rows would decrypt future-at-rest payloads only
  if the DB itself leaks — at-rest DB encryption is a documented
  non-goal at v1 (see below); filesystem permissions + volume hygiene
  are the mitigation.

## Admin gating and stub removal (P8 reconciliation)

- P2 shipped with `pair_admin_stub.go` (fail-OPEN pass-through) so the
  pairing flow was verifiable before login existed. P8 DELETES the stub.
  Every operator route is now behind `webui.RequireAdmin(sessionStore)`:
  `/api/*` → `401 {"error":"unauthorized"}`, `/webui/*` → `302 /login`.
- `POST /api/v1/enroll` is deliberately OUTSIDE the gate (token-only).
- CSRF: the WebUI session cookie is `SameSite=Lax`, which still allows
  top-level-navigation CSRF. `POST /webui/pair/revoke` therefore
  requires a session-bound token
  (`HMAC(SESSION_SECRET, "webui-csrf-v1" || 0x00 || cookieValue)`)
  embedded in each revoke form (plus a page-level meta tag). Mismatches
  → `403 csrf_mismatch`; cross-session tokens are rejected. API routes
  are not CSRF-token-guarded (non-browser clients); `SameSite=Lax` +
  session-cookie auth is the v1 posture there.
- A nil session store fails closed (`denyAllSessions`); a nil device DB
  fails closed (500, never a panic).

## QR base URL: EXTERNAL_BASE_URL vs Host fallback

- Production MUST set `EXTERNAL_BASE_URL=https://<public-host>`: the QR
  `enroll_url` is built from it, so phones always learn the true public
  origin regardless of proxy headers.
- When unset, the server falls back to the request `Host` +
  `X-Forwarded-Proto` (TLS state otherwise). This is DEV-ONLY: `Host`
  is client-controlled and a misconfigured proxy can poison it into
  QR codes (phishing a phone to enroll against an attacker host —
  though the attacker still learns nothing without the single-use
  token, and tokens never appear in URLs/logs). The fallback logs a
  boot warning; config validation rejects non-origin values when set.

## Non-goals at v1 (explicit)

- Hiding notification/pairing metadata (timing, sizes, destination
  hosts) from FCM/Google or the network — out of scope.
- Device attestation / proof of genuine app — the enroll token is a
  bearer secret, not attestation.
- At-rest encryption of sqlite (`secret_key`, hashes) — filesystem and
  volume permissions are the v1 control.
- Cross-device key history — rotation overwrites; old ciphertexts are
  intentionally undecryptable.

## Abuse cases (how the design answers)

- **Stolen DB**: hashes don't yield tokens; `secret_key` exposure is
  bounded by filesystem controls (non-goal above, stated not missed).
- **Revoked device keeps polling**: `AuthenticateDevice` fails on the
  next request; fan-out skips revoked rows.
- **Rotated key (re-pair)**: old app key decrypts nothing new; new key
  decrypts nothing old; both sides fail closed with "needs refresh".
- **Compromised proxy rewriting Host**: QR would point at the wrong
  origin only when `EXTERNAL_BASE_URL` is unset (dev); production pins
  the origin in config.
- **CSRF against revoke**: needs the session cookie value to forge the
  token; `HttpOnly` denies reads, `SameSite=Lax` + token check denies
  blind POSTs.
