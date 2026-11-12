// Package main — remote shell channel (operator → device, poll-delivered).
//
// This is the "adb link": the operator queues one bounded shell command,
// the device polls it from the outbox, executes it through Shizuku's
// shell privilege, and posts the result back. It exists to let the
// operator (or an agent acting for them) pull logs, change settings, take
// screenshots (`screencap -p | base64`) or simulate input (`input tap`),
// using the same authenticated, audited transport as every other
// operator action — no inbound ports, no adb daemon on the wire.
//
// Security posture: queuing requires the operator credential (WebUI
// session + write guard, or OPERATOR_API_TOKEN); results are readable by
// the same. Granting Shizuku to the app is therefore equivalent to
// handing the operator account full control of the phone — the app warns
// about exactly that. Command CONTENT is never logged (it can carry
// secrets); logs carry the command id/device prefix only.
package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// Shell limits. Output is capped per stream so a chatty command cannot
// bloat the sqlite file or the response; the device enforces its own
// timeout too (see the Android executor).
const (
	outboxTypeShell     = "shell"
	maxShellCommandLen  = 8192
	maxShellOutputBytes = 64 * 1024
	minShellTimeoutMS   = 1_000
	maxShellTimeoutMS   = 120_000
	defaultShellTimeout = 30_000
)

// shellResultRow is one stored command outcome.
type shellResultRow struct {
	CommandID string `json:"command_id"`
	DeviceID  string `json:"device_id"`
	Status    string `json:"status"` // pending | done
	ExitCode  int    `json:"exit_code"`
	Stdout    string `json:"stdout"`
	Stderr    string `json:"stderr"`
	Truncated bool   `json:"truncated"`
	CreatedAt string `json:"created_at"`
}

// MigrateShell creates the result table. Idempotent, called at boot.
func MigrateShell(db *sql.DB) error {
	_, err := db.Exec(`
CREATE TABLE IF NOT EXISTS shell_results (
	command_id TEXT PRIMARY KEY,
	device_id  TEXT NOT NULL,
	status     TEXT NOT NULL,
	exit_code  INTEGER NOT NULL DEFAULT 0,
	stdout     TEXT NOT NULL DEFAULT '',
	stderr     TEXT NOT NULL DEFAULT '',
	truncated  INTEGER NOT NULL DEFAULT 0,
	created_at TEXT NOT NULL,
	finished_at TEXT
)`)
	return err
}

type shellQueueRequest struct {
	DeviceID  string `json:"device_id"`
	Command   string `json:"command"`
	TimeoutMS int    `json:"timeout_ms"`
}

// handleShellQueue serves POST /api/v1/shell (operator-only): validate,
// resolve the single target, enqueue one `shell` outbox row, and record a
// pending result row. 202 {"command_id"}.
func handleShellQueue(db *sql.DB, sender notify.Sender) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 64*1024)
		var req shellQueueRequest
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		deviceID := strings.TrimSpace(req.DeviceID)
		command := strings.TrimSpace(req.Command)
		if deviceID == "" {
			writeAPIError(w, http.StatusUnprocessableEntity, "missing_device_id")
			return
		}
		if command == "" {
			writeAPIError(w, http.StatusUnprocessableEntity, "missing_command")
			return
		}
		if len(command) > maxShellCommandLen {
			writeAPIError(w, http.StatusUnprocessableEntity, "command_too_long")
			return
		}
		timeout := req.TimeoutMS
		if timeout <= 0 {
			timeout = defaultShellTimeout
		}
		if timeout < minShellTimeoutMS || timeout > maxShellTimeoutMS {
			writeAPIError(w, http.StatusUnprocessableEntity, "invalid_timeout")
			return
		}
		dev, ok := lookupActiveDevice(r, db, deviceID)
		if !ok {
			writeAPIError(w, http.StatusNotFound, "device_not_found")
			return
		}
		// Per-device pending cap (same ceiling as app-push): an operator
		// token leak must not be able to pile up unbounded shell work.
		if n, cerr := outboxPendingCount(db, dev, utcNow()); cerr != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		} else if n >= maxOutboxPendingPerDevice {
			writeAPIError(w, http.StatusTooManyRequests, "too_many_pending")
			return
		}
		commandID, err := newNotificationID()
		if err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		now := utcNow()
		payload, _ := json.Marshal(map[string]any{
			"type":       outboxTypeShell,
			"command_id": commandID,
			"command":    command,
			"timeout_ms": timeout,
		})
		tx, err := db.Begin()
		if err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		defer func() { _ = tx.Rollback() }()
		if _, err := tx.Exec(
			`INSERT INTO shell_results (command_id, device_id, status, created_at)
			 VALUES (?, ?, 'pending', ?)`,
			commandID, dev, formatTime(now)); err != nil {
			log.Printf("shell: record command_id=%s: %v", commandID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		if err := insertOutboxRow(tx, commandID, commandID, dev, outboxTypeShell,
			string(payload), now, now.Add(outboxTTL)); err != nil {
			log.Printf("shell: enqueue command_id=%s: %v", commandID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		if err := tx.Commit(); err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		signalOutboxWaiters(dev)
		// Best-effort FCM wake so the command runs even when instant mode
		// is off: same silent hint shape as app-push, failures logged only.
		if _, isFCM := sender.(*notify.FCMSender); isFCM {
			var token string
			if err := db.QueryRow(`SELECT COALESCE(fcm_token,'') FROM devices WHERE id = ?`, dev).Scan(&token); err == nil && strings.TrimSpace(token) != "" {
				wake := map[string]string{"type": appPushWakeType, "push_id": commandID}
				if sendErr := sender.Send(r.Context(), notify.Device{ID: dev, FCMToken: token}, wake); sendErr != nil {
					log.Printf("shell: wake hint failed command_id=%s device=%s: %v", commandID, deviceLogID(dev), sendErr)
				}
			}
		}
		log.Printf("shell: queued command_id=%s device=%s len=%d timeout_ms=%d",
			commandID, deviceLogID(dev), len(command), timeout)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]string{"command_id": commandID})
	}
}

// handleShellResult serves POST /api/v1/shell/result (device-only).
func handleShellResult(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		r.Body = http.MaxBytesReader(w, r.Body, 512*1024)
		var req struct {
			CommandID string `json:"command_id"`
			ExitCode  int    `json:"exit_code"`
			Stdout    string `json:"stdout"`
			Stderr    string `json:"stderr"`
			Truncated bool   `json:"truncated"`
		}
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		dev, ok := authenticateDeviceCaller(w, r, db)
		if !ok {
			return
		}
		commandID := strings.TrimSpace(req.CommandID)
		if commandID == "" {
			writeAPIError(w, http.StatusUnprocessableEntity, "missing_command_id")
			return
		}
		if len(req.Stdout) > maxShellOutputBytes {
			req.Stdout = req.Stdout[:maxShellOutputBytes]
			req.Truncated = true
		}
		if len(req.Stderr) > maxShellOutputBytes {
			req.Stderr = req.Stderr[:maxShellOutputBytes]
			req.Truncated = true
		}
		res, err := db.Exec(
			`UPDATE shell_results
			    SET status = 'done', exit_code = ?, stdout = ?, stderr = ?, truncated = ?, finished_at = ?
			  WHERE command_id = ? AND device_id = ? AND status = 'pending'`,
			req.ExitCode, req.Stdout, req.Stderr, boolInt(req.Truncated),
			formatTime(utcNow()), commandID, dev.ID)
		if err != nil {
			log.Printf("shell: store result command_id=%s: %v", commandID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		if n, _ := res.RowsAffected(); n == 0 {
			// Unknown, other-device, or already-reported: ignore silently
			// (no oracle) but still 200 so the device can ack.
			log.Printf("shell: result ignored command_id=%s device=%s", commandID, deviceLogID(dev.ID))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"stored": true})
	}
}

// handleShellGet serves GET /api/v1/shell/{command_id} (operator-only):
// pending → 202 with no output; done → 200 with the stored result.
func handleShellGet(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		commandID := strings.TrimSpace(r.PathValue("command_id"))
		if commandID == "" {
			writeAPIError(w, http.StatusBadRequest, "missing_command_id")
			return
		}
		var row shellResultRow
		var truncated int
		err := db.QueryRow(
			`SELECT command_id, device_id, status, exit_code, stdout, stderr, truncated, created_at
			   FROM shell_results WHERE command_id = ?`, commandID).
			Scan(&row.CommandID, &row.DeviceID, &row.Status, &row.ExitCode,
				&row.Stdout, &row.Stderr, &truncated, &row.CreatedAt)
		if errors.Is(err, sql.ErrNoRows) {
			writeAPIError(w, http.StatusNotFound, "command_not_found")
			return
		}
		if err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		row.Truncated = truncated != 0
		w.Header().Set("Content-Type", "application/json")
		if row.Status == "pending" {
			w.WriteHeader(http.StatusAccepted)
		}
		_ = json.NewEncoder(w).Encode(row)
	}
}

// lookupActiveDevice returns the device id when it exists and is not
// revoked. Device ids are opaque; no existence oracle beyond the
// operator credential that already lists them.
func lookupActiveDevice(r *http.Request, db *sql.DB, deviceID string) (string, bool) {
	var id string
	err := db.QueryRow(
		`SELECT id FROM devices WHERE id = ? AND revoked_at IS NULL`, deviceID).Scan(&id)
	if err != nil {
		return "", false
	}
	return id, true
}

func boolInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

// registerShellRoutes wires the operator queue/read endpoints and the
// device result endpoint. Called from the production router and tests.
func registerShellRoutes(mux *http.ServeMux, db *sql.DB, sessions webui.SessionStore, csrf *webui.CSRFProtection, sender notify.Sender) {
	mux.Handle("POST /api/v1/shell", operatorAdminChain(sessions, csrf, handleShellQueue(db, sender)))
	mux.Handle("GET /api/v1/shell/{command_id}", operatorAdminChain(sessions, csrf, handleShellGet(db)))
	mux.HandleFunc("POST /api/v1/shell/result", handleShellResult(db))
}

// shellResultTimeout bounds how long the operator should wait before
// treating a command as lost; exported for the docs/test harness only.
const shellResultTimeout = 2 * time.Minute
