// Package main — sqlite storage stub.
//
// P0 BASE skeleton: open + minimal schema placeholder only. No auth logic,
// no queries, no token handling. Pairing/auth PRs build on schemaDevicesV1.
package main

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"

	_ "modernc.org/sqlite"
)

// schemaDevicesV1 is the minimal devices table placeholder. Columns are the
// contract other PRs program against; constraints and indexes evolve there.
//
//	id          device identifier (server-issued, opaque)
//	name        human-friendly device label shown in the WebUI
//	token_hash  hash of the per-device Bearer token (never the raw token)
//	secret_key  per-device payload-encryption key material, generated at
//	            enroll and delivered once (wire contract: docs/api-contract.md
//	            "Key bootstrap at enrollment"; rationale: docs/threat-model.md)
//
// TODO (pairing PR): created_at/revoked_at columns, revocation indexes.
// TODO (auth PR): decide at-rest protection for secret_key.
const schemaDevicesV1 = `
CREATE TABLE IF NOT EXISTS devices (
	id TEXT PRIMARY KEY,
	name TEXT NOT NULL,
	token_hash TEXT NOT NULL,
	secret_key BLOB
);
`

// OpenDB opens the sqlite database file, creating parent state as needed.
// The driver is pure Go (modernc.org/sqlite) so the binary stays static and
// runs in distroless/scratch.
//
// Permissions: the parent dir is created 0700 and the DB file is tightened
// to 0600 after open (pre-created 0600 when missing), because the DB holds
// token hashes and per-device key material. Run the process with umask 077
// so driver-created sidecar files (journal/WAL) default to owner-only too;
// the explicit Chmod below is defense-in-depth for pre-existing files.
func OpenDB(path string) (*sql.DB, error) {
	if dir := filepath.Dir(path); dir != "" && dir != "." && dir != string(filepath.Separator) {
		// Refuse symlinked parent dirs fail-closed: MkdirAll/Chmod follow
		// links, so managing through one would retarget outside the state
		// dir. Lstat (not Stat) sees the link itself.
		if fi, err := os.Lstat(dir); err != nil {
			if !os.IsNotExist(err) {
				return nil, err
			}
		} else if fi.Mode()&os.ModeSymlink != 0 {
			return nil, fmt.Errorf("refusing to open database in symlinked dir %q", dir)
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, err
		}
		// Tighten pre-existing dirs left behind by a looser umask or an
		// earlier version (MkdirAll is a no-op when the dir exists). "."
		// and the filesystem root are never managed: opening a bare
		// filename must not chmod the process cwd, and "/" is never a
		// state dir.
		if err := os.Chmod(dir, 0o700); err != nil {
			return nil, err
		}
	}
	// sql.Open is lazy (no file I/O yet), so pre-create the file with
	// owner-only mode when absent; Chmod then tightens pre-existing files
	// left behind by a looser umask or an earlier version.
	if f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600); err != nil {
		return nil, err
	} else if err := f.Close(); err != nil {
		return nil, err
	}
	if err := os.Chmod(path, 0o600); err != nil {
		return nil, err
	}
	// TODO (auth PR): set busy_timeout / journal_mode pragmas via DSN.
	return sql.Open("sqlite", path)
}

// Migrate applies the minimal schema stub. Future migrations are versioned
// here; each change must be additive and reviewed.
//
// Pairing (P2) additions, all additive/idempotent: created_at + revoked_at +
// fcm_token columns on devices (revocation index per the TODO above),
// enroll_tokens table for single-use QR pairing tokens, and a token_hash
// index for device Bearer auth.
func Migrate(db *sql.DB) error {
	// TODO (pairing PR): migration version tracking table.
	if _, err := db.Exec(schemaDevicesV1); err != nil {
		return err
	}
	for _, col := range []string{"created_at", "revoked_at", "fcm_token"} {
		if err := ensureDeviceColumn(db, col); err != nil {
			return err
		}
	}
	if _, err := db.Exec(`CREATE TABLE IF NOT EXISTS enroll_tokens (
		token_hash TEXT PRIMARY KEY,
		expires_at TEXT NOT NULL,
		used INTEGER NOT NULL DEFAULT 0,
		created_at TEXT NOT NULL
	)`); err != nil {
		return err
	}
	if _, err := db.Exec(`CREATE INDEX IF NOT EXISTS idx_devices_token_hash ON devices (token_hash)`); err != nil {
		return err
	}
	return migrateSessions(db)
}

// ensureDeviceColumn adds a nullable TEXT column to devices if missing, so
// Migrate stays idempotent across P0 databases and re-runs.
func ensureDeviceColumn(db *sql.DB, col string) error {
	rows, err := db.Query(`PRAGMA table_info(devices)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	var cid int
	var name, ctype string
	var notnull, pk int
	var dflt sql.NullString
	for rows.Next() {
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return err
		}
		if name == col {
			return rows.Err()
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = db.Exec(`ALTER TABLE devices ADD COLUMN ` + col + ` TEXT`)
	return err
}
