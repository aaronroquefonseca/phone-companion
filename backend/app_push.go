// Package main — POST /api/v1/app-push (poll-based remote APK install push).
//
// The operator enqueues an APK-install directive that the app picks up on
// its next poll (GET /api/v1/commands); there is NO FCM, no Google
// service, and no push transport anywhere in this flow. The queued
// per-device rows carry the exact same flat string data-map shape the
// FCM-based variant used ({"type":"app_install","push_id":...,
// "apk_url":...,"sha256":...,"package":...,"version":...,"label":...})
// plus a per-device command_id, so the client parser is transport-agnostic.
//
// Auth is the shared operator chain (see notify_auth.go): operator token
// Bearer or WebUI session, never a device token. Every enqueue is
// recorded in the app_pushes audit table (no APK bytes stored) BEFORE any
// per-device row is served; notification_history is untouched by this
// endpoint.
//
// Delivery semantics:
//
//	accepted>0 → 202 {"push_id","accepted"} + one audit row + one pending
//	row per resolved device (status pending).
//	genuinely zero targets (unknown device_ids, or an empty registry for
//	{"all":true}) → 202 accepted:0 + audit row, never an error.
//	a target device already holding maxAppPushPendingPerDevice pending
//	commands → 429 too_many_pending with nothing queued and no audit row.
//	a DB failure anywhere in the enqueue → 500 with nothing queued.
package main

import (
	"context"
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

// appPushRequestJSON mirrors the contract's app-push request body. The
// target reuses the notify wire shape (notifyTargetJSON) and the notify
// union rule: exactly one of {"all":true} or {"device_ids":[...]};
// {"all":true} with an empty/absent device_ids means all, while a
// non-empty device_ids alongside all:true is 422 ambiguous_target.
type appPushRequestJSON struct {
	Target  notifyTargetJSON `json:"target"`
	APKURL  string           `json:"apk_url"`
	SHA256  string           `json:"sha256"`
	Package string           `json:"package"`
	Version string           `json:"version"`
	Label   string           `json:"label"`
}

// appPushRequest is a validated app-push enqueue request. Version "" means
// absent (stored NULL, served as an empty flat string).
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
// data payload must also fit the notify size budget (see
// checkAppPushPayloadSize): over-cap requests fail closed instead of
// truncating or dropping fields in transit.
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

	// Notify-style union: all:true XOR a non-empty device_ids. An
	// empty/absent device_ids with all:true still means all; both forms
	// together are ambiguous_target (unlike the FCM variant, which let
	// All absorb device_ids).
	ids := dedupeStrings(req.Target.DeviceIDs)
	all := req.Target.All != nil && *req.Target.All
	if all && len(ids) > 0 {
		return fail("ambiguous_target", `target must be exactly one of {"all":true} or {"device_ids":[...]}`)
	}
	target := notify.Target{All: all, DeviceIDs: ids}
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

// checkAppPushPayloadSize caps the worst-case marshaled data payload,
// mirroring notify's MaxDataPayloadBytes budget: over-cap requests fail
// closed with payload_too_long instead of truncating or dropping fields
// in transit. The poll transport serves this same flat map from the
// database, so the budget keeps queued rows bounded for the client too.
func checkAppPushPayloadSize(req appPushRequest) error {
	raw, err := json.Marshal(appPushPayload(strings.Repeat("0", 32), req))
	if err != nil || len(raw) > notify.MaxDataPayloadBytes {
		return &notify.ValidationError{Code: "payload_too_long", Err: errors.New("data payload exceeds the size budget: shorten apk_url/label")}
	}
	return nil
}

// appPushPayload renders the exact data-message shape the Android side
// installs from. Flat strings only (transport-agnostic: the FCM variant
// fanned out this same map, the poll transport serves it from the
// database); the "type" key lets old clients ignore what they do not
// understand.
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

// schemaAppPushV1Table is the audit ledger for enqueue attempts: one row
// per attempt, never per device. No APK bytes are stored — only the
// metadata needed to answer "who queued what to whom, when".
//
//	push_id    opaque id returned as 202 {"push_id": ...}
//	created_at RFC 3339 UTC accept time
//	operator   "operator-token" or "session:<username>" — never a secret
//	target     canonical JSON of the request target ({"all":true} or
//	           {"device_ids":[...]}, deduped; all:true means All)
//	apk_url / sha256 / package / label  validated request fields
//	version    optional request field, NULL when absent
//	accepted   devices queued (all-or-nothing with the pending rows)
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

// handleAppPush serves POST /api/v1/app-push: validate, queue one pending
// command row per resolved device inside a single transaction, respond.
//
// Delivery semantics (mirroring notify where the contract overlaps):
//
//	accepted>0 → 202 {"push_id","accepted"} + one audit row + one pending
//	row per resolved device.
//	genuinely zero targets (unknown device_ids, or an empty registry for
//	{"all":true}) → 202 accepted:0 + audit row, never an error.
//	a target device at the pending cap → 429 too_many_pending with
//	nothing queued and no audit row.
//	DB failure anywhere in the enqueue → 500 with nothing queued (the
//	audit row and the pending rows commit atomically).
//
// Unknown device_ids are skipped at resolve time (same as notify);
// notification_history is never written by this endpoint.
func handleAppPush(db *sql.DB) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		var raw appPushRequestJSON
		// Cap the body before decode (same 1 MiB bound as notify: this
		// endpoint queues one row per device, so bound the per-request
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
		now := time.Now()
		// Best-effort TTL prune before the cap check so expired rows never
		// count toward the per-device pending budget (same log-and-continue
		// posture as notify's history prune).
		if err := pruneAppPushCommands(ctx, db, now); err != nil {
			log.Printf("app-push: prune commands: %v", err)
		}
		if over, err := appPushOverCap(ctx, db, devices, now); err != nil {
			log.Printf("app-push: cap lookup: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		} else if over {
			writeAPIError(w, http.StatusTooManyRequests, "too_many_pending")
			return
		}
		commandIDs := make([]string, 0, len(devices))
		for range devices {
			id, err := newNotificationID()
			if err != nil {
				log.Printf("app-push: new id: %v", err)
				writeAPIError(w, http.StatusInternalServerError, "internal")
				return
			}
			commandIDs = append(commandIDs, id)
		}
		if err := insertAppPushEnqueue(ctx, db, pushID, operatorIdentity(r), req, devices, commandIDs, now); err != nil {
			log.Printf("app-push: record enqueue: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		// Log push_id and counts only — never URLs, digests, or tokens.
		log.Printf("app-push: queued push_id=%s accepted=%d/%d", pushID, len(devices), len(devices))
		writeAppPushAccepted(w, pushID, len(devices))
	}
}

// insertAppPushEnqueue commits the audit row plus one pending per-device
// row atomically: the audit insert lands BEFORE any servable row inside
// the same transaction, so a DB failure leaves nothing queued (no audit
// row, no pending rows — a retry is a clean re-enqueue, never a partial
// duplicate).
func insertAppPushEnqueue(ctx context.Context, db *sql.DB, pushID, operator string, req appPushRequest, devices []notify.Device, commandIDs []string, now time.Time) error {
	tx, err := db.Begin()
	if err != nil {
		return err
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback()
		}
	}()
	var v any
	if req.Version != "" {
		v = req.Version
	}
	if _, err := tx.ExecContext(ctx,
		`INSERT INTO app_pushes
			(push_id, created_at, operator, target, apk_url, sha256, package, version, label, accepted)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		pushID, formatTime(now), operator, canonicalAppPushTarget(req.Target),
		req.APKURL, req.SHA256, req.Package, v, req.Label, len(devices)); err != nil {
		return err
	}
	for i, d := range devices {
		if err := insertAppPushCommandTx(ctx, tx, commandIDs[i], pushID, d.ID, req, now); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	committed = true
	return nil
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
