// Package main — POST /api/v1/app-push tests (poll-based APK install push).
//
// Covers: operator-token config boot gate, the auth matrix (no auth,
// device Bearer, bad Bearer, session without write guard, session,
// token), the validation matrix (every bad field → 422 with a stable
// code), All-vs-device_ids target semantics, enqueue/audit contents
// (redacted operator identity, canonical target), per-device caps
// (429), and audit-before-serve atomicity (DB failure leaves nothing
// queued). Poll/ack lifecycle lives in app_push_commands_test.go.
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

const (
	appPushTestToken = "test-operator-token-0123456789abcdef"
	appPushTestUser  = "op"
)

const (
	appPushTestSHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	appPushTestURL = "https://example.com/app-release.apk"
)

// openAppPushTestDB returns an in-memory DB with base + notify + app-push
// + pending-commands schemas applied and no device rows.
func openAppPushTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db := openNotifyTestDB(t)
	if err := MigrateAppPush(db); err != nil {
		t.Fatal(err)
	}
	if err := MigrateAppPushCommands(db); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`DELETE FROM devices`); err != nil {
		t.Fatal(err)
	}
	return db
}

// setAppPushAuth wires the operator token + session-user globals for one
// test, restoring the previous values on cleanup.
func setAppPushAuth(t *testing.T, token, user string) {
	t.Helper()
	oldTok, oldUser := operatorAPIToken, operatorSessionUser
	SetOperatorAPIToken(token)
	SetOperatorSessionUser(user)
	t.Cleanup(func() {
		SetOperatorAPIToken(oldTok)
		SetOperatorSessionUser(oldUser)
	})
}

// openAppPushMux builds the production router (NewMux: notify + app-push
// + commands) backed by db/sender, with operator auth globals set.
func openAppPushMux(t *testing.T, db *sql.DB, sender notify.Sender) (http.Handler, *SessionStore) {
	t.Helper()
	setAppPushAuth(t, appPushTestToken, appPushTestUser)
	mux, store := openAuthedMux(t, db, sender)
	return mux, store
}

func postAppPush(t *testing.T, mux http.Handler, body string, cookie *http.Cookie, auth string, xrw bool) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(body))
	if xrw {
		req.Header.Set("X-Requested-With", "XMLHttpRequest")
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func validAppPushBody(target string) string {
	return `{"target":` + target + `,"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) +
		`,"package":"com.example.app","version":"1.2.3","label":"Test App"}`
}

func decodePush202(t *testing.T, w *httptest.ResponseRecorder) (string, float64) {
	t.Helper()
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("non-JSON 202 body: %q", w.Body.String())
	}
	id, _ := got["push_id"].(string)
	return id, got["accepted"].(float64)
}

// mustEnrollDevice enrolls one device and returns its id + raw Bearer token.
func mustEnrollDevice(t *testing.T, db *sql.DB, name, fcm string) (id, token string) {
	t.Helper()
	id, token, _, err := EnrollDevice(db, name, fcm)
	if err != nil {
		t.Fatal(err)
	}
	return id, token
}

func appPushAuditCount(t *testing.T, db *sql.DB) int {
	t.Helper()
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM app_pushes`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func appPushPendingCount(t *testing.T, db *sql.DB, deviceID string) int {
	t.Helper()
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM app_push_commands WHERE device_id = ?`, deviceID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// The operator token rides the environment into Config and is fail-closed
// at boot, mirroring SESSION_SECRET.
func TestAppPushConfigToken(t *testing.T) {
	t.Setenv("OPERATOR_API_TOKEN", strings.Repeat("o", 32))
	if got := LoadConfig().OperatorAPIToken; got != strings.Repeat("o", 32) {
		t.Fatalf("LoadConfig missed OPERATOR_API_TOKEN: %q", got)
	}

	c := validTestConfig()
	c.OperatorAPIToken = "short"
	if err := c.Validate(); err == nil {
		t.Fatal("short operator token: expected rejection")
	} else if strings.Contains(err.Error(), "short") {
		t.Errorf("validation error echoes token material: %q", err)
	}
}

func TestAppPushAuthMatrix(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, store := openAppPushMux(t, db, &captureSender{})
	cookie := loginCookie(t, store)
	zeroTargetBody := validAppPushBody(`{"device_ids":["nope"]}`)

	// No auth → 401.
	if w := postAppPush(t, mux, zeroTargetBody, nil, "", false); w.Code != http.StatusUnauthorized {
		t.Errorf("no auth: got %d, want 401 (%s)", w.Code, w.Body.String())
	}

	// Unknown Bearer → 403 (blanket: any non-operator Bearer is
	// forbidden, no DB lookup, no fallthrough to the session).
	if w := postAppPush(t, mux, zeroTargetBody, nil, "Bearer garbage-token", false); w.Code != http.StatusForbidden {
		t.Errorf("bad bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Valid device Bearer → 403, even with a session alongside.
	_, devTok := mustEnrollDevice(t, db, "phone", "fcm-auth-matrix")
	if w := postAppPush(t, mux, zeroTargetBody, nil, "Bearer "+devTok, false); w.Code != http.StatusForbidden {
		t.Errorf("device bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}
	if w := postAppPush(t, mux, zeroTargetBody, cookie, "Bearer "+devTok, true); w.Code != http.StatusForbidden {
		t.Errorf("device bearer+session: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Revoked device Bearer is still just a non-operator Bearer → 403
	// (the chain does no device lookup; any wrong Bearer is forbidden).
	devID := ""
	if err := db.QueryRow(`SELECT id FROM devices WHERE fcm_token = 'fcm-auth-matrix'`).Scan(&devID); err != nil {
		t.Fatal(err)
	}
	if _, err := RevokeDevice(db, devID); err != nil {
		t.Fatal(err)
	}
	if w := postAppPush(t, mux, zeroTargetBody, nil, "Bearer "+devTok, false); w.Code != http.StatusForbidden {
		t.Errorf("revoked device bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Session without the write-guard marker → 403 per existing convention.
	if w := postAppPush(t, mux, zeroTargetBody, cookie, "", false); w.Code != http.StatusForbidden {
		t.Errorf("session w/o guard: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Session + guard → 202 (zero targets is a normal 202).
	if w := postAppPush(t, mux, zeroTargetBody, cookie, "", true); w.Code != http.StatusAccepted {
		t.Errorf("session+guard: got %d, want 202 (%s)", w.Code, w.Body.String())
	}

	// Operator token alone (no session, no guard) → 202.
	if w := postAppPush(t, mux, zeroTargetBody, nil, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Errorf("operator token: got %d, want 202 (%s)", w.Code, w.Body.String())
	}

	// Wrong operator token → 403 (not 401: any wrong Bearer is forbidden).
	if w := postAppPush(t, mux, zeroTargetBody, nil, "Bearer "+appPushTestToken+"-wrong", false); w.Code != http.StatusForbidden {
		t.Errorf("wrong token: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Operator token wins over a guard-less session (token path needs no CSRF).
	if w := postAppPush(t, mux, zeroTargetBody, cookie, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Errorf("token+session: got %d, want 202 (%s)", w.Code, w.Body.String())
	}

	// Session + unknown Bearer → 403: a presented credential never falls
	// through to the session.
	if w := postAppPush(t, mux, zeroTargetBody, cookie, "Bearer garbage-token", true); w.Code != http.StatusForbidden {
		t.Errorf("session+bad bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}
}

// The operator token is also accepted on POST /api/v1/notify; the
// session path keeps working unchanged.
func TestAppPushTokenAcceptedOnNotify(t *testing.T) {
	db := openAppPushTestDB(t)
	addNotifyDevice(t, db, "dev-1")
	mux, store := openAppPushMux(t, db, &captureSender{})
	cookie := loginCookie(t, store)
	body := `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`

	w := postNotifyMux(mux, body, nil, "Bearer "+appPushTestToken)
	if w.Code != http.StatusAccepted {
		t.Fatalf("notify+token: got %d, want 202 (%s)", w.Code, w.Body.String())
	}
	// No write-guard header was sent above: the token path skips the guard.
	w = postNotifyMux(mux, body, cookie, "")
	if w.Code != http.StatusAccepted {
		t.Fatalf("notify+session: got %d, want 202 (%s)", w.Code, w.Body.String())
	}
}

func TestAppPushValidationMatrix(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken
	good := func(mut func(m map[string]any)) string {
		m := map[string]any{
			"target":  map[string]any{"all": true},
			"apk_url": appPushTestURL,
			"sha256":  appPushTestSHA,
			"package": "com.example.app",
			"version": "1.2.3",
			"label":   "Test App",
		}
		mut(m)
		raw, _ := json.Marshal(m)
		return string(raw)
	}

	cases := []struct {
		name   string
		body   string
		status int
		code   string
	}{
		{"empty body target", `{"apk_url":"https://example.com/a.apk","sha256":"` + appPushTestSHA + `","package":"com.example.app","label":"x"}`, 422, "empty_target"},
		{"empty target obj", good(func(m map[string]any) { m["target"] = map[string]any{} }), 422, "empty_target"},
		{"empty device_ids", good(func(m map[string]any) { m["target"] = map[string]any{"device_ids": []string{}} }), 422, "empty_target"},
		{"both forms", good(func(m map[string]any) {
			m["target"] = map[string]any{"all": true, "device_ids": []string{"dev-1"}}
		}), 422, "ambiguous_target"},
		{"too many targets", good(func(m map[string]any) { m["target"] = map[string]any{"device_ids": manyIDs(501)} }), 422, "too_many_targets"},
		{"missing apk_url", good(func(m map[string]any) { delete(m, "apk_url") }), 422, "missing_apk_url"},
		{"http apk_url", good(func(m map[string]any) { m["apk_url"] = "http://example.com/a.apk" }), 422, "invalid_apk_url"},
		{"ftp apk_url", good(func(m map[string]any) { m["apk_url"] = "ftp://example.com/a.apk" }), 422, "invalid_apk_url"},
		{"apk_url no host", good(func(m map[string]any) { m["apk_url"] = "https://" }), 422, "invalid_apk_url"},
		{"apk_url creds", good(func(m map[string]any) { m["apk_url"] = "https://user@example.com/a.apk" }), 422, "invalid_apk_url"},
		{"apk_url too long", good(func(m map[string]any) { m["apk_url"] = "https://example.com/" + strings.Repeat("a", 2048) }), 422, "invalid_apk_url"},
		{"missing sha256", good(func(m map[string]any) { delete(m, "sha256") }), 422, "missing_sha256"},
		{"short sha256", good(func(m map[string]any) { m["sha256"] = "abc" }), 422, "invalid_sha256"},
		{"upper sha256", good(func(m map[string]any) { m["sha256"] = strings.ToUpper(appPushTestSHA) }), 422, "invalid_sha256"},
		{"nonhex sha256", good(func(m map[string]any) { m["sha256"] = strings.Repeat("z", 64) }), 422, "invalid_sha256"},
		{"missing package", good(func(m map[string]any) { delete(m, "package") }), 422, "missing_package"},
		{"package no dot", good(func(m map[string]any) { m["package"] = "nodots" }), 422, "invalid_package"},
		{"package bad chars", good(func(m map[string]any) { m["package"] = "com.example-app" }), 422, "invalid_package"},
		{"package too long", good(func(m map[string]any) { m["package"] = strings.Repeat("a", 255) + ".b" }), 422, "invalid_package"},
		{"version too long", good(func(m map[string]any) { m["version"] = strings.Repeat("v", 65) }), 422, "invalid_version"},
		{"missing label", good(func(m map[string]any) { delete(m, "label") }), 422, "missing_label"},
		{"empty label", good(func(m map[string]any) { m["label"] = "  " }), 422, "missing_label"},
		{"label too long", good(func(m map[string]any) { m["label"] = strings.Repeat("l", 101) }), 422, "invalid_label"},
		{"malformed json", `{`, 400, "invalid_json"},
		{"empty body", ``, 400, "invalid_json"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := postAppPush(t, mux, tc.body, nil, auth, false)
			if w.Code != tc.status {
				t.Fatalf("status %d, want %d (%s)", w.Code, tc.status, w.Body.String())
			}
			if code := errorCode(t, w); code != tc.code {
				t.Fatalf("error %q, want %q", code, tc.code)
			}
		})
	}

	// Oversize bodies are 413, same bound as notify.
	big := `{"target":{"all":true},"apk_url":"https://example.com/a.apk","sha256":"` + appPushTestSHA +
		`","package":"com.example.app","label":"` + strings.Repeat("l", notify.MaxRequestBodyBytes) + `"}`
	if w := postAppPush(t, mux, big, nil, auth, false); w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversize: status %d, want 413 (%s)", w.Code, w.Body.String())
	} else if code := errorCode(t, w); code != "body_too_large" {
		t.Fatalf("oversize: error %q, want body_too_large", code)
	}
}

// The marshaled-payload budget mirrors notify's MaxDataPayloadBytes: a
// maxed-out-but-valid request passes (no false trip), while a synthetic
// over-budget request fails the size gate with payload_too_long.
func TestAppPushPayloadSizeCap(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	big := `{"target":{"all":true},"apk_url":"https://example.com/` + strings.Repeat("a", 2000) +
		`","sha256":` + jsonQuote(appPushTestSHA) +
		`,"package":"` + strings.Repeat("p", 254) + `.b","version":"` + strings.Repeat("v", 64) +
		`","label":"` + strings.Repeat("l", 100) + `"}`
	if w := postAppPush(t, mux, big, nil, auth, false); w.Code != http.StatusAccepted {
		t.Fatalf("maxed-out valid request: status %d, want 202 (%s)", w.Code, w.Body.String())
	}

	over := appPushRequest{
		Target:  notify.Target{All: true},
		APKURL:  "https://example.com/" + strings.Repeat("a", 4000),
		SHA256:  appPushTestSHA,
		Package: "com.example.app",
		Label:   "Too big",
	}
	err := checkAppPushPayloadSize(over)
	verr, ok := err.(*notify.ValidationError)
	if !ok || verr.Code != "payload_too_long" {
		t.Fatalf("over-budget payload: got %v, want payload_too_long", err)
	}
}

// {"all":true} with empty/absent device_ids means all; both forms
// together are ambiguous_target (notify-style union, unlike the FCM
// variant where All absorbed device_ids).
func TestAppPushTargetSemantics(t *testing.T) {
	db := openAppPushTestDB(t)
	mustEnrollDevice(t, db, "a", "fcm-target-a")
	mustEnrollDevice(t, db, "b", "fcm-target-b")
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	for _, target := range []string{`{"all":true}`, `{"all":true,"device_ids":[]}`} {
		w := postAppPush(t, mux, validAppPushBody(target), nil, auth, false)
		if w.Code != http.StatusAccepted {
			t.Fatalf("%s: status %d, want 202 (%s)", target, w.Code, w.Body.String())
		}
		if _, accepted := decodePush202(t, w); accepted != 2 {
			t.Fatalf("%s: accepted=%v, want 2 (All wins)", target, accepted)
		}
	}

	w := postAppPush(t, mux, validAppPushBody(`{"all":true,"device_ids":["dev-x"]}`), nil, auth, false)
	if w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("both forms: status %d, want 422 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "ambiguous_target" {
		t.Fatalf("both forms: error %q, want ambiguous_target", code)
	}
}

// A 256-character package passes; 257 fails (matrix covers the reject).
func TestAppPushPackageLengthBoundary(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, _ := openAppPushMux(t, db, &captureSender{})
	pkg := strings.Repeat("a", 254) + ".b"
	if len(pkg) != 256 {
		t.Fatalf("fixture package is %d chars, want 256", len(pkg))
	}
	body := `{"target":{"all":true},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) +
		`,"package":` + jsonQuote(pkg) + `,"label":"Boundary"}`
	if w := postAppPush(t, mux, body, nil, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Fatalf("256-char package: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
}

// Unknown JSON fields are tolerated (match notify): only undecodable
// bodies are 400 invalid_json.
func TestAppPushUnknownFieldsTolerated(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, _ := openAppPushMux(t, db, &captureSender{})

	body := `{"target":{"all":true},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) +
		`,"package":"com.example.app","label":"Extra keys","bogus":1,"nested":{"x":[1,2]}}`
	if w := postAppPush(t, mux, body, nil, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Fatalf("unknown fields: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
}

// Audit-first atomicity: when the audit INSERT fails, the handler answers
// 500 and no pending row is queued; when the pending INSERT fails, no
// audit row is left behind either (single transaction, all-or-nothing).
func TestAppPushAuditFirstDBFailure(t *testing.T) {
	db := openAppPushTestDB(t)
	mustEnrollDevice(t, db, "a", "fcm-audit-a")
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken
	body := validAppPushBody(`{"all":true}`)

	if _, err := db.Exec(`DROP TABLE app_pushes`); err != nil {
		t.Fatal(err)
	}
	w := postAppPush(t, mux, body, nil, auth, false)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("audit failure: status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	var pending int
	if err := db.QueryRow(`SELECT COUNT(*) FROM app_push_commands`).Scan(&pending); err != nil {
		t.Fatal(err)
	}
	if pending != 0 {
		t.Fatalf("audit failure queued %d pending rows, want 0", pending)
	}
}

func TestAppPushPendingFailureLeavesNoAudit(t *testing.T) {
	db := openAppPushTestDB(t)
	mustEnrollDevice(t, db, "a", "fcm-audit-b")
	mux, _ := openAppPushMux(t, db, &captureSender{})

	if _, err := db.Exec(`DROP TABLE app_push_commands`); err != nil {
		t.Fatal(err)
	}
	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, "Bearer "+appPushTestToken, false)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("pending failure: status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if n := appPushAuditCount(t, db); n != 0 {
		t.Fatalf("pending failure left %d audit rows, want 0", n)
	}
}

// The audit row records the push with a redacted operator identity, and
// notification_history stays untouched.
func TestAppPushAuditRow(t *testing.T) {
	db := openAppPushTestDB(t)
	mustEnrollDevice(t, db, "a", "fcm-auditrow-a")
	mux, store := openAppPushMux(t, db, &captureSender{})
	cookie := loginCookie(t, store)

	// Token-authenticated push.
	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, "Bearer "+appPushTestToken, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, _ := decodePush202(t, w)

	var operator, target, apkURL, sha, pkg, label string
	var version sql.NullString
	var created string
	var accepted int
	if err := db.QueryRow(
		`SELECT operator, target, apk_url, sha256, package, version, label, created_at, accepted
		 FROM app_pushes WHERE push_id = ?`, pushID).
		Scan(&operator, &target, &apkURL, &sha, &pkg, &version, &label, &created, &accepted); err != nil {
		t.Fatalf("audit row missing: %v", err)
	}
	if operator != "operator-token" {
		t.Fatalf("operator = %q, want operator-token", operator)
	}
	if target != `{"all":true}` {
		t.Fatalf("target = %q, want canonical all:true", target)
	}
	if apkURL != appPushTestURL || sha != appPushTestSHA || pkg != "com.example.app" || label != "Test App" {
		t.Fatalf("audit fields = %q %q %q %q, want request values", apkURL, sha, pkg, label)
	}
	if !version.Valid || version.String != "1.2.3" {
		t.Fatalf("version = %+v, want 1.2.3", version)
	}
	if created == "" {
		t.Fatal("created_at must be stamped")
	}
	if accepted != 1 {
		t.Fatalf("accepted = %d, want 1", accepted)
	}
	for _, v := range []string{operator, target, apkURL, sha, pkg, label} {
		if strings.Contains(v, appPushTestToken) {
			t.Fatalf("audit row leaks the token value in %q", v)
		}
	}

	// Session-authenticated push labels the operator without the secret;
	// absent version is stored NULL.
	idB, _ := mustEnrollDevice(t, db, "b", "fcm-auditrow-b")
	sessBody := `{"target":{"device_ids":[` + jsonQuote(idB) + `]},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) + `,"package":"com.example.app","label":"No version"}`
	_ = idB
	w = postAppPush(t, mux, sessBody, cookie, "", true)
	if w.Code != http.StatusAccepted {
		t.Fatalf("session push: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, _ = decodePush202(t, w)
	var sessOp, sessTarget string
	var sessVer sql.NullString
	if err := db.QueryRow(`SELECT operator, target, version FROM app_pushes WHERE push_id = ?`, pushID).
		Scan(&sessOp, &sessTarget, &sessVer); err != nil {
		t.Fatalf("session audit row missing: %v", err)
	}
	if sessOp != "session:"+appPushTestUser {
		t.Fatalf("session operator = %q, want session:%s", sessOp, appPushTestUser)
	}
	if sessVer.Valid {
		t.Fatalf("absent version must be NULL, got %q", sessVer.String)
	}

	// notification_history is never written by app-push.
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM notification_history`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("notification_history has %d rows, want 0", n)
	}
}

// A target device already at the pending cap rejects the whole enqueue
// with 429 and queues nothing; other devices are unaffected.
func TestAppPushPendingCap(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, _ := mustEnrollDevice(t, db, "a", "fcm-cap-a")
	idB, _ := mustEnrollDevice(t, db, "b", "fcm-cap-b")
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	seedAppPushPending(t, db, "cap-push", idA, maxAppPushPendingPerDevice, formatTime(nowPlus(0)))

	before := appPushAuditCount(t, db)
	w := postAppPush(t, mux, validAppPushBody(`{"device_ids":[`+jsonQuote(idA)+`]}`), nil, auth, false)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("capped device: status %d, want 429 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "too_many_pending" {
		t.Fatalf("capped device: error %q, want too_many_pending", code)
	}
	if got := appPushPendingCount(t, db, idA); got != maxAppPushPendingPerDevice {
		t.Fatalf("capped device queued more rows: %d, want %d", got, maxAppPushPendingPerDevice)
	}
	if n := appPushAuditCount(t, db); n != before {
		t.Fatalf("429 left %d audit rows, want %d (none)", n, before)
	}

	// A device under the cap still enqueues fine (per-device scoping).
	w = postAppPush(t, mux, validAppPushBody(`{"device_ids":[`+jsonQuote(idB)+`]}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("uncapped device: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 1 {
		t.Fatalf("uncapped device: accepted=%v, want 1", accepted)
	}
}
