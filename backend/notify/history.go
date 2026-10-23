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

// schemaHistoryV1 creates the delivery history table plus its lookup index.
// Additive-only: later PRs extend this table, never rewrite it.
//
//	notification_id  opaque id returned as 202 {"notification_id": ...}
//	device_id        target device (matches devices.id, no FK so history
//	                 survives device re-pair/deletion)
//	kind             message kind at send time (open vocabulary)
//	status           queued | failed
//	error            driver error text on failure, else ""
//	created_at       RFC 3339 UTC send-attempt time
const schemaHistoryV1 = `
CREATE TABLE IF NOT EXISTS notification_history (
	id INTEGER PRIMARY KEY AUTOINCREMENT,
	notification_id TEXT NOT NULL,
	device_id TEXT NOT NULL,
	kind TEXT NOT NULL DEFAULT '',
	status TEXT NOT NULL DEFAULT 'queued',
	error TEXT NOT NULL DEFAULT '',
	created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_notification_history_notification_id
	ON notification_history(notification_id);
`

// Migrate applies the history schema. Called from main alongside the base
// Migrate; safe to run repeatedly (CREATE IF NOT EXISTS).
func Migrate(db *sql.DB) error {
	_, err := db.Exec(schemaHistoryV1)
	return err
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
