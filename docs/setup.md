# Operator setup guide

From empty machine to running backend. Pairing detail lives in
`docs/pairing.md`; wire contract in `docs/api-contract.md`; security
reasoning in `docs/threat-model.md`.

## 1. Secrets

Generate once per deployment. Never commit them (`.env`, `*.token`,
`secrets/` and `android/app/google-services.json` are gitignored).

```sh
openssl rand -hex 32   # SESSION_SECRET (≥32 bytes; hex string is fine)
htpasswd -bnBC 10 admin 'choose-a-long-password' | cut -d: -f2
# ^ ADMIN_PASSWORD_HASH. Bcrypt only ($2a/$2b/$2y accepted).
# Prefer the interactive form `htpasswd -nBC 10 admin` (prompts for the
# password, so it never lands in shell history); pipe through `cut`
# the same way. Only `-b` puts the password on the command line.
```

Rules enforced at boot (fail closed — the process exits otherwise):

- `ADMIN_USER` non-empty, not starting with `CHANGEME`.
- Either `ADMIN_PASSWORD_HASH` (preferred, not truncated/placeholder) or
  `ADMIN_PASSWORD` bootstrap (min 12 chars, not placeholder).
- `SESSION_SECRET` ≥ 32 bytes, not placeholder.
- `EXTERNAL_BASE_URL`, when set, must be a bare `http(s)` origin
  (no path/query/fragment); trailing slashes are trimmed.

Compose interpolation gotcha (verified): `docker compose` treats `$` in
`docker-compose.yml` as variable interpolation, so paste the bcrypt hash
with every `$` doubled (`$2y$10$…` → `$$2y$$10$$…`). `openssl` hex has no
`$`, but an `ADMIN_PASSWORD` containing `$` needs the same treatment.

## 2. First boot

```sh
cp backend/docker-compose.example.yml docker-compose.yml   # repo root!
# edit ADMIN_USER / ADMIN_PASSWORD_HASH / SESSION_SECRET
# (EXTERNAL_BASE_URL may stay commented for localhost testing)
docker compose up -d --build
curl http://127.0.0.1:8080/healthz        # {"status":"ok"}
docker compose logs backend               # drivers, widget count, warnings
```

What healthy looks like: `notify driver: notify.LogSender`,
`widgets: loaded 1 widget(s) from /widgets` (the shipped hello example),
`phone-companion backend listening on :8080`. With `EXTERNAL_BASE_URL`
unset you also get the `falls back to request Host (dev-only)` warning —
expected locally, fix before exposing (set it to the public origin).

Then sign in at `/login` and open `/webui/pair` (login-gated; anonymous
visits redirect to `/login`). Continue with `docs/pairing.md` to enroll
phones and send the first notification.

## 3. Backup (`/data`)

All state is the sqlite file at `$DB_PATH` (default
`/data/phone-companion.db`): devices + token hashes, per-device
`secret_key`s, sessions, enroll tokens, notification history. The example
compose keeps it in the named volume `phone-companion-data`.

- Named volume (default): `docker compose down` (stops writes), then copy
  the volume contents to backup storage before bringing the stack back.
  One-liner (substitute the real volume name from `docker volume ls` if
  your compose project prefixes it):

  ```sh
  docker compose down
  docker run --rm -v phone-companion-data:/data:ro -v "$PWD:/backup" \
    alpine tar -czf "/backup/phone-companion-$(date +%F).tgz" -C /data .
  docker compose up -d
  ```

- Recommended for production: bind-mount `/data` to a host directory so
  the db file is a regular file you can snapshot with your normal backup
  tooling (stop the stack first — never copy sqlite hot).

Single-file note: `OpenDB` (`backend/db.go`) sets no `journal_mode`
pragma, so the default delete-journal keeps the database one file —
there are no `-wal`/`-shm` sidecars to chase. That is why a
stopped-stack file (or volume-tar) copy is a complete backup.

Losing `/data` means re-pairing every phone (device keys live only there
and in each phone's encrypted storage) and losing delivery history.
Widget *definitions* (`/widgets` mount) are operator files — version them
separately.

## 4. Upgrades

State lives in the volume, code in the image, so upgrades are:

```sh
git pull
docker compose up -d --build
curl http://127.0.0.1:8080/healthz
docker compose logs backend | tail
```

Back up `/data` first (section 3). Migrations run automatically at boot
(`Migrate` in `db.go` + notification history). Rolling back the image
after a migration is not supported — restore the backup instead.
