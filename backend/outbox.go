// Package main — poll-based push outbox (zero Google services required).
//
// POST /api/v1/app-push and POST /api/v1/notify enqueue one pending row
// per target device here; devices poll them via GET /api/v1/outbox, hold
// a wake via GET /api/v1/events/wait, and mark them received via
// POST /api/v1/commands/ack. The outbox is the delivery leg: FCM (when
// configured) is only a best-effort wake hint on the notify path, never
// required. See docs/api-contract.md ("Push outbox").
//
// SINGLE-INSTANCE ASSUMPTION: waiters block on an in-memory per-device
// channel fed only by the enqueue paths in this process. That is correct
// for the documented single-backend deployment; running two backends
// behind a load balancer needs sticky routing or a shared pubsub for
// wakeups (out of scope at v1) — polls still converge, only the wait
// fast-path would degrade to its timeout.
package main

import (
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"sync"
	"time"
)

// Outbox row types. Only app_install flows at v1; notify rows share the
// table so one poll/ack/wait surface serves both. The app ignores unknown
// types (forward-compat, same rule as unknown widget types).
const (
	outboxTypeAppInstall = "app_install"
	outboxTypeNotify     = "notify"
)

// Outbox row statuses: pending rows are pollable; ack flips to received.
// Received rows linger until TTL prune (they are never re-polled).
const (
	outboxStatusPending  = "pending"
	outboxStatusReceived = "received"
)

// Outbox bounds (contract, see docs/api-contract.md):
//
//	outboxTTL caps row lifetime; pruneOutbox deletes expired rows on every
//	enqueue and every outbox poll.
//	maxOutboxPendingPerDevice caps queue depth per device; enqueueing
//	beyond it is 429 too_many_pending on the app path (nothing queued)
//	and a per-device skip on the notify path (notify is always 202).
const (
	outboxTTL                   = 7 * 24 * time.Hour
	maxOutboxPendingPerDevice   = 20
	maxAckIDs                   = 1000
	outboxWaitTimeoutDefault    = 300 * time.Second
	outboxWaitChannelBufferSize = 1
)

// outboxWaitTimeout is the long-poll hold ceiling for GET
// /api/v1/events/wait. A var (not const) so tests use short timeouts —
// production must never wait out real 300s sleeps in tests.
var outboxWaitTimeout = outboxWaitTimeoutDefault

// schemaOutboxV1Table is the per-device pending-command table. One row per
// (push, device); the push record in `pushes` groups a fan-out, these rows
// are the delivery leg each device polls.
//
//	id           row id (poll ordering: ORDER BY id)
//	command_id   opaque per-device command id (ack ids; minted like
//	             notification ids, never a rowid leak)
//	push_id      fan-out group id returned to the operator (app-push) or
//	             the notification id (notify path)
//	device_id    target device (REFERENCES devices(id): intent only —
//	             foreign_keys stays off like the rest of this codebase, so
//	             history-style survival needs no enforcement)
//	type         app_install | notify (v1; readers ignore unknown types)
//	payload_json canonical flat-string map (json.Marshal of
//	             map[string]string: Go sorts map keys, so the bytes are
//	             deterministic for equal maps)
//	created_at / expires_at  RFC 3339 UTC; expires_at = created + 7d TTL
//	status       pending | received
const schemaOutboxV1Table = `
CREATE TABLE IF NOT EXISTS outbox (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	command_id TEXT NOT NULL UNIQUE,
	push_id TEXT NOT NULL,
	device_id TEXT NOT NULL REFERENCES devices(id),
	type TEXT NOT NULL DEFAULT 'app_install',
	payload_json TEXT NOT NULL DEFAULT '{}',
	created_at TEXT NOT NULL,
	expires_at TEXT NOT NULL,
	status TEXT NOT NULL DEFAULT 'pending'
);
`

// schemaOutboxV1Indexes covers the poll/count paths: per-device pending
// lookup in rowid order, plus command_id for ack.
const schemaOutboxV1Indexes = `
CREATE INDEX IF NOT EXISTS idx_outbox_device_pending
	ON outbox(device_id, status, expires_at, id);
CREATE INDEX IF NOT EXISTS idx_outbox_command_id
	ON outbox(command_id);
`

// schemaPushesV1Table is the fan-out audit log: one row per accepted
// operator request, written BEFORE any per-device row (audit-before-mutate
// — a DB failure answers 500 with nothing queued, documented at each
// enqueue site). accepted is UPDATEd after the rows land so a crash
// between the two leaves accepted:0 (fail-closed direction: never claim
// more deliveries than rows written).
//
//	push_id    opaque id returned as 202 {"push_id"} / {"notification_id"}
//	type       app_install | notify
//	created_at RFC 3339 UTC accept time
//	operator   "operator-token" or "session:<username>" — never a secret
//	target     canonical JSON of the request target ({"all":true} or
//	           {"device_ids":[...]}, computed once at record time from the
//	           validated struct — no parallel TargetJS field to drift)
//	accepted   devices queued (final count)
const schemaPushesV1Table = `
CREATE TABLE IF NOT EXISTS pushes (
	push_id TEXT PRIMARY KEY,
	type TEXT NOT NULL DEFAULT 'app_install',
	created_at TEXT NOT NULL,
	operator TEXT NOT NULL DEFAULT '',
	target TEXT NOT NULL DEFAULT '',
	accepted INTEGER NOT NULL DEFAULT 0
);
`

// MigrateOutbox applies the outbox schema. Called from main alongside the
// base and notify migrations; safe to run repeatedly. Tests that enqueue
// or poll must call it (openNotifyTestDB-style helpers do).
func MigrateOutbox(db *sql.DB) error {
	if _, err := db.Exec(schemaOutboxV1Table); err != nil {
		return err
	}
	if _, err := db.Exec(schemaOutboxV1Indexes); err != nil {
		return err
	}
	_, err := db.Exec(schemaPushesV1Table)
	return err
}

// canonicalPayloadJSON serializes a flat string map deterministically
// (encoding/json sorts map[string]string keys). Both enqueue paths funnel
// through here — one canonical builder, never forked per endpoint.
func canonicalPayloadJSON(payload map[string]string) string {
	raw, err := json.Marshal(payload)
	if err != nil {
		return "{}"
	}
	return string(raw)
}

// canonicalTargetJSON renders the validated target for the pushes audit
// row. Computed once at record time from the validated struct.
func canonicalTargetJSON(all bool, deviceIDs []string) string {
	if all {
		return `{"all":true}`
	}
	ids := deviceIDs
	if ids == nil {
		ids = []string{}
	}
	raw, err := json.Marshal(map[string]any{"device_ids": ids})
	if err != nil {
		return `{"device_ids":[]}`
	}
	return string(raw)
}

// pruneOutbox deletes expired rows (any status — received rows are terminal
// and pending rows past TTL are undeliverable). Best-effort at call sites
// that must stay available (notify enqueue, device poll); load-bearing
// inside the app-push transaction, where a failure aborts closed.
func pruneOutbox(db *sql.DB, now time.Time) error {
	_, err := db.Exec(`DELETE FROM outbox WHERE expires_at <= ?`, formatTime(now))
	return err
}

// outboxPendingCount reports a device's pollable depth (pending, unexpired).
func outboxPendingCount(db *sql.DB, deviceID string, now time.Time) (int, error) {
	var n int
	err := db.QueryRow(
		`SELECT COUNT(*) FROM outbox WHERE device_id = ? AND status = ? AND expires_at > ?`,
		deviceID, outboxStatusPending, formatTime(now)).Scan(&n)
	return n, err
}

// outboxDeviceFull reports whether a device is at the per-device cap.
// Callers map full=true to 429 (app-push, whole request fails closed with
// nothing queued) or to a per-device skip (notify, always 202).
func outboxDeviceFull(db *sql.DB, deviceID string, now time.Time) (bool, error) {
	n, err := outboxPendingCount(db, deviceID, now)
	if err != nil {
		return false, err
	}
	return n >= maxOutboxPendingPerDevice, nil
}

// outboxQuerier is the *sql.DB / *sql.Tx surface the enqueue helpers need,
// so the app path can run prune + cap-check + inserts atomically.
type outboxQuerier interface {
	Exec(query string, args ...any) (sql.Result, error)
	QueryRow(query string, args ...any) *sql.Row
}

// recordPush inserts the fan-out audit row (accepted starts 0; callers
// UPDATE it after the per-device rows land).
func recordPush(q outboxQuerier, pushID, typ, operator, target string, now time.Time) error {
	_, err := q.Exec(
		`INSERT INTO pushes (push_id, type, created_at, operator, target, accepted)
			VALUES (?, ?, ?, ?, ?, 0)`,
		pushID, typ, formatTime(now), operator, target)
	return err
}

// updatePushAccepted records the final queued count. A failure here is
// logged, never returned: the rows already exist, so the response must
// report delivery, not invite a duplicate push with a 500.
func updatePushAccepted(q outboxQuerier, pushID string, accepted int) error {
	_, err := q.Exec(`UPDATE pushes SET accepted = ? WHERE push_id = ?`, accepted, pushID)
	return err
}

// insertOutboxRow queues one pending command for one device. expiresAt is
// set by the caller (created + outboxTTL).
func insertOutboxRow(q outboxQuerier, commandID, pushID, deviceID, typ, payloadJSON string, now, expiresAt time.Time) error {
	_, err := q.Exec(
		`INSERT INTO outbox
			(command_id, push_id, device_id, type, payload_json, created_at, expires_at, status)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		commandID, pushID, deviceID, typ, payloadJSON,
		formatTime(now), formatTime(expiresAt), outboxStatusPending)
	return err
}

// deviceLogID renders a device id for logs: the first 8 hex chars of its
// SHA-256 (encoding/hex, no bespoke loops). Device ids are opaque but
// stable identifiers — new outbox code logs at most this prefix, never
// the full id alongside request context.
func deviceLogID(deviceID string) string {
	sum := sha256.Sum256([]byte(deviceID))
	return hex.EncodeToString(sum[:])[:8]
}

// --- Waiter registry ----------------------------------------------------

// outboxWaiters maps deviceID -> set of live long-poll channels. Fed ONLY
// by the enqueue paths (signalOutboxWaiters, called after rows commit);
// ack and prune never wake anyone (nothing new to poll). In-memory only:
// see the single-instance note at the top of this file.
var (
	outboxWaitMu  sync.Mutex
	outboxWaiters = make(map[string]map[chan struct{}]struct{})
)

// registerOutboxWaiter subscribes one long-poll for wakeups on deviceID.
// The caller MUST defer unregisterOutboxWaiter.
func registerOutboxWaiter(deviceID string) chan struct{} {
	ch := make(chan struct{}, outboxWaitChannelBufferSize)
	outboxWaitMu.Lock()
	set := outboxWaiters[deviceID]
	if set == nil {
		set = make(map[chan struct{}]struct{})
		outboxWaiters[deviceID] = set
	}
	set[ch] = struct{}{}
	outboxWaitMu.Unlock()
	return ch
}

// unregisterOutboxWaiter drops a long-poll subscription (timeout, wake, or
// client disconnect). Buffered signals left behind are GC'd with ch.
func unregisterOutboxWaiter(deviceID string, ch chan struct{}) {
	outboxWaitMu.Lock()
	if set := outboxWaiters[deviceID]; set != nil {
		delete(set, ch)
		if len(set) == 0 {
			delete(outboxWaiters, deviceID)
		}
	}
	outboxWaitMu.Unlock()
}

// signalOutboxWaiters wakes every live long-poll for deviceID. Call only
// AFTER the outbox rows commit (enqueue paths); non-blocking so a slow
// reader can never stall an operator request.
func signalOutboxWaiters(deviceID string) {
	outboxWaitMu.Lock()
	defer outboxWaitMu.Unlock()
	for ch := range outboxWaiters[deviceID] {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

// resetOutboxWaiters drops all subscriptions. Tests only: keeps waiter
// state hermetic between cases that override outboxWaitTimeout.
func resetOutboxWaiters() {
	outboxWaitMu.Lock()
	outboxWaiters = make(map[string]map[chan struct{}]struct{})
	outboxWaitMu.Unlock()
}
