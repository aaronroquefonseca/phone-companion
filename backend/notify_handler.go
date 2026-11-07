// Package main — POST /api/v1/notify handler (P4 NOTIFICATIONS).
//
// Request/response shapes follow docs/api-contract.md ("notify" section).
// Target resolution queries the devices table directly here: only the
// P0-stable columns (id, name) are selected. Admin gating wraps at
// registration time via operatorAdminChain (see notify_auth.go): the WebUI
// session or the operator token is required (401 without either) and
// device Bearer tokens are rejected with 403.
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
// explicit false/absent from true. Shared with POST /api/v1/app-push
// (same package, no parallel struct).
type notifyTargetJSON struct {
	All       *bool    `json:"all"`
	DeviceIDs []string `json:"device_ids"`
}

type notifyActionJSON struct {
	ID    string `json:"id"`
	Title string `json:"title"`
}

// notifyRequestJSON mirrors the contract's notify request body, plus the
// additive v1 "secret" flag (code-only extension, see notify.Message): when
// true the server per-device encrypts title+body into P5 envelopes and the
// fan-out payload carries no plaintext. Non-secret requests are unchanged.
type notifyRequestJSON struct {
	Target   notifyTargetJSON   `json:"target"`
	Kind     string             `json:"kind"`
	Title    string             `json:"title"`
	Body     string             `json:"body"`
	Priority string             `json:"priority"`
	Channel  string             `json:"channel"`
	Actions  []notifyActionJSON `json:"actions"`
	ImageURL string             `json:"image_url"`
	Secret   bool               `json:"secret"`
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
		Secret:   req.Secret,
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
// actually queued. Revoked devices (revoked_at NOT NULL) are always
// excluded: revocation must stop pushes, not just device polling (which
// already 401s via AuthenticateDevice). Shared verbatim by POST
// /api/v1/app-push — do not fork it.
func resolveNotifyTargets(ctx context.Context, db *sql.DB, t notify.Target) ([]notify.Device, error) {
	if t.All {
		return queryDevices(ctx, db, `SELECT id, name, COALESCE(fcm_token, '') FROM devices WHERE revoked_at IS NULL ORDER BY id`)
	}
	args := make([]any, 0, len(t.DeviceIDs))
	placeholders := make([]string, 0, len(t.DeviceIDs))
	for _, id := range t.DeviceIDs {
		args = append(args, id)
		placeholders = append(placeholders, "?")
	}
	return queryDevices(ctx, db,
		`SELECT id, name, COALESCE(fcm_token, '') FROM devices WHERE revoked_at IS NULL AND id IN (`+strings.Join(placeholders, ",")+`) ORDER BY id`,
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
		if err := rows.Scan(&d.ID, &d.Name, &d.FCMToken); err != nil {
			return nil, err
		}
		devices = append(devices, d)
	}
	return devices, rows.Err()
}

// newNotificationID mints the opaque id returned as 202 {"notification_id"}
// (and reused as push_id / command_id across the outbox — one minter, no
// mintXxx duplicates).
func newNotificationID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// fanoutNotify is the shared fan-out core behind both POST /api/v1/notify
// and the operator WebUI test form (POST /webui/notify): record the push,
// enqueue one pending outbox row per target device, signal waiters.
// Callers map the result onto their surface (JSON 202 vs. HTML 202 vs.
// error page).
//
// INTENTIONAL CONTRACT CHANGE (poll-based delivery, no FCM required): the
// outbox is now the delivery leg, so valid requests ALWAYS return 202
// (accepted = devices queued). The old stub-driver 500 ("a stub accept
// would lie about delivery, so fail closed") is gone BY DESIGN — a 202 now
// means "queued in the outbox", which is literally true, so there is
// nothing to lie about. LogSender logs the enqueue (observability, no
// delivery claim); FCM is a best-effort wake hint ONLY when the configured
// driver is the FCM sender AND the device has a non-empty token (send
// failures are logged only, never fail the request).
//
// Success returns (notificationID, accepted, 202, ""). Failure returns
// ("", 0, status, code): resolve/mint/record failures only (500
// target_lookup_failed / internal). Per-device build/enqueue failures
// skip that device — one device's failure never blocks the rest, and the
// request still 202s with the devices actually queued.
//
// History note: notification_history still records FCM-leg attempts
// (queued/failed, sanitized) and payload-build failures. Non-FCM drivers
// write no history rows — observability for those is the enqueue log line
// plus the outbox rows themselves.
func fanoutNotify(ctx context.Context, db *sql.DB, sender notify.Sender, msg notify.Message) (notificationID string, accepted int, httpStatus int, errCode string) {
	devices, err := resolveNotifyTargets(ctx, db, msg.Target)
	if err != nil {
		log.Printf("notify: resolve targets: %v", err)
		return "", 0, http.StatusInternalServerError, "target_lookup_failed"
	}
	notificationID, err = newNotificationID()
	if err != nil {
		log.Printf("notify: new id: %v", err)
		return "", 0, http.StatusInternalServerError, "internal"
	}
	// TTL prune rides every enqueue, best-effort (a prune failure is
	// logged, never fails the request).
	if err := pruneOutbox(db, time.Now()); err != nil {
		log.Printf("notify: prune outbox notification=%s: %v", notificationID, err)
	}
	// Audit-before-mutate: the push record lands before any per-device
	// row. A DB failure here answers 500 with nothing queued (fail
	// closed); per-device insert failures below skip that device only.
	if err := recordPush(db, notificationID, outboxTypeNotify, operatorIDFromContext(ctx), canonicalTargetJSON(msg.Target.All, msg.Target.DeviceIDs), time.Now()); err != nil {
		log.Printf("notify: record push notification=%s: %v", notificationID, err)
		return "", 0, http.StatusInternalServerError, "internal"
	}
	_, isFCM := sender.(*notify.FCMSender)
	accepted = 0
	for _, d := range devices {
		payload, perr := payloadForDevice(msg, db, notificationID, d)
		if perr != nil {
			log.Printf("notify: build payload device=%s notification=%s: %v", deviceLogID(d.ID), notificationID, perr)
			if recErr := notify.RecordDelivery(ctx, db, notificationID, d.ID, msg.Kind, notify.StatusFailed, perr.Error(), time.Now()); recErr != nil {
				log.Printf("notify: record delivery: %v", recErr)
			}
			continue
		}
		if full, cerr := outboxDeviceFull(db, d.ID, time.Now()); cerr != nil || full {
			// Notify stays 202 by design: a capped device is skipped
			// (the app path 429s instead — see handleAppPush).
			log.Printf("notify: skip device=%s notification=%s (queue full or count failed: %v)", deviceLogID(d.ID), notificationID, cerr)
			continue
		}
		commandID, merr := newNotificationID()
		if merr != nil {
			log.Printf("notify: new command id notification=%s: %v", notificationID, merr)
			continue
		}
		if ierr := insertOutboxRow(db, commandID, notificationID, d.ID, outboxTypeNotify, canonicalPayloadJSON(payload), time.Now(), time.Now().Add(outboxTTL)); ierr != nil {
			log.Printf("notify: enqueue device=%s notification=%s: %v", deviceLogID(d.ID), notificationID, ierr)
			continue
		}
		accepted++
		signalOutboxWaiters(d.ID)
		if _, isLog := sender.(notify.LogSender); isLog {
			// Observability, no delivery claim: the outbox row (not this
			// log line) is the delivery leg.
			log.Printf("notify: enqueued notification_id=%q device=%q kind=%q",
				notificationID, deviceLogID(d.ID), msg.Kind)
		}
		// Best-effort wake hint ONLY for the real FCM driver with a
		// stored token: failures are logged, never fail the request.
		if isFCM && strings.TrimSpace(d.FCMToken) != "" {
			if sendErr := sender.Send(ctx, d, payload); sendErr != nil {
				// Token-gone signals (unregistered/invalid/missing FCM
				// token) are recorded in the failed history row — the
				// operator greps history for them — but NEVER revoke the
				// device here: revocation is destructive and stays an
				// explicit operator action. The device heals by uploading
				// a fresh token (re-pair or the token-update endpoint).
				if notify.NeedsReRegistration(sendErr) {
					log.Printf("notify: device=%s notification=%s needs fresh FCM token: %v", deviceLogID(d.ID), notificationID, sendErr)
				} else {
					log.Printf("notify: hint failed device=%s notification=%s: %v", deviceLogID(d.ID), notificationID, sendErr)
				}
				if recErr := notify.RecordDelivery(ctx, db, notificationID, d.ID, msg.Kind, notify.StatusFailed, sendErr.Error(), time.Now()); recErr != nil {
					log.Printf("notify: record delivery: %v", recErr)
				}
			} else if recErr := notify.RecordDelivery(ctx, db, notificationID, d.ID, msg.Kind, notify.StatusQueued, "", time.Now()); recErr != nil {
				log.Printf("notify: record delivery: %v", recErr)
			}
		}
	}
	// Bounded history (P4 follow-up): prune best-effort after fan-out. A
	// prune failure is logged, never fails the request; fan-out stays
	// sequential (one device after another) so a slow device cannot
	// amplify into concurrent load.
	if pruneErr := notify.PruneHistory(ctx, db, time.Now()); pruneErr != nil {
		log.Printf("notify: prune history: %v", pruneErr)
	}
	if err := updatePushAccepted(db, notificationID, accepted); err != nil {
		log.Printf("notify: update accepted notification=%s: %v", notificationID, err)
	}
	return notificationID, accepted, http.StatusAccepted, ""
}

// handleNotify validates, then runs the shared fanoutNotify core and maps
// its result onto the JSON contract. Auth and validation are EXACTLY as
// before the outbox change (operator-only gate at registration, same
// field checks and codes); only what happens after validation changed:
// enqueue to the outbox + waiter signals (delivery leg), with FCM as a
// best-effort hint. Result: valid requests ALWAYS 202 (accepted =
// devices queued).
func handleNotify(db *sql.DB, sender notify.Sender) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		var req notifyRequestJSON
		// Cap the body before decode (B1): this endpoint fans out to N
		// devices, so bound the storage/CPU amplifier (outbox INSERTs +
		// log lines per device) up front. Target-list size is capped by
		// validation (B2: at most notify.MaxDeviceIDs explicit ids).
		r.Body = http.MaxBytesReader(w, r.Body, notify.MaxRequestBodyBytes)
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) || strings.Contains(err.Error(), "request body too large") {
				writeAPIError(w, http.StatusRequestEntityTooLarge, "body_too_large")
				return
			}
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		msg, err := requestToMessage(req)
		if err != nil {
			var verr *notify.ValidationError
			if errors.As(err, &verr) {
				writeAPIError(w, http.StatusUnprocessableEntity, verr.Code)
				return
			}
			writeAPIError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		if notify.DebugEnabled() {
			// Explicit local-debug opt-in only: this is the one place full
			// notify content may be logged. Never on in production.
			log.Printf("notify: debug dump kind=%q title=%q body=%q secret=%v targets=%+v",
				msg.Kind, msg.Title, msg.Body, msg.Secret, msg.Target)
		}
		notificationID, accepted, status, code := fanoutNotify(ctx, db, sender, msg)
		if status != http.StatusAccepted {
			writeAPIError(w, status, code)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"notification_id": notificationID,
			"accepted":        accepted,
		})
	}
}

// payloadForDevice builds the wire payload for one device: the plaintext
// versioned payload, or per-device P5 envelopes when msg.Secret is set
// (key lookup + Encrypt per field; any failure fails that device closed —
// the failure text is sanitized at RecordDelivery time). The key lookup
// reads the passed db handle (not the deviceDB global) so fan-out stays
// hermetic in tests and never depends on boot wiring. Reused for both the
// outbox row and the best-effort FCM hint — never forked.
func payloadForDevice(msg notify.Message, db *sql.DB, notificationID string, d notify.Device) (map[string]string, error) {
	if !msg.Secret {
		return msg.DataPayload(notificationID), nil
	}
	key, err := lookupNotifySecretKey(db, d.ID)
	if err != nil {
		return nil, errors.New("notify: no device key for secret payload")
	}
	return msg.SecretDataPayload(notificationID, d.ID, key)
}

// lookupNotifySecretKey returns the 32-byte per-device key for an active
// device, or an error when the row is missing/revoked or the key is
// absent/malformed. Secret material is never logged by callers.
func lookupNotifySecretKey(db *sql.DB, deviceID string) ([32]byte, error) {
	var zero [32]byte
	if db == nil {
		return zero, errors.New("notify: device DB not wired")
	}
	var keyBytes []byte
	if err := db.QueryRow(
		`SELECT secret_key FROM devices WHERE id = ? AND revoked_at IS NULL`,
		deviceID).Scan(&keyBytes); err != nil {
		return zero, err
	}
	if len(keyBytes) != 32 {
		return zero, errors.New("notify: bad key length")
	}
	var key [32]byte
	copy(key[:], keyBytes)
	return key, nil
}
