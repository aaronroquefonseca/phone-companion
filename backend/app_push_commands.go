// Package main — GET /api/v1/commands + POST /api/v1/commands/ack
// (poll-based remote APK install push, device side).
//
// The app already polls the backend (hourly WorkManager + manual refresh);
// these endpoints let it pick up operator-queued APK-install directives
// on its next poll with no push transport involved: no FCM, no Google
// services, no google-services.json anywhere in this flow.
//
//   - GET /api/v1/commands returns the caller's pending (unexpired,
//     unacked) commands as flat string data maps in the exact shape the
//     FCM-based variant fanned out (plus a per-device command_id), so the
//     client parser is transport-agnostic.
//   - POST /api/v1/commands/ack {command_id} marks that device's row
//     received (idempotent: re-acking an already-acked row still 200s).
//
// Auth is the per-device Bearer token ONLY (AuthenticateDevice; revoked
// and unknown tokens fail closed with 401, exactly like the widgets
// poll). The operator API token is a valid credential on the wrong
// surface here, so it is rejected with 403 — never accepted, never
// mistaken for device intent.
//
// Caps: at most maxAppPushPendingPerDevice pending commands per device
// (the enqueue side rejects with 429 too_many_pending); rows older than
// appPushCommandTTL are expired — pruned best-effort on enqueue and on
// every poll, and never served.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

// Poll-side bounds: per-device pending budget and command lifetime. The
// TTL prune runs on enqueue (before the cap check, so expired rows never
// count) and on every poll (so dead rows never sit past their lifetime).
const (
	maxAppPushPendingPerDevice = 20
	appPushCommandTTL          = 7 * 24 * time.Hour
)

// schemaAppPushCommandsV1Table is the per-device pending-command queue:
// one row per resolved device per push. No APK bytes are stored — only
// the validated directive fields the app needs to download and verify
// the APK itself.
//
//	command_id per-device opaque id the ack endpoint consumes
//	push_id    parent audit id (app_pushes.push_id)
//	device_id  the single target device (polls filter on this; a device
//	           only ever sees its own rows)
//	apk_url / sha256 / package / label  validated request fields
//	version    optional request field, NULL when absent
//	created_at RFC 3339 UTC enqueue time (drives the TTL)
//	acked_at   RFC 3339 UTC ack time, NULL while pending
const schemaAppPushCommandsV1Table = `
CREATE TABLE IF NOT EXISTS app_push_commands (
	command_id TEXT PRIMARY KEY,
	push_id TEXT NOT NULL,
	device_id TEXT NOT NULL,
	apk_url TEXT NOT NULL DEFAULT '',
	sha256 TEXT NOT NULL DEFAULT '',
	package TEXT NOT NULL DEFAULT '',
	version TEXT,
	label TEXT NOT NULL DEFAULT '',
	created_at TEXT NOT NULL,
	acked_at TEXT
);
CREATE INDEX IF NOT EXISTS idx_app_push_commands_device ON app_push_commands (device_id);
`

// MigrateAppPushCommands applies the pending-command queue schema. Called
// from main alongside the other migrations; safe to run repeatedly.
func MigrateAppPushCommands(db *sql.DB) error {
	_, err := db.Exec(schemaAppPushCommandsV1Table)
	return err
}

// appPushCommandsCutoff renders the created_at boundary: rows at or below
// it are expired.
func appPushCommandsCutoff(now time.Time) string {
	return formatTime(now.Add(-appPushCommandTTL))
}

// pruneAppPushCommands deletes expired rows (acked or not). Best-effort:
// callers log failures and continue, mirroring notify's history prune.
func pruneAppPushCommands(ctx context.Context, db *sql.DB, now time.Time) error {
	_, err := db.ExecContext(ctx,
		`DELETE FROM app_push_commands WHERE created_at <= ?`,
		appPushCommandsCutoff(now))
	return err
}

// appPushOverCap reports whether any target device already holds a full
// pending budget of unexpired unacked commands. Expired rows are pruned
// by the caller first, so they never count here.
func appPushOverCap(ctx context.Context, db *sql.DB, devices []notify.Device, now time.Time) (bool, error) {
	cutoff := appPushCommandsCutoff(now)
	for _, d := range devices {
		var n int
		if err := db.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM app_push_commands
			 WHERE device_id = ? AND acked_at IS NULL AND created_at > ?`,
			d.ID, cutoff).Scan(&n); err != nil {
			return false, err
		}
		if n >= maxAppPushPendingPerDevice {
			return true, nil
		}
	}
	return false, nil
}

// insertAppPushCommandTx queues one pending row inside the caller's
// transaction (see insertAppPushEnqueue: audit + rows commit atomically).
func insertAppPushCommandTx(ctx context.Context, tx *sql.Tx, commandID, pushID, deviceID string, req appPushRequest, now time.Time) error {
	var v any
	if req.Version != "" {
		v = req.Version
	}
	_, err := tx.ExecContext(ctx,
		`INSERT INTO app_push_commands
			(command_id, push_id, device_id, apk_url, sha256, package, version, label, created_at, acked_at)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)`,
		commandID, pushID, deviceID,
		req.APKURL, req.SHA256, req.Package, v, req.Label, formatTime(now))
	return err
}

// requireDeviceBearerForCommands authenticates the device Bearer token
// for the poll/ack surface. The operator token is rejected with 403 (a
// valid credential on the wrong surface); missing/invalid/revoked device
// tokens fail with 401 without distinguishing which (no oracle, same as
// the widgets poll).
func requireDeviceBearerForCommands(db *sql.DB, w http.ResponseWriter, r *http.Request) (*Device, bool) {
	if db == nil {
		writeAPIError(w, http.StatusInternalServerError, "server misconfigured")
		return nil, false
	}
	if tok, ok := bearerToken(r); ok && validOperatorToken(tok) {
		writeAPIError(w, http.StatusForbidden, "forbidden")
		return nil, false
	}
	dev, err := AuthenticateDevice(db, r)
	if err != nil {
		writeAPIError(w, http.StatusUnauthorized, "unauthorized")
		return nil, false
	}
	return dev, true
}

// appPushCommandRow is one pending row read back for the poll response.
type appPushCommandRow struct {
	commandID string
	pushID    string
	apkURL    string
	sha256    string
	pkg       string
	version   string
	label     string
}

// queryAppPushPending lists the device's pending (unexpired, unacked)
// commands oldest-first.
func queryAppPushPending(ctx context.Context, db *sql.DB, deviceID string, now time.Time) ([]appPushCommandRow, error) {
	cutoff := appPushCommandsCutoff(now)
	rows, err := db.QueryContext(ctx,
		`SELECT command_id, push_id, apk_url, sha256, package, version, label
		 FROM app_push_commands
		 WHERE device_id = ? AND acked_at IS NULL AND created_at > ?
		 ORDER BY created_at, command_id`,
		deviceID, cutoff)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []appPushCommandRow{}
	for rows.Next() {
		var c appPushCommandRow
		var version sql.NullString
		if err := rows.Scan(&c.commandID, &c.pushID, &c.apkURL, &c.sha256, &c.pkg, &version, &c.label); err != nil {
			return nil, err
		}
		if version.Valid {
			c.version = version.String
		}
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return out, nil
}

// handleAppPushCommandsPoll serves GET /api/v1/commands: the caller's
// pending commands only. Expired rows are pruned first (best-effort) and
// never served; revoked devices already 401 in auth, so their rows sit
// unserved until the TTL prune collects them.
func handleAppPushCommandsPoll(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := requireDeviceBearerForCommands(db, w, r)
		if !ok {
			return
		}
		ctx := r.Context()
		now := time.Now()
		if err := pruneAppPushCommands(ctx, db, now); err != nil {
			log.Printf("app-push: prune commands: %v", err)
		}
		pending, err := queryAppPushPending(ctx, db, dev.ID, now)
		if err != nil {
			log.Printf("app-push: poll commands: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		commands := make([]map[string]string, 0, len(pending))
		for _, c := range pending {
			cmd := appPushPayload(c.pushID, appPushRequest{
				APKURL:  c.apkURL,
				SHA256:  c.sha256,
				Package: c.pkg,
				Version: c.version,
				Label:   c.label,
			})
			cmd["command_id"] = c.commandID
			commands = append(commands, cmd)
		}
		w.Header().Set("Cache-Control", "no-store, private")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"commands": commands})
	}
}

// appPushAckRequest is the POST /api/v1/commands/ack body: the per-device
// command_id from the poll response.
type appPushAckRequest struct {
	CommandID string `json:"command_id"`
}

// handleAppPushCommandsAck serves POST /api/v1/commands/ack: marks the
// caller's row received. Idempotent: re-acking an already-acked row still
// 200s. Unknown ids — or ids queued for another device — are 404
// command_not_found with no distinction (no oracle across devices).
func handleAppPushCommandsAck(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := requireDeviceBearerForCommands(db, w, r)
		if !ok {
			return
		}
		// Small fixed body (one id); the device-token backfill endpoint
		// uses the same 64 KiB bound.
		r.Body = http.MaxBytesReader(w, r.Body, 1<<16)
		var req appPushAckRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) || strings.Contains(err.Error(), "request body too large") {
				writeAPIError(w, http.StatusRequestEntityTooLarge, "body_too_large")
				return
			}
			writeAPIError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		commandID := strings.TrimSpace(req.CommandID)
		if commandID == "" {
			writeAPIError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		res, err := db.ExecContext(r.Context(),
			`UPDATE app_push_commands SET acked_at = ? WHERE command_id = ? AND device_id = ?`,
			formatTime(time.Now()), commandID, dev.ID)
		if err != nil {
			log.Printf("app-push: ack command: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		n, err := res.RowsAffected()
		if err != nil {
			log.Printf("app-push: ack rows: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		if n != 1 {
			writeAPIError(w, http.StatusNotFound, "command_not_found")
			return
		}
		w.Header().Set("Cache-Control", "no-store, private")
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]bool{"acked": true})
	}
}
