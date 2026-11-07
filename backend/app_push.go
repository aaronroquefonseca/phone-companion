// Package main — POST /api/v1/app-push (remote APK install push).
//
// Operator-only endpoint that fans out a data-only install directive to
// devices through the existing notify/FCM driver (notify.Sender). The
// payload is a flat string map the Android side renders into a
// download+install flow:
//
//	{"type":"app_install","push_id":...,"apk_url":...,"sha256":...,
//	 "package":...,"version":...,"label":...}
//
// Auth is the shared operator chain (see notify_auth.go): operator token
// Bearer or WebUI session, never a device token. Every attempted fan-out
// is recorded in the app_pushes audit table (no APK bytes stored);
// notification_history is untouched by this endpoint.
//
// Audit ordering is insert-then-send: the audit row lands BEFORE any
// driver send, so a DB failure answers 500 with nothing sent (no phantom
// push, no retry-duplicate), and the accepted count is UPDATEd after
// fan-out. A stub-driver rejection (LogSender/NoopSender with real
// targets) answers 500 driver_unavailable with no audit row at all —
// mirroring notify, which likewise writes no history there.
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
	"time"
	"unicode/utf8"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

// appPushRequestJSON mirrors the contract's app-push request body.
// Target reuses the notify union shape: {"all":true} wins outright —
// device_ids alongside it are ignored, not an error (unlike notify's
// ambiguous_target; see the contract).
type appPushRequestJSON struct {
	Target  notifyTargetJSON `json:"target"`
	APKURL  string           `json:"apk_url"`
	SHA256  string           `json:"sha256"`
	Package string           `json:"package"`
	Version string           `json:"version"`
	Label   string           `json:"label"`
}

// appPushRequest is a validated app-push enqueue request. Version "" means
// absent (stored NULL, sent as an empty flat string).
type appPushRequest struct {
	Target  notify.Target
	APKURL  string
	SHA256  string
	Package string
	Version string
	Label   string
}

// Limits for the app-push fields (contract: max sizes, https-only URL,
// lowercase-hex digest, reverse-DNS package, short label). The marshaled
// data payload must also fit the FCM budget (see checkAppPushPayloadSize).
const (
	maxAppPushURLLen     = 2048
	maxAppPackageLen     = 256
	maxAppPushVersionLen = 64
	maxAppPushLabelLen   = 100
)

// validateAppPushRequest enforces the contract, returning a
// *notify.ValidationError (stable machine-readable Code) on anything bad.
// Unknown device_ids are tolerated at resolve time (skipped, never an
// error) exactly like notify.
func validateAppPushRequest(req appPushRequestJSON) (appPushRequest, error) {
	var out appPushRequest
	fail := func(code, msg string) (appPushRequest, error) {
		return appPushRequest{}, &notify.ValidationError{Code: code, Err: errors.New(msg)}
	}

	// All:true absorbs device_ids entirely — even a non-empty list —
	// normalizing to a pure All target before the shared check runs.
	target := notify.Target{DeviceIDs: dedupeStrings(req.Target.DeviceIDs)}
	if req.Target.All != nil && *req.Target.All {
		target = notify.Target{All: true}
	}
	if err := notify.ValidateTarget(target); err != nil {
		return appPushRequest{}, err
	}
	out.Target = target

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
		return fail("invalid_package", "package must be a reverse-DNS name (at least one dot, [A-Za-z0-9_.])")
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

// isLowerHex reports whether s is all [0-9a-f]: decodable as hex and
// already lowercase (hex.DecodeString accepts uppercase, so check both).
func isLowerHex(s string) bool {
	if strings.ToLower(s) != s {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}

// validAppPackage is the basic reverse-DNS shape check: at least one
// dot, charset [A-Za-z0-9_.].
func validAppPackage(s string) bool {
	if !strings.Contains(s, ".") {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '.' || c == '_' {
			continue
		}
		return false
	}
	return true
}

// checkAppPushPayloadSize caps the worst-case marshaled FCM data payload,
// mirroring notify's MaxDataPayloadBytes budget: FCM data messages are
// ~4KB, so over-cap requests fail closed with payload_too_long instead of
// truncating or dropping fields in transit.
func checkAppPushPayloadSize(req appPushRequest) error {
	raw, err := json.Marshal(appPushPayload(strings.Repeat("0", 32), req))
	if err != nil || len(raw) > notify.MaxDataPayloadBytes {
		return &notify.ValidationError{Code: "payload_too_long", Err: errors.New("data payload exceeds the FCM size budget: shorten apk_url/label")}
	}
	return nil
}

// appPushPayload renders the exact data-message shape the Android side
// installs from. Flat strings only (FCM-data compatible); the "type"
// key lets old clients ignore what they do not understand.
func appPushPayload(pushID string, req appPushRequest) map[string]string {
	return map[string]string{
		"type":    "app_install",
		"push_id": pushID,
		"apk_url": req.APKURL,
		"sha256":  req.SHA256,
		"package": req.Package,
		"version": req.Version,
		"label":   req.Label,
	}
}

// canonicalAppPushTarget renders the validated target for the audit row:
// {"all":true} or {"device_ids":[...], deduped}. Computed once at record
// time from the validated struct — no parallel TargetJS field to drift.
func canonicalAppPushTarget(t notify.Target) string {
	if t.All {
		return `{"all":true}`
	}
	raw, err := json.Marshal(map[string]any{"device_ids": t.DeviceIDs})
	if err != nil {
		return `{"device_ids":[]}`
	}
	return string(raw)
}

// schemaAppPushV1Table is the audit ledger for attempted pushes: one row
// per fan-out attempt, never per device. No APK bytes are stored — only
// the metadata needed to answer "who pushed what to whom, when".
//
//	push_id    opaque id returned as 202 {"push_id": ...}
//	created_at RFC 3339 UTC accept time
//	operator   "operator-token" or "session:<username>" — never a secret
//	target     canonical JSON of the request target ({"all":true} or
//	           {"device_ids":[...]}, deduped; All absorbs device_ids)
//	apk_url / sha256 / package / label  validated request fields
//	version    optional request field, NULL when absent
//	accepted   devices the driver accepted; UPDATEd after fan-out (stays 0
//	           when nothing was delivered or no target resolved)
const schemaAppPushV1Table = `
CREATE TABLE IF NOT EXISTS app_pushes (
	push_id TEXT PRIMARY KEY,
	created_at TEXT NOT NULL,
	operator TEXT NOT NULL DEFAULT '',
	target TEXT NOT NULL DEFAULT '',
	apk_url TEXT NOT NULL DEFAULT '',
	sha256 TEXT NOT NULL DEFAULT '',
	package TEXT NOT NULL DEFAULT '',
	version TEXT,
	label TEXT NOT NULL DEFAULT '',
	accepted INTEGER NOT NULL DEFAULT 0
);
`

// MigrateAppPush applies the app-push audit schema. Called from main
// alongside the base and notify migrations; safe to run repeatedly. The
// accepted column is ensured separately so databases created by the
// pre-accepted DDL pick it up (same PRAGMA-then-ALTER shape as the
// devices.updated_at backfill).
func MigrateAppPush(db *sql.DB) error {
	if _, err := db.Exec(schemaAppPushV1Table); err != nil {
		return err
	}
	return ensureAppPushAcceptedColumn(db)
}

// ensureAppPushAcceptedColumn adds app_pushes.accepted when missing.
func ensureAppPushAcceptedColumn(db *sql.DB) error {
	rows, err := db.Query(`PRAGMA table_info(app_pushes)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, typ string
		var notNull int
		var dflt sql.NullString
		var pk int
		if err := rows.Scan(&cid, &name, &typ, &notNull, &dflt, &pk); err != nil {
			return err
		}
		if name == "accepted" {
			return rows.Err()
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = db.Exec(`ALTER TABLE app_pushes ADD COLUMN accepted INTEGER NOT NULL DEFAULT 0`)
	return err
}

// insertAppPushAudit appends one audit row for an attempted push, BEFORE
// any driver send (accepted starts 0; updateAppPushAccepted fills the
// final count). A DB failure here must answer 500 with nothing sent.
func insertAppPushAudit(db *sql.DB, pushID, operator string, req appPushRequest, at time.Time) error {
	var v any
	if req.Version != "" {
		v = req.Version
	}
	_, err := db.Exec(
		`INSERT INTO app_pushes
			(push_id, created_at, operator, target, apk_url, sha256, package, version, label, accepted)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)`,
		pushID, formatTime(at), operator, canonicalAppPushTarget(req.Target),
		req.APKURL, req.SHA256, req.Package, v, req.Label)
	return err
}

// updateAppPushAccepted records the post-fan-out accepted count. A failure
// here is logged, never returned: the sends already happened, so the
// response must report delivery, not a 500 that invites a duplicate push.
func updateAppPushAccepted(db *sql.DB, pushID string, accepted int) error {
	_, err := db.Exec(`UPDATE app_pushes SET accepted = ? WHERE push_id = ?`, accepted, pushID)
	return err
}

// handleAppPush serves POST /api/v1/app-push: validate, audit first, fan
// out through the existing driver, UPDATE the accepted count, respond.
//
// Delivery semantics (mirroring notify where the contract overlaps):
//
//	accepted>0 → 202 {"push_id","accepted"} + one audit row.
//	genuinely zero targets (unknown device_ids, or an empty registry for
//	{"all":true}) → 202 accepted:0 + audit row, never an error, behind
//	any driver (even a stub).
//	stub driver (LogSender/NoopSender) with real targets → 500
//	driver_unavailable with NO audit row (rejected before audit, like
//	notify writes no history there). A stub "accept" would lie about
//	delivery, so it fails closed instead.
//	real driver, every send failing → 500 driver_unavailable; the audit
//	row stays with accepted:0 as the record of the attempt.
//
// Unknown device_ids are skipped at resolve time (same as notify);
// notification_history is never written by this endpoint. One device's
// failure never blocks the rest.
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
		if len(devices) == 0 {
			if err := insertAppPushAudit(db, pushID, operatorIdentity(r), req, time.Now()); err != nil {
				log.Printf("app-push: record audit: %v", err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
			writeAppPushAccepted(w, pushID, 0)
			return
		}
		if notify.IsStubSender(sender) {
			log.Printf("app-push: stub driver %T cannot deliver push_id=%s to %d device(s)", sender, pushID, len(devices))
			writeAPIError(w, http.StatusInternalServerError, "driver_unavailable")
			return
		}
		if err := insertAppPushAudit(db, pushID, operatorIdentity(r), req, time.Now()); err != nil {
			log.Printf("app-push: record audit: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		payload := appPushPayload(pushID, req)
		accepted := 0
		for _, d := range devices {
			if err := sender.Send(ctx, d, payload); err != nil {
				log.Printf("app-push: send failed device=%s push_id=%s: %v", d.ID, pushID, err)
				continue
			}
			accepted++
		}
		log.Printf("app-push: sent push_id=%s type=%q accepted=%d/%d", pushID, payload["type"], accepted, len(devices))
		if err := updateAppPushAccepted(db, pushID, accepted); err != nil {
			log.Printf("app-push: update accepted push_id=%s: %v", pushID, err)
		}
		if accepted == 0 {
			// Real targets, nothing delivered: the driver (e.g.
			// misconfigured FCM) is down, not merely idle. The audit
			// row stays with accepted:0 as the record of the attempt.
			writeAPIError(w, http.StatusInternalServerError, "driver_unavailable")
			return
		}
		writeAppPushAccepted(w, pushID, accepted)
	}
}

// writeAppPushAccepted renders the 202 {"push_id","accepted"} body.
func writeAppPushAccepted(w http.ResponseWriter, pushID string, accepted int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusAccepted)
	_ = json.NewEncoder(w).Encode(map[string]any{
		"push_id":  pushID,
		"accepted": accepted,
	})
}
