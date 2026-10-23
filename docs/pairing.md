# Pairing, revocation, re-pair

How phones join, leave, and re-join. Wire shapes: `docs/api-contract.md`
("Key bootstrap at enrollment"); why it is built this way:
`docs/threat-model.md`.

## Pair a phone

1. Sign in to the WebUI, open `GET /webui/pair`. Loading the page mints
   a fresh 32-byte enroll token (single-use, 15 min expiry, at most 10
   outstanding) and renders it as a QR encoding
   `{"enroll_url": "<EXTERNAL_BASE_URL>/api/v1/enroll", "enroll_token": "…"}`.
2. In the app's Pair screen, scan the QR (base URL + token fill in; or
   type them manually) and tap Enroll. The phone POSTs
   `{"enroll_token","device_name","fcm_token"}` — no session cookie; the
   admin session is neither required nor accepted here.
3. The server consumes the token atomically (exactly one concurrent
   winner; replays get `410 enroll_token_invalid`) and returns `201
   {device_id, device_token, device_key}` — each shown exactly once. The
   app persists them to encrypted storage and never transmits the key.

Refresh `/webui/pair` for a new token per phone. Issuance is rate-limited
(30/min/IP across page + API) to stop token-spray.

## Pair a second phone, target one

Repeat the flow on another phone — the registry holds one row per device.
Then verify targeted delivery from an operator session (see root README
for the login-cookie setup):

```sh
# list device ids
curl -H "Cookie: pc_session=$COOKIE" http://127.0.0.1:8080/api/v1/devices
# notify exactly one of them
curl -H "Cookie: pc_session=$COOKIE" -H 'Content-Type: application/json' \
  -d '{"target":{"device_ids":["<id-1>"]},"kind":"note","title":"Half","body":"Only phone 1"}' \
  http://127.0.0.1:8080/api/v1/notify
# -> 202 {"notification_id":"...","accepted":1}
```

`accepted` counts per-device sends that succeeded; unknown ids are
skipped silently. `{"target":{"all":true}}` fans out to every live row.

## Revoke

- WebUI: the Revoke button per device row (`POST /webui/pair/revoke`,
  CSRF-guarded form).
- API: `DELETE /api/v1/devices/{id}` → `204`; unknown id → `404
  device_not_found` (WebUI redirects with `?error=device_not_found`).

Revocation is immediate (next request against the same DB fails `401`;
no caches, no grace window) and idempotent (re-revoking still succeeds).
A revoked phone's widgets poll and notify fan-out stop at once; the app
keeps its (now useless) credentials until re-pair or local clear.

## Re-pair and key rotation

Scanning a new QR from an already-paired phone (same `fcm_token`, or the
same device re-enrolling) overwrites that row's token hash AND
`secret_key` in place — a fresh 256-bit key. Consequences, all by design:

- Old secret-widget ciphertexts (cached on the phone or in transit) fail
  closed: the widget shows "needs refresh" until the next poll fetches a
  freshly encrypted envelope. Never plaintext, never a crash.
- App-side key loss (cleared storage, new phone) can only be fixed by
  re-pairing; there is no key recovery and no key history at v1.
- The enroll response is the ONLY time `device_token`/`device_key`
  exist outside their stores. If missed, re-pair.

## Troubleshooting

| Symptom | Cause → fix |
| --- | --- |
| `410 enroll_token_invalid` | Token used or older than 15 min → refresh `/webui/pair`, scan again |
| `409` on enroll | Device already enrolled → re-pair intentionally (rotates key) or use the existing credentials |
| `429 rate_limited` | >30 token issues/min/IP → wait a minute |
| Device `401` on widgets poll | Revoked (or unknown) credential → re-pair, or check the registry |
| Secret widget "needs refresh" | Key rotated or payload undecryptable → pull-to-refresh / wait for hourly poll; persists only if the device row lost its key (re-pair) |
| QR points at the wrong host | `EXTERNAL_BASE_URL` unset (Host fallback, dev-only) → set the public origin and reboot |
