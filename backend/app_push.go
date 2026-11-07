// Package main — POST /api/v1/app-push (poll-based remote install push).
//
// Operator-only endpoint that queues one pending outbox command per target
// device. Delivery is poll-based (GET /api/v1/outbox): the outbox row is
// the delivery leg and FCM is only a best-effort wake hint (a silent
// {"type":"app_push_wake","push_id":...} data message sent when the
// configured driver is the real FCM sender and the device has a stored
// token — hint failures are logged, never fail the request). The queued
// payload is the flat string map the Android side installs from:
//
//	{"type":"app_install","push_id":...,"apk_url":...,"sha256":...,
//	 "package":...,"version":...,"label":...}
//
// Auth is the shared operator chain (see notify_auth.go): operator token
// Bearer or WebUI session, never a device token (any other Bearer is a
// blanket 403). Every accepted fan-out is recorded in the pushes audit
// table (metadata only, no APK bytes) plus one pending row per device.
//
// Ordering is audit-before-mutate: validate, mint push_id, BEGIN, prune,
// cap-check, insert push record + per-device rows, UPDATE accepted,
// COMMIT — and only then signal waiters. Any DB failure before the
// response rolls back, so a 500 never queues a partial push (fail closed,
// never a retry-duplicate).
package main

import (
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"net/url"
	"strings"
	"unicode/utf8"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

// appPushRequestJSON mirrors the contract's app-push request body. Target
// reuses the notify union shape (notifyTargetJSON, same package — no
// parallel struct to drift): {"all":true} with empty/absent device_ids
// means all; {"all":true} with non-empty device_ids is ambiguous (422);
// neither is empty_target (422).
type appPushRequestJSON struct {
	Target  notifyTargetJSON `json:"target"`
	APKURL  string           `json:"apk_url"`
	SHA256  string           `json:"sha256"`
	Package string           `json:"package"`
	Version string           `json:"version"`
	Label   string           `json:"label"`
}

// appPushRequest is a validated app-push enqueue request. Version "" means
// absent (stored as an empty flat string, FCM-data compatible).
type appPushRequest struct {
	Target  notify.Target
	APKURL  string
	SHA256  string
	Package string
	Version string
	Label   string
}

// Limits for the app-push fields (contract: max sizes, https-only URL,
// lowercase-hex digest, reverse-DNS-ish package, short label). The
// marshaled flat map must also fit the notify FCM-budget constant
// (checkAppPushPayloadSize): the wake hint shares the same size
// vocabulary and the cap bounds row/poll weight.
const (
	maxAppPushURLLen     = 2048
	maxAppPackageLen     = 256
	maxAppPushVersionLen = 64
	maxAppPushLabelLen   = 100
)

// validateAppPushRequest enforces the contract, returning a
// *notify.ValidationError (stable machine-readable Code) on anything bad.
// Target checks reuse notify.ValidateTarget — no duplicate union logic.
// Unknown device_ids are tolerated at resolve time (skipped, never an
// error), exactly like notify.
func validateAppPushRequest(req appPushRequestJSON) (appPushRequest, error) {
	var out appPushRequest
	fail := func(code, msg string) (appPushRequest, error) {
		return appPushRequest{}, &notify.ValidationError{Code: code, Err: errors.New(msg)}
	}

	// Normalize then share the union check: All with empty/absent
	// device_ids is All; All with non-empty ids is ambiguous (422 via
	// ValidateTarget); neither is empty_target (422).
	all := req.Target.All != nil && *req.Target.All
	out.Target = notify.Target{All: all, DeviceIDs: dedupeStrings(req.Target.DeviceIDs)}
	if err := notify.ValidateTarget(out.Target); err != nil {
		return appPushRequest{}, err
	}

	apkURL := strings.TrimSpace(req.APKURL)
	if apkURL == "" {
		return fail("missing_apk_url", "apk_url must be non-empty")
	}
	if utf8.RuneCountInString(apkURL) > maxAppPushURLLen {
		return fail("invalid_apk_url", "apk_url must be at most 2048 characters")
	}
	u, err := url.Parse(apkURL)
	if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Host == "" {
		return fail("invalid_apk_url", "apk_url must be an https URL with a host")
	}
	if u.User != nil {
		return fail("invalid_apk_url", "apk_url must not contain credentials")
	}
	out.APKURL = apkURL

	sha := strings.TrimSpace(req.SHA256)
	if sha == "" {
		return fail("missing_sha256", "sha256 must be non-empty")
	}
	if len(sha) != 64 || !isLowerHex(sha) {
		return fail("invalid_sha256", "sha256 must be exactly 64 lowercase hex characters")
	}
	out.SHA256 = sha

	pkg := strings.TrimSpace(req.Package)
	if pkg == "" {
		return fail("missing_package", "package must be non-empty")
	}
	if utf8.RuneCountInString(pkg) > maxAppPackageLen {
		return fail("invalid_package", "package must be at most 256 characters")
	}
	if !validAppPackage(pkg) {
		return fail("invalid_package", "package must be a reverse-DNS name (at least one dot, [A-Za-z0-9._-])")
	}
	out.Package = pkg

	if v := strings.TrimSpace(req.Version); v != "" {
		if utf8.RuneCountInString(v) > maxAppPushVersionLen {
			return fail("invalid_version", "version must be at most 64 characters")
		}
		out.Version = v
	}

	label := strings.TrimSpace(req.Label)
	if label == "" {
		return fail("missing_label", "label must be non-empty")
	}
	if utf8.RuneCountInString(label) > maxAppPushLabelLen {
		return fail("invalid_label", "label must be at most 100 characters")
	}
	out.Label = label

	if err := checkAppPushPayloadSize(out); err != nil {
		return appPushRequest{}, err
	}
	return out, nil
}

// isLowerHex reports whether s is 64 chars of [0-9a-f]: hex.DecodeString
// (encoding/hex, no bespoke loops) accepts uppercase, so the lowercase
// check runs first.
func isLowerHex(s string) bool {
	if strings.ToLower(s) != s {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

// validAppPackage is the reverse-DNS-ish shape check: at least one dot,
// charset [A-Za-z0-9._-]. Deliberately loose (no per-label rules) — the
// app verifies the installed package out-of-band via the sha256 digest.
func validAppPackage(s string) bool {
	if !strings.Contains(s, ".") {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9', c == '.', c == '_', c == '-':
		default:
			return false
		}
	}
	return true
}

// checkAppPushPayloadSize caps the marshaled flat map against the shared
// notify.MaxDataPayloadBytes budget (same constant, same 422 direction as
// notify's payload_too_large). The placeholder push id has the minted
// length (32 hex chars) so the estimate matches stored bytes.
func checkAppPushPayloadSize(req appPushRequest) error {
	raw, err := json.Marshal(appPushPayload(strings.Repeat("0", 32), req))
	if err != nil || len(raw) > notify.MaxDataPayloadBytes {
		return &notify.ValidationError{Code: "payload_too_large", Err: errors.New("data payload exceeds the size budget: shorten apk_url/label")}
	}
	return nil
}

// appPushPayload renders the exact flat-string map the Android side
// installs from. Flat strings only (poll-JSON compatible); the "type"
// key lets old clients ignore what they do not understand.
func appPushPayload(pushID string, req appPushRequest) map[string]string {
	return map[string]string{
		"type":    outboxTypeAppInstall,
		"push_id": pushID,
		"apk_url": req.APKURL,
		"sha256":  req.SHA256,
		"package": req.Package,
		"version": req.Version,
		"label":   req.Label,
	}
}

// appPushWakeType is the silent FCM data-message type the app-push wake
// hint carries. The client must match the identical string (see the
// Android wake intercept): the value is a frozen cross-boundary
// contract, never renamed casually.
const appPushWakeType = "app_push_wake"

// handleAppPush serves POST /api/v1/app-push: validate, resolve, then
// atomically record the push + per-device pending rows and signal
// waiters. Delivery semantics:
//
//	accepted>0 → 202 {"push_id","accepted"} + one pushes row (accepted=N)
//	  + N pending outbox rows.
//	genuinely zero targets (unknown device_ids, or an empty registry for
//	  {"all":true}) → 202 accepted:0 + pushes row, never an error.
//	a device at maxOutboxPendingPerDevice → 429 too_many_pending with
//	  NOTHING queued (cap checked inside the tx before any insert).
//	any DB failure → 500 with nothing queued (tx rollback, fail closed:
//	  a 500 here must never invite a retry that double-queues).
//
// After commit the handler sends a best-effort FCM wake hint per target
// device — ONLY when sender is the real *notify.FCMSender AND the device
// has a non-empty FCM token. The hint is a silent data message
// ({"type":"app_push_wake","push_id":...}) that tells the app to poll
// NOW instead of waiting for the next cycle; failures are logged only
// (same NeedsReRegistration split as notify) and never fail the request,
// and no history rows are written. Devices without a token (or behind a
// non-FCM driver) simply pick the command up on their next poll.
//
// Logs carry push_id only — never tokens, bodies, or URLs (which may
// carry query strings).
func handleAppPush(db *sql.DB, sender notify.Sender) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		var raw appPushRequestJSON
		// Cap the body before decode (same 1 MiB bound as notify: this
		// endpoint fans out to N devices, so bound the per-request
		// amplifier up front). Unknown fields are tolerated (match
		// notify); undecodable bodies are 400 invalid_json.
		r.Body = http.MaxBytesReader(w, r.Body, notify.MaxRequestBodyBytes)
		if err := json.NewDecoder(r.Body).Decode(&raw); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) || strings.Contains(err.Error(), "request body too large") {
				writeAPIError(w, http.StatusRequestEntityTooLarge, "body_too_large")
				return
			}
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		req, err := validateAppPushRequest(raw)
		if err != nil {
			var verr *notify.ValidationError
			if errors.As(err, &verr) {
				writeAPIError(w, http.StatusUnprocessableEntity, verr.Code)
				return
			}
			writeAPIError(w, http.StatusBadRequest, "invalid_request")
			return
		}
		devices, err := resolveNotifyTargets(ctx, db, req.Target)
		if err != nil {
			log.Printf("app-push: resolve targets: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "target_lookup_failed")
			return
		}
		pushID, err := newNotificationID()
		if err != nil {
			log.Printf("app-push: new id: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		now := utcNow()
		// Audit-before-mutate in ONE tx: prune (expired rows must not
		// count toward the cap), cap-check every target, insert the push
		// record, insert one pending row per device, UPDATE accepted,
		// commit. Signals fire only after commit — waiters can never wake
		// onto rows that later roll back.
		tx, err := db.Begin()
		if err != nil {
			log.Printf("app-push: begin push_id=%s: %v", pushID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		committed := false
		defer func() {
			if !committed {
				_ = tx.Rollback()
			}
		}()
		if _, err := tx.Exec(`DELETE FROM outbox WHERE expires_at <= ?`, formatTime(now)); err != nil {
			log.Printf("app-push: prune push_id=%s: %v", pushID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		for _, d := range devices {
			var n int
			if err := tx.QueryRow(
				`SELECT COUNT(*) FROM outbox WHERE device_id = ? AND status = ? AND expires_at > ?`,
				d.ID, outboxStatusPending, formatTime(now)).Scan(&n); err != nil {
				log.Printf("app-push: cap check push_id=%s: %v", pushID, err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
			if n >= maxOutboxPendingPerDevice {
				// Whole request fails closed: nothing queued, operator
				// retries after the device drains (log the device hash
				// prefix only).
				log.Printf("app-push: device=%s at cap push_id=%s", deviceLogID(d.ID), pushID)
				writeAPIError(w, http.StatusTooManyRequests, "too_many_pending")
				return
			}
		}
		if err := recordPush(tx, pushID, outboxTypeAppInstall, operatorIdentity(r), canonicalTargetJSON(req.Target.All, req.Target.DeviceIDs), now); err != nil {
			log.Printf("app-push: record push_id=%s: %v", pushID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		payloadJSON := canonicalPayloadJSON(appPushPayload(pushID, req))
		expiresAt := now.Add(outboxTTL)
		for _, d := range devices {
			commandID, err := newNotificationID()
			if err != nil {
				log.Printf("app-push: new command id push_id=%s: %v", pushID, err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
			if err := insertOutboxRow(tx, commandID, pushID, d.ID, outboxTypeAppInstall, payloadJSON, now, expiresAt); err != nil {
				log.Printf("app-push: enqueue push_id=%s: %v", pushID, err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
		}
		if err := updatePushAccepted(tx, pushID, len(devices)); err != nil {
			log.Printf("app-push: update accepted push_id=%s: %v", pushID, err)
			// Logged, not returned (see updatePushAccepted): the rows
			// exist, so report delivery.
		}
		if err := tx.Commit(); err != nil {
			log.Printf("app-push: commit push_id=%s: %v", pushID, err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		committed = true
		for _, d := range devices {
			signalOutboxWaiters(d.ID)
		}
		// Best-effort wake hint ONLY for the real FCM driver with a
		// stored token (mirror of the notify hint): the silent wake
		// tells the app to poll now; failures are logged, never fail
		// the request, and write no history rows. Devices without a
		// token poll on their normal cadence.
		if _, isFCM := sender.(*notify.FCMSender); isFCM {
			for _, d := range devices {
				if strings.TrimSpace(d.FCMToken) == "" {
					continue
				}
				wake := map[string]string{"type": appPushWakeType, "push_id": pushID}
				if sendErr := sender.Send(ctx, d, wake); sendErr != nil {
					if notify.NeedsReRegistration(sendErr) {
						log.Printf("app-push: device=%s push_id=%s needs fresh FCM token: %v", deviceLogID(d.ID), pushID, sendErr)
					} else {
						log.Printf("app-push: hint failed device=%s push_id=%s: %v", deviceLogID(d.ID), pushID, sendErr)
					}
				}
			}
		}
		log.Printf("app-push: push_id=%s accepted=%d/%d", pushID, len(devices), len(devices))
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"push_id":  pushID,
			"accepted": len(devices),
		})
	}
}
