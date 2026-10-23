# Threat model (STUB — full document lands with the pairing/widgets PRs)

Placeholder so `docs/api-contract.md` (key bootstrap, encrypted envelope)
has a stable reference to point at. The pairing PR owns this file.

Planned contents:

- Trust boundaries: browser WebUI, backend, reverse proxy / TLS
  terminator, Android app, FCM transport, widget source files.
- Secrets inventory: `SESSION_SECRET`, admin credential hash, per-device
  Bearer token hashes, per-device `secret_key` material, FCM service
  account. Where each lives at rest and why.
- Per-device payload encryption: why AES-256-GCM with per-message nonces,
  what AAD binding covers, rotation-on-repair consequences.
- Non-goals at v1 (e.g. hiding notification metadata from FCM/Google,
  device attestation) — stated explicitly, not discovered later.
- Abuse cases: stolen DB, revoked device, rotated key, compromised proxy.
