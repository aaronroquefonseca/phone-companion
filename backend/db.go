// Package main — sqlite storage stub.
//
// P0 BASE skeleton: open + minimal schema placeholder only. No auth logic,
// no queries, no token handling. Pairing/auth PRs build on schemaDevicesV1.
package main

import (
	"database/sql"
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
func OpenDB(path string) (*sql.DB, error) {
	if dir := filepath.Dir(path); dir != "" {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			return nil, err
		}
	}
	// TODO (auth PR): set busy_timeout / journal_mode pragmas via DSN.
	return sql.Open("sqlite", path)
}

// Migrate applies the minimal schema stub. Future migrations are versioned
// here; each change must be additive and reviewed.
func Migrate(db *sql.DB) error {
	// TODO (pairing PR): migration version tracking table.
	if _, err := db.Exec(schemaDevicesV1); err != nil {
		return err
	}
	return migrateSessions(db)
}
