// Package notify — delivery history storage (P4 NOTIFICATIONS).
//
// Own table, own migration: nothing here touches the devices schema owned by
// the pairing PR. One row per (notification, device) delivery keeps fan-out
// auditable; notification_id groups the rows of a single POST /api/v1/notify.
package notify

import (
	"context"
	"database/sql"
	"time"
)

// Delivery outcome values stored in notification_history.status.
const (
	StatusQueued = "queued"
	StatusFailed = "failed"
)

// schemaHistoryV1Table creates the delivery history table.
// Additive-only: later PRs extend this table, never rewrite it.
//
//	notification_id  opaque id returned as 202 {"notification_id": ...}
//	device_id        target device (matches devices.id, no FK so history
//	                 survives device re-pair/deletion)
//	kind             message kind at send time (open vocabulary)
//	status           queued | failed
//	error            driver error text on failure, else ""
//	created_at       RFC 3339 UTC send-attempt time
const schemaHistoryV1Table = `
CREATE TABLE IF NOT EXISTS notification_history (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	notification_id TEXT NOT NULL,
	device_id TEXT NOT NULL,
	kind TEXT NOT NULL DEFAULT '',
	status TEXT NOT NULL DEFAULT 'queued',
	error TEXT NOT NULL DEFAULT '',
	created_at TEXT NOT NULL
);
`

// schemaHistoryV1Index covers per-notification lookups. It is a separate
// statement from the table DDL: some drivers reject multi-statement Exec,
// so each statement gets its own Exec call below.
const schemaHistoryV1Index = `
CREATE INDEX IF NOT EXISTS idx_notification_history_notification_id
	ON notification_history(notification_id);
`

// Migrate applies the history schema. Called from main alongside the base
// Migrate; safe to run repeatedly (CREATE IF NOT EXISTS).
func Migrate(db *sql.DB) error {
	if _, err := db.Exec(schemaHistoryV1Table); err != nil {
		return err
	}
	_, err := db.Exec(schemaHistoryV1Index)
	return err
}

// Retention bounds for notification_history (P4 follow-up, P9). History is
// diagnostic, not a ledger: rows older than HistoryRetentionDays are
// dropped, and the table is capped at HistoryMaxRows rows (newest kept).
// Pruning runs best-effort from the notify handler — a prune failure is
// logged and never fails the request.
const (
	HistoryRetentionDays = 90
	HistoryMaxRows       = 20000
)

// PruneHistory deletes rows older than HistoryRetentionDays and trims the
// table to the HistoryMaxRows newest rows. Plain DELETEs, no schema change;
// safe to run alongside RecordDelivery.
//
// COUNT-first gate: both DELETEs are full-table scans, so each runs only
// when its COUNT shows work to do. The notify handler calls this after every
// fan-out; without the gate a small table would still pay two scans per
// notification.
func PruneHistory(ctx context.Context, db *sql.DB, now time.Time) error {
	cutoff := now.UTC().AddDate(0, 0, -HistoryRetentionDays).Format(time.RFC3339)
	var expired int
	if err := db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM notification_history WHERE created_at < ?`, cutoff).Scan(&expired); err != nil {
		return err
	}
	if expired > 0 {
		if _, err := db.ExecContext(ctx,
			`DELETE FROM notification_history WHERE created_at < ?`, cutoff); err != nil {
			return err
		}
	}
	var total int
	if err := db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM notification_history`).Scan(&total); err != nil {
		return err
	}
	if total > HistoryMaxRows {
		if _, err := db.ExecContext(ctx, `DELETE FROM notification_history WHERE id NOT IN
		(SELECT id FROM notification_history ORDER BY id DESC LIMIT ?)`, HistoryMaxRows); err != nil {
			return err
		}
	}
	return nil
}

// RecordDelivery appends one history row for a single send attempt.
func RecordDelivery(ctx context.Context, db *sql.DB, notificationID, deviceID, kind, status, errMsg string, at time.Time) error {
	_, err := db.ExecContext(ctx,
		`INSERT INTO notification_history
			(notification_id, device_id, kind, status, error, created_at)
			VALUES (?, ?, ?, ?, ?, ?)`,
		notificationID, deviceID, kind, status, errMsg, at.UTC().Format(time.RFC3339))
	return err
}
