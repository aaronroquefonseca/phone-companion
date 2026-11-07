// Package main — POST /api/v1/app-push (remote APK install push).
//
// Operator-only endpoint that fans out a data-only install directive to
// devices through the EXISTING notify/FCM driver (notify.Sender). The
// payload is a flat string map the Android side renders into a
// download+install flow:
//
//	{"type":"app_install","push_id":...,"apk_url":...,"sha256":...,
//	 "package":...,"version":...,"label":...}
//
// Auth mirrors POST /api/v1/notify plus the machine operator token: a
// valid WebUI session cookie with explicit non-browser intent
// (X-Requested-With or a session-bound CSRF token, same write guard as
// the other operator POST routes), OR Authorization: Bearer
// <OPERATOR_API_TOKEN> (constant-time compare, no write guard needed —
// a Bearer credential is not ambient like a cookie). Device Bearer
// tokens never authorize here (403, even alongside a session); unknown
// Bearer values are 401.
//
// Every accepted push is recorded in the app_pushes table for audit (no
// APK bytes stored). notification_history is untouched by this endpoint.
package main

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
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
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// operatorAPIToken is the machine operator credential from
// $OPERATOR_API_TOKEN (see Config). Wired once at boot via
// SetOperatorAPIToken; tests override it with cleanup. It is never
// logged and never returned in any response — audit rows record the
// fixed string "operator-token" instead.
var operatorAPIToken string

// SetOperatorAPIToken wires the operator API token for the auth chains
// below. Callers must pass the validated Config value (Config.Validate
// refuses to boot on a missing/weak token); an empty value here fails
// closed downstream because validOperatorToken never matches it.
func SetOperatorAPIToken(tok string) {
	operatorAPIToken = tok
}

// operatorSessionUser is the WebUI admin login name from Config,
// used only to label session-authenticated audit rows
// ("session:<username>"). It carries no authority: authority comes
// from the session cookie itself.
var operatorSessionUser string

// SetOperatorSessionUser wires the admin username for audit labelling.
func SetOperatorSessionUser(name string) {
	operatorSessionUser = name
}

// operatorIdentityForSession labels a cookie-authenticated push.
func operatorIdentityForSession() string {
	if operatorSessionUser != "" {
		return "session:" + operatorSessionUser
	}
	return "session"
}

// appPushIdentityKey carries the resolved operator identity through the
// request context from the auth chain to the handler.
type appPushIdentityKey struct{}

// withAppPushIdentity stamps the operator identity onto the request.
func withAppPushIdentity(r *http.Request, id string) *http.Request {
	return r.WithContext(context.WithValue(r.Context(), appPushIdentityKey{}, id))
}

// appPushIdentity returns the identity stamped by the auth chain, or
// the session label when the chain authenticated via cookie.
func appPushIdentity(r *http.Request) string {
	if v, ok := r.Context().Value(appPushIdentityKey{}).(string); ok && v != "" {
		return v
	}
	return operatorIdentityForSession()
}

// bearerToken extracts an Authorization: Bearer value. It reports false
// for any other scheme (or an empty token) so non-Bearer credentials
// fall through to the session path, matching the notify chain.
func bearerToken(r *http.Request) (string, bool) {
	h := strings.TrimSpace(r.Header.Get("Authorization"))
	if h == "" {
		return "", false
	}
	scheme := h
	rest := ""
	if i := strings.IndexByte(h, ' '); i >= 0 {
		scheme, rest = h[:i], strings.TrimSpace(h[i+1:])
	}
	if !strings.EqualFold(scheme, "bearer") || rest == "" {
		return "", false
	}
	return rest, true
}

// validOperatorToken constant-time compares the presented Bearer value
// against the configured operator token. An unconfigured (empty)
// token never matches — fail closed.
func validOperatorToken(presented string) bool {
	if presented == "" || operatorAPIToken == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(presented), []byte(operatorAPIToken)) == 1
}

// deviceBearerKnown reports whether presented matches a live device row.
// Revoked/unknown tokens are not valid device credentials (callers map
// those to 401, same as the device endpoints).
func deviceBearerKnown(db *sql.DB, presented string) bool {
	if db == nil || presented == "" {
		return false
	}
	var one int
	if err := db.QueryRow(
		`SELECT 1 FROM devices WHERE token_hash = ? AND revoked_at IS NULL`,
		sha256Hex(presented)).Scan(&one); err != nil {
		return false
	}
	return true
}

// appPushAdminChain gates POST /api/v1/app-push: operator token OR WebUI
// session, never a device token.
//
//   - Bearer == OPERATOR_API_TOKEN → accept (machine credential, no
//     write guard — CSRF proofs are for ambient cookies, not Bearers).
//   - Any other Bearer for a live device → 403 (valid credential, not
//     allowed), even alongside a session.
//   - Any other Bearer value → 401 (unknown credential; a presented
//     credential never silently falls through to the session).
//   - No Bearer → WebUI session required (401 without one) plus the
//     operator write guard (403 without X-Requested-With/CSRF token),
//     same as the other operator POST routes.
func appPushAdminChain(sessions webui.SessionStore, csrf *webui.CSRFProtection, db *sql.DB, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if tok, ok := bearerToken(r); ok {
			if validOperatorToken(tok) {
				next.ServeHTTP(w, withAppPushIdentity(r, "operator-token"))
				return
			}
			if deviceBearerKnown(db, tok) {
				writeAPIError(w, http.StatusForbidden, "forbidden")
				return
			}
			writeAPIError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		webui.RequireAdmin(sessions, requireAPIWriteGuard(csrf, next)).ServeHTTP(w, r)
	})
}

// appPushRequestJSON mirrors the contract's app-push request body.
// Target reuses the notify union shape: exactly one of {"all":true} or
// {"device_ids":[...]}.
type appPushRequestJSON struct {
	Target  notifyTargetJSON `json:"target"`
	APKURL  string           `json:"apk_url"`
	SHA256  string           `json:"sha256"`
	Package string           `json:"package"`
	Version string           `json:"version"`
	Label   string           `json:"label"`
}

// appPushRequest is a validated app-push enqueue request.
type appPushRequest struct {
	Target   notify.Target
	APKURL   string
	SHA256   string
	Package  string
	Version  string
	HasVer   bool
	Label    string
	TargetJS string
}

// Limits for the app-push fields (contract: max sizes, https-only URL,
// lowercase-hex digest, reverse-DNS package, short label).
const (
	maxAppPushURLLen     = 2048
	maxAppPushVersionLen = 64
	maxAppPushLabelLen   = 100
)

// validateAppPushRequest enforces the contract, returning a
// *notify.ValidationError (stable machine-readable Code) on anything
// bad. Target semantics match notify: deduped ids, empty rejected,
// unknown ids tolerated at resolve time (skipped, never an error).
func validateAppPushRequest(req appPushRequestJSON) (appPushRequest, error) {
	var out appPushRequest
	fail := func(code, msg string) (appPushRequest, error) {
		return appPushRequest{}, &notify.ValidationError{Code: code, Err: errors.New(msg)}
	}

	var target notify.Target
	if req.Target.All != nil && *req.Target.All {
		target.All = true
		if len(req.Target.DeviceIDs) > 0 {
			return fail("ambiguous_target", `target must be exactly one of {"all":true} or {"device_ids":[...]}`)
		}
	} else {
		target.DeviceIDs = dedupeStrings(req.Target.DeviceIDs)
	}
	if target.All {
		out.TargetJS = `{"all":true}`
	} else {
		if len(target.DeviceIDs) == 0 {
			return fail("empty_target", `target must be exactly one of {"all":true} or non-empty {"device_ids":[...]}`)
		}
		if len(target.DeviceIDs) > notify.MaxDeviceIDs {
			return fail("too_many_targets", "device_ids supports at most 500 entries")
		}
		raw, err := json.Marshal(map[string]any{"device_ids": target.DeviceIDs})
		if err != nil {
			return fail("invalid_request", "cannot encode target")
		}
		out.TargetJS = string(raw)
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
	if !validAppPackage(pkg) {
		return fail("invalid_package", "package must be a reverse-DNS name (at least one dot, [A-Za-z0-9_.])")
	}
	out.Package = pkg

	if v := strings.TrimSpace(req.Version); v != "" {
		if utf8.RuneCountInString(v) > maxAppPushVersionLen {
			return fail("invalid_version", "version must be at most 64 characters")
		}
		out.Version, out.HasVer = v, true
	}

	label := strings.TrimSpace(req.Label)
	if label == "" {
		return fail("missing_label", "label must be non-empty")
	}
	if utf8.RuneCountInString(label) > maxAppPushLabelLen {
		return fail("invalid_label", "label must be at most 100 characters")
	}
	out.Label = label

	return out, nil
}

// isLowerHex reports whether s is all [0-9a-f].
func isLowerHex(s string) bool {
	for i := 0; i < len(s); i++ {
		c := s[i]
		if c >= '0' && c <= '9' || c >= 'a' && c <= 'f' {
			continue
		}
		return false
	}
	return true
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

// mintPushID mints the opaque id returned as 202 {"push_id": ...}
// (crypto/rand hex, 128-bit — same mechanics as notification ids).
func mintPushID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
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

// fanoutAppPush hands the payload to the existing driver once per
// resolved device. One device's failure never blocks the rest; the
// return counts devices the driver accepted.
func fanoutAppPush(ctx context.Context, sender notify.Sender, devices []notify.Device, payload map[string]string, pushID string) int {
	accepted := 0
	for _, d := range devices {
		if err := sender.Send(ctx, d, payload); err != nil {
			log.Printf("app-push: send failed device=%s push_id=%s: %v", d.ID, pushID, err)
			continue
		}
		accepted++
	}
	return accepted
}

// schemaAppPushV1Table is the audit ledger for accepted pushes: one row
// per 202, never per device. No APK bytes are stored — only the
// metadata needed to answer "who pushed what to whom, when".
//
//	push_id    opaque id returned as 202 {"push_id": ...}
//	created_at RFC 3339 UTC accept time
//	operator   "operator-token" or "session:<username>" — never a secret
//	target     canonical JSON of the request target ({"all":true} or
//	           {"device_ids":[...]}, deduped)
//	apk_url / sha256 / package / label  validated request fields
//	version    optional request field, NULL when absent
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
	label TEXT NOT NULL DEFAULT ''
);
`

// MigrateAppPush applies the app-push audit schema. Called from main
// alongside the base and notify migrations; safe to run repeatedly
// (CREATE IF NOT EXISTS).
func MigrateAppPush(db *sql.DB) error {
	_, err := db.Exec(schemaAppPushV1Table)
	return err
}

// recordAppPush appends one audit row for an accepted push.
func recordAppPush(db *sql.DB, pushID, operator, targetJS, apkURL, sha, pkg, label string, version *string, at time.Time) error {
	var v any
	if version != nil {
		v = *version
	}
	_, err := db.Exec(
		`INSERT INTO app_pushes
			(push_id, created_at, operator, target, apk_url, sha256, package, version, label)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		pushID, formatTime(at), operator, targetJS, apkURL, sha, pkg, v, label)
	return err
}

// handleAppPush serves POST /api/v1/app-push: validate, fan out through
// the existing driver, audit, respond.
//
// Delivery semantics (mirroring notify where the contract overlaps):
//
//	accepted>0 → 202 {"push_id","accepted"} + one audit row.
//	genuinely zero targets (unknown device_ids, or an empty registry for
//	{"all":true}) → 202 accepted:0 + audit row, never an error.
//	real targets but nothing delivered (every send driver-failing) →
//	500 driver_unavailable, no audit row.
//
// Unknown device_ids are skipped at resolve time (same as notify);
// notification_history is never written by this endpoint.
func handleAppPush(db *sql.DB, sender notify.Sender) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx := r.Context()
		var raw appPushRequestJSON
		// Cap the body before decode (same 1 MiB bound as notify: this
		// endpoint fans out to N devices, so bound the per-request
		// amplifier up front).
		r.Body = http.MaxBytesReader(w, r.Body, notify.MaxRequestBodyBytes)
		dec := json.NewDecoder(r.Body)
		dec.DisallowUnknownFields()
		if err := dec.Decode(&raw); err != nil {
			var maxErr *http.MaxBytesError
			if errors.As(err, &maxErr) || strings.Contains(err.Error(), "request body too large") {
				writeAPIError(w, http.StatusRequestEntityTooLarge, "body_too_large")
				return
			}
			writeAPIError(w, http.StatusUnprocessableEntity, "invalid_json")
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
		pushID, err := mintPushID()
		if err != nil {
			log.Printf("app-push: new id: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		accepted := 0
		if len(devices) > 0 {
			accepted = fanoutAppPush(ctx, sender, devices, appPushPayload(pushID, req), pushID)
			if accepted == 0 {
				// Real targets, nothing delivered: the driver (e.g.
				// misconfigured FCM) is down, not merely idle.
				writeAPIError(w, http.StatusInternalServerError, "driver_unavailable")
				return
			}
		}
		var version *string
		if req.HasVer {
			v := req.Version
			version = &v
		}
		if err := recordAppPush(db, pushID, appPushIdentity(r), req.TargetJS,
			req.APKURL, req.SHA256, req.Package, req.Label, version, time.Now()); err != nil {
			log.Printf("app-push: record audit: %v", err)
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusAccepted)
		_ = json.NewEncoder(w).Encode(map[string]any{
			"push_id":  pushID,
			"accepted": accepted,
		})
	}
}
