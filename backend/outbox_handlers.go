// Package main — device-side outbox endpoints (poll-based delivery).
//
//	GET  /api/v1/events/wait  — long-poll wake ("outbox" event or timeout)
//	GET  /api/v1/outbox       — list the caller's pending unexpired commands
//	POST /api/v1/commands/ack — mark owned commands received
//
// Auth is device Bearer ONLY: a valid OPERATOR_API_TOKEN Bearer is 403
// (operator credentials never act as a device); missing, unknown, or
// revoked Bearer is 401 (revoked devices authenticate nowhere, so they
// get nothing and their waiters never fire — enqueue paths skip revoked
// targets and signal per-device only).
//
// Errors use the shared writeAPIError writer (no endpoint-local writer).
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

// registerPushOutboxRoutes wires the operator app-push endpoint plus the
// three device outbox endpoints. Called from NewMux and from main's base
// mux (one definition so production and test routers agree). The sender
// is the configured notify driver: handleAppPush uses it for the
// best-effort FCM wake hint only (real FCM sender + stored token,
// log-only failures).
func registerPushOutboxRoutes(mux *http.ServeMux, db *sql.DB, sessions webui.SessionStore, csrf *webui.CSRFProtection, sender notify.Sender) {
	mux.Handle("POST /api/v1/app-push", operatorAdminChain(sessions, csrf, handleAppPush(db, sender)))
	mux.HandleFunc("GET /api/v1/events/wait", handleOutboxWait(db))
	mux.HandleFunc("GET /api/v1/outbox", handleOutboxPoll(db))
	mux.HandleFunc("POST /api/v1/commands/ack", handleCommandsAck(db))
}

// authenticateDeviceCaller enforces device-only auth: operator tokens get
// 403 even when otherwise valid; anything else goes through the standard
// device check (missing/invalid/revoked → 401, no oracle between them).
func authenticateDeviceCaller(w http.ResponseWriter, r *http.Request, db *sql.DB) (*Device, bool) {
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

// writeOutboxEvents renders a wait response: ["outbox"] on wake/pending,
// [] on timeout. Always 200 JSON.
func writeOutboxEvents(w http.ResponseWriter, wake bool) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	events := []string{}
	if wake {
		events = []string{"outbox"}
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"events": events})
}

// handleOutboxWait serves GET /api/v1/events/wait: immediate ["outbox"]
// when the caller has pending unacked unexpired items, else hold up to
// outboxWaitTimeout for a per-device signal, then [] on timeout.
//
// No DB connection is held while waiting: the pending count is queried
// first and released, then the request blocks on an in-memory per-device
// channel fed ONLY by the enqueue paths (signalOutboxWaiters after
// commit). Register-before-check ordering closes the missed-wake race: a
// signal landing after register is buffered; an enqueue landing before
// the check is observed by the check.
//
// Logs carry the device hash prefix at most — never the full id, never
// tokens, bodies, or URLs.
func handleOutboxWait(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := authenticateDeviceCaller(w, r, db)
		if !ok {
			return
		}
		ch := registerOutboxWaiter(dev.ID)
		defer unregisterOutboxWaiter(dev.ID, ch)
		// Pending check AFTER register (see race note above); the query
		// connection is released before any waiting starts.
		n, err := outboxPendingCount(db, dev.ID, utcNow())
		if err != nil {
			log.Printf("events-wait: count device=%s: %v", deviceLogID(dev.ID), err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		if n > 0 {
			writeOutboxEvents(w, true)
			return
		}
		timer := time.NewTimer(outboxWaitTimeout)
		defer timer.Stop()
		select {
		case <-ch:
			writeOutboxEvents(w, true)
		case <-timer.C:
			writeOutboxEvents(w, false)
		case <-r.Context().Done():
			// Client went away; nothing to write (defer unregisters).
		}
	}
}

// handleOutboxPoll serves GET /api/v1/outbox: the caller's own pending
// unexpired commands in rowid order. Only owned rows are ever returned
// (WHERE device_id = caller); other devices' rows are invisible, not
// just unackable. Headers pin no-store, private per contract. Expired
// rows are pruned best-effort on every fetch (log-only: the poll must
// stay available even if the prune DELETE fails).
func handleOutboxPoll(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := authenticateDeviceCaller(w, r, db)
		if !ok {
			return
		}
		now := utcNow()
		if err := pruneOutbox(db, now); err != nil {
			log.Printf("outbox: prune device=%s: %v", deviceLogID(dev.ID), err)
		}
		rows, err := db.Query(
			`SELECT command_id, push_id, type, payload_json FROM outbox
				WHERE device_id = ? AND status = ? AND expires_at > ?
				ORDER BY id`,
			dev.ID, outboxStatusPending, formatTime(now))
		if err != nil {
			log.Printf("outbox: poll device=%s: %v", deviceLogID(dev.ID), err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		defer rows.Close()
		commands := []map[string]string{}
		for rows.Next() {
			var commandID, pushID, typ, payloadJSON string
			if err := rows.Scan(&commandID, &pushID, &typ, &payloadJSON); err != nil {
				log.Printf("outbox: scan device=%s: %v", deviceLogID(dev.ID), err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
			cmd := map[string]string{}
			if payloadJSON != "" {
				_ = json.Unmarshal([]byte(payloadJSON), &cmd)
			}
			// Columns are canonical: the flat map repeats push_id/type
			// for app rows; authoritative values win either way.
			cmd["command_id"] = commandID
			cmd["push_id"] = pushID
			cmd["type"] = typ
			commands = append(commands, cmd)
		}
		if err := rows.Err(); err != nil {
			log.Printf("outbox: rows device=%s: %v", deviceLogID(dev.ID), err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store, private")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{"commands": commands})
	}
}

// ackRequestJSON is the POST /api/v1/commands/ack body: owned command ids
// to flip to received. Unknown/other-device ids are silently ignored
// (still 200); only malformed bodies are 400.
type ackRequestJSON struct {
	IDs []string `json:"ids"`
}

// handleCommandsAck serves POST /api/v1/commands/ack: mark the caller's
// OWN pending unexpired rows received. Idempotent (re-ack matches zero
// pending rows → acked:0). Unknown ids, other devices' ids, and expired
// rows match nothing — all silently ignored with 200 {"acked":N} where N
// counts owned rows actually flipped. Malformed bodies are 400.
func handleCommandsAck(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := authenticateDeviceCaller(w, r, db)
		if !ok {
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 1<<16)
		var req ackRequestJSON
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) || strings.Contains(err.Error(), "request body too large") {
				writeAPIError(w, http.StatusRequestEntityTooLarge, "body_too_large")
				return
			}
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		if len(req.IDs) > maxAckIDs {
			writeAPIError(w, http.StatusBadRequest, "too_many_ids")
			return
		}
		acked := 0
		if len(req.IDs) > 0 {
			placeholders := make([]string, 0, len(req.IDs))
			args := make([]any, 0, len(req.IDs)+3)
			args = append(args, dev.ID, outboxStatusPending, formatTime(utcNow()))
			for _, id := range req.IDs {
				placeholders = append(placeholders, "?")
				args = append(args, id)
			}
			res, err := db.Exec(
				`UPDATE outbox SET status = ? WHERE device_id = ? AND status = ? AND expires_at > ? AND command_id IN (`+
					strings.Join(placeholders, ",")+`)`,
				append([]any{outboxStatusReceived}, args...)...)
			if err != nil {
				log.Printf("commands-ack: device=%s: %v", deviceLogID(dev.ID), err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
			if n, err := res.RowsAffected(); err == nil {
				acked = int(n)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		_ = json.NewEncoder(w).Encode(map[string]any{"acked": acked})
	}
}
