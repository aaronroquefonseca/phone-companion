// Package main — POST /api/v1/notify handler (P4 NOTIFICATIONS).
//
// Request/response shapes follow docs/api-contract.md ("notify" section).
// Target resolution queries the devices table directly here so this PR stays
// independent of the pairing/auth PRs: only the P0-stable columns
// (id, name) are selected. Admin gating wraps at registration time via
// requireAdminNotifyStub (see notify_auth_stub.go).
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

// notifyTargetJSON mirrors the contract's target union: exactly one of
// {"all":true} or {"device_ids":[...]}. Pointer bool distinguishes an
// explicit false/absent from true.
type notifyTargetJSON struct {
	All       *bool    `json:"all"`
	DeviceIDs []string `json:"device_ids"`
}

type notifyActionJSON struct {
	ID    string `json:"id"`
	Title string `json:"title"`
}

// notifyRequestJSON mirrors the contract's notify request body.
type notifyRequestJSON struct {
	Target   notifyTargetJSON   `json:"target"`
	Kind     string             `json:"kind"`
	Title    string             `json:"title"`
	Body     string             `json:"body"`
	Priority string             `json:"priority"`
	Channel  string             `json:"channel"`
	Actions  []notifyActionJSON `json:"actions"`
	ImageURL string             `json:"image_url"`
}

// requestToMessage converts the wire shape into a validated notify.Message,
// applying contract defaults first.
func requestToMessage(req notifyRequestJSON) (notify.Message, error) {
	msg := notify.Message{
		Kind:     strings.TrimSpace(req.Kind),
		Title:    strings.TrimSpace(req.Title),
		Body:     strings.TrimSpace(req.Body),
		Priority: strings.TrimSpace(req.Priority),
		Channel:  strings.TrimSpace(req.Channel),
		ImageURL: strings.TrimSpace(req.ImageURL),
	}
	if req.Target.All != nil && *req.Target.All {
		msg.Target.All = true
		msg.Target.DeviceIDs = nil
		if len(req.Target.DeviceIDs) > 0 {
			// Both forms present: fail via Validate's ambiguous_target.
			msg.Target.DeviceIDs = req.Target.DeviceIDs
		}
	} else {
		msg.Target.DeviceIDs = dedupeStrings(req.Target.DeviceIDs)
	}
	for _, a := range req.Actions {
		msg.Actions = append(msg.Actions, notify.Action{
			ID:    strings.TrimSpace(a.ID),
			Title: strings.TrimSpace(a.Title),
		})
	}
	msg.ApplyDefaults()
	if err := msg.Validate(); err != nil {
		return notify.Message{}, err
	}
	return msg, nil
}

func dedupeStrings(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s = strings.TrimSpace(s); s != "" && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

// resolveNotifyTargets maps a validated target onto devices with a direct
// devices-table query owned by this file (P0-stable columns only). Unknown
// device_ids are skipped — accepted in the 202 response reflects devices
// actually fanned out to.
func resolveNotifyTargets(ctx context.Context, db *sql.DB, t notify.Target) ([]notify.Device, error) {
	if t.All {
		return queryDevices(ctx, db, `SELECT id, name FROM devices ORDER BY id`)
	}
	args := make([]any, 0, len(t.DeviceIDs))
	placeholders := make([]string, 0, len(t.DeviceIDs))
	for _, id := range t.DeviceIDs {
		args = append(args, id)
		placeholders = append(placeholders, "?")
	}
	return queryDevices(ctx, db,
		`SELECT id, name FROM devices WHERE id IN (`+strings.Join(placeholders, ",")+`) ORDER BY id`,
		args...)
}

func queryDevices(ctx context.Context, db *sql.DB, query string, args ...any) ([]notify.Device, error) {
	rows, err := db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var devices []notify.Device
	for rows.Next() {
		var d notify.Device
		if err := rows.Scan(&d.ID, &d.Name); err != nil {
			return nil, err
		}
		devices = append(devices, d)
	}
	return devices, rows.Err()
}

// newNotificationID mints the opaque id returned as 202 {"notification_id"}.
func newNotificationID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

func writeNotifyError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code})
}

// handleNotify enqueues fan-out: validate, resolve targets, send per device
// via the configured Sender, record history, respond 202. One device's
// failure never blocks the rest; accepted counts successful sends.
func handleNotify(db *sql.DB, sender notify.Sender) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		var req notifyRequestJSON
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeNotifyError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		msg, err := requestToMessage(req)
		if err != nil {
			var verr *notify.ValidationError
			if errors.As(err, &verr) {
				writeNotifyError(w, http.StatusUnprocessableEntity, verr.Code)
				return
			}
			writeNotifyError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		devices, err := resolveNotifyTargets(ctx, db, msg.Target)
		if err != nil {
			log.Printf("notify: resolve targets: %v", err)
			writeNotifyError(w, http.StatusInternalServerError, "target_lookup_failed")
			return
		}
		notificationID, err := newNotificationID()
		if err != nil {
			log.Printf("notify: new id: %v", err)
			writeNotifyError(w, http.StatusInternalServerError, "internal")
			return
		}
		accepted := 0
		for _, d := range devices {
			status, errMsg := notify.StatusQueued, ""
			if sendErr := sender.Send(ctx, d, msg); sendErr != nil {
				status, errMsg = notify.StatusFailed, sendErr.Error()
				log.Printf("notify: send failed device=%s notification=%s: %v", d.ID, notificationID, sendErr)
			} else {
				accepted++
			}
			if recErr := notify.RecordDelivery(ctx, db, notificationID, d.ID, msg.Kind, status, errMsg, time.Now()); recErr != nil {
				log.Printf("notify: record delivery: %v", recErr)
			}
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"notification_id": notificationID,
			"accepted":        accepted,
		})
	}
}
