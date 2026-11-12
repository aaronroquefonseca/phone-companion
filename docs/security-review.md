# Backend security assessment

Scope: the Go backend (`backend/`), its public surface as deployed behind
the TLS proxy, and the one dangerous capability added late — the operator
remote-shell channel ("adb link"). The Android client is covered by
`docs/threat-model.md`; this note is the operator-facing review the
maintainer asked for because the source is public.

Status: reviewed against `origin/main` on 2026-11-12. No critical finding
is open; two medium items are accepted with mitigations and are listed at
the end.

## What an attacker can reach

`https://phone.aaronroque.biz` (reverse proxy → backend `:8080` bound to
loopback). Three credential types, deliberately non-interchangeable:

| Credential | Where | Grants |
| --- | --- | --- |
| `SESSION_SECRET`-signed `pc_session` cookie | WebUI | operator pages + operator API (with CSRF proof) |
| `OPERATOR_API_TOKEN` Bearer | operator API | same operator actions, for curl/automation |
| per-device Bearer | app only | that device's outbox, widgets, ack, result upload |

Device tokens are rejected (`403`) on operator endpoints; operator
credentials cannot act as a device. Revocation is checked per request.

## Controls verified in code

- **Login**: bcrypt only (cost 10), generic failure copy (no username
  enumeration), per-IP rate limit before bcrypt work
  (`webui/login_limit.go`), CSRF-protected logout.
- **Sessions**: `pc_session` is `HttpOnly`, `SameSite=Lax`, `Secure` by
  default; only token hashes are stored; 24 h expiry. The insecure-cookie
  escape hatch is dev-only and warns at boot.
- **Enrollment**: single-use tokens, 15 min expiry, shared 30/min issuance
  bucket, tokens never listed after mint; enroll responses are shown once.
- **Operator writes**: session writes require a CSRF token or the
  `X-Requested-With` proof; the machine path uses the operator token. The
  remote-shell endpoints follow the same chain.
- **Boot fail-closed**: weak/missing `SESSION_SECRET` or
  `OPERATOR_API_TOKEN` (<32 bytes, `CHANGEME*`) refuse to boot; FCM
  credential files must be `0600`/`0400` and are never logged.
- **Input bounds**: request bodies capped (notify/app-push 1 MiB, shell
  queue 64 KiB, shell result 512 KiB); field lengths validated; `apk_url`
  https-only with no userinfo; command output capped at 64 KiB/stream.
- **SQL**: every query is parameterized; widget files are size-capped and
  symlinks refused; widget ids are constrained by regex.
- **Logs**: request outcomes and metadata only — never tokens, keys,
  nonces, plaintext secrets, widget secret contents, or shell command
  text (shell logs carry the command id and a device-prefix only).
- **Repo hygiene**: no `.env`, token, key, or `google-services.json` is
  tracked; `secrets/` is ignored; the compose example uses placeholders.
- **Transport**: TLS terminates at the proxy (Let's Encrypt, HSTS seen on
  the live host); the compose port binds `127.0.0.1:8080`.

## Remote-shell channel — the one high-impact capability

`POST /api/v1/shell` queues one bounded command for one active device;
the phone executes it as the Shizuku shell peer and posts the result to
`POST /api/v1/shell/result`; the operator reads it at
`GET /api/v1/shell/{id}`.

Consequences, stated plainly:

- **Backend operator credential = full control of the phone.** Shell
  privilege can read logs, change settings, take screenshots, simulate
  touch/keys, exfiltrate app-accessible files, and install/uninstall
  packages. Anyone who obtains the admin password, the session cookie, or
  `OPERATOR_API_TOKEN` owns the device while Shizuku is granted.
- Shell commands are **at-most-once** on the device: marked before
  execution, acked after, so a crash cannot re-run a destructive command.
  The operator sees a pending command if the result upload was lost.
- The per-device pending cap (20, same as app-push) bounds a token leak
  to a small queue. There is no per-command allowlist; that would defeat
  the stated purpose.
- Results are stored in sqlite (no at-rest encryption — see the threat
  model's non-goals). Output can contain secrets the command printed;
  treat the DB backup as sensitive.

Required operator practice, given the above:

1. Long, unique admin password (password manager); rotate on any doubt.
2. Keep `OPERATOR_API_TOKEN` in a 0600 file, never in shell history,
   screenshots, or chat.
3. Grant Shizuku only while the backend is correctly deployed behind TLS
   with the proxy locked down (`127.0.0.1` upstream).
4. Revoke on the phone (or unpair) if the backend is ever suspected
   compromised — Shizuku grant removal instantly disables the channel.

## Findings

**Medium — operator credential is now equivalent to device RCE.**
Mitigated by the credential separation above, the one-time user warning
in the app, and the queue cap. Accepted: this is the feature's design;
the security boundary is the backend login.

**Medium — shell output/DB at rest.** Command output can contain anything
the command prints, stored unencrypted in sqlite. Accepted (matches the
documented v1 non-goal); protect `/data` and backups.

**Low — no per-command audit trail.** Logs record command id + device
prefix + length, not the command text (deliberate: text can carry
secrets). If auditing is needed, store a hash of the command.

**Low — no IP allowlist or MFA on the WebUI.** Single-operator self-host;
strong password + TLS accepted. Adding TOTP is the cheapest upgrade.

**Low — enrollment limiter is per-`RemoteAddr`.** Behind a proxy all
clients share the proxy's bucket (documented); it bounds brute force but
not per-client fairness. Accepted for a single-operator deployment.

## Recommended next steps (not required for v1)

- TOTP on the WebUI login.
- Optional command allowlist mode for the shell channel (deny-by-default
  with explicit operator opt-in per command pattern).
- Encrypt shell results at rest, or auto-expire them with the outbox TTL.
- Reverse-proxy rate limit on `/api/v1/*` as defence in depth.
