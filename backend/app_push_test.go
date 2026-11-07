// Package main — POST /api/v1/app-push tests (remote APK install push).
//
// Covers: operator-token config boot gate, the auth matrix (no auth,
// device Bearer, bad Bearer, session without write guard, session, token),
// the validation matrix (every bad field → 422 with a stable code), the
// exact data-payload shape through a fake driver, per-device failure
// isolation, audit-row contents (redacted operator identity), and that
// notification_history stays untouched.
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
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
// schemas applied and one live device row (dev-1).
func openAppPushTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db := openNotifyTestDB(t)
	if err := MigrateAppPush(db); err != nil {
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

// openAppPushMux builds the production router (NewMux: notify + app-push)
// backed by db/sender, with operator auth globals set.
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
	badTargetBody := validAppPushBody(`{"device_ids":["nope"]}`)

	// No auth → 401.
	if w := postAppPush(t, mux, badTargetBody, nil, "", false); w.Code != http.StatusUnauthorized {
		t.Errorf("no auth: got %d, want 401 (%s)", w.Code, w.Body.String())
	}

	// Unknown Bearer → 403 (blanket: any non-operator Bearer is
	// forbidden, no DB lookup, no fallthrough to the session).
	if w := postAppPush(t, mux, badTargetBody, nil, "Bearer garbage-token", false); w.Code != http.StatusForbidden {
		t.Errorf("bad bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Valid device Bearer → 403, even with a session alongside.
	_, devTok, _ := func() (string, string, string) {
		id, tok, key, err := EnrollDevice(db, "phone", "fcm-auth-matrix")
		if err != nil {
			t.Fatal(err)
		}
		_ = key
		return id, tok, key
	}()
	if w := postAppPush(t, mux, badTargetBody, nil, "Bearer "+devTok, false); w.Code != http.StatusForbidden {
		t.Errorf("device bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}
	if w := postAppPush(t, mux, badTargetBody, cookie, "Bearer "+devTok, true); w.Code != http.StatusForbidden {
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
	if w := postAppPush(t, mux, badTargetBody, nil, "Bearer "+devTok, false); w.Code != http.StatusForbidden {
		t.Errorf("revoked device bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Session without the write-guard marker → 403 per existing convention.
	if w := postAppPush(t, mux, badTargetBody, cookie, "", false); w.Code != http.StatusForbidden {
		t.Errorf("session w/o guard: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Session + guard → 202 (zero targets is a normal 202).
	if w := postAppPush(t, mux, badTargetBody, cookie, "", true); w.Code != http.StatusAccepted {
		t.Errorf("session+guard: got %d, want 202 (%s)", w.Code, w.Body.String())
	}

	// Operator token alone (no session, no guard) → 202.
	if w := postAppPush(t, mux, badTargetBody, nil, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Errorf("operator token: got %d, want 202 (%s)", w.Code, w.Body.String())
	}

	// Wrong operator token → 403 (not 401: any wrong Bearer is forbidden).
	if w := postAppPush(t, mux, badTargetBody, nil, "Bearer "+appPushTestToken+"-wrong", false); w.Code != http.StatusForbidden {
		t.Errorf("wrong token: got %d, want 403 (%s)", w.Code, w.Body.String())
	}

	// Operator token wins over a guard-less session (token path needs no CSRF).
	if w := postAppPush(t, mux, badTargetBody, cookie, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Errorf("token+session: got %d, want 202 (%s)", w.Code, w.Body.String())
	}

	// Session + unknown Bearer → 403: a presented credential never falls
	// through to the session.
	if w := postAppPush(t, mux, badTargetBody, cookie, "Bearer garbage-token", true); w.Code != http.StatusForbidden {
		t.Errorf("session+bad bearer: got %d, want 403 (%s)", w.Code, w.Body.String())
	}
}

// The operator token is also accepted on POST /api/v1/notify; the
// session path keeps working unchanged.
func TestAppPushTokenAcceptedOnNotify(t *testing.T) {
	db := openAppPushTestDB(t)
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
	goodTarget := `{"all":true}`
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
	_ = goodTarget

	cases := []struct {
		name   string
		body   string
		status int
		code   string
	}{
		{"empty body target", `{"apk_url":"https://example.com/a.apk","sha256":"` + appPushTestSHA + `","package":"com.example.app","label":"x"}`, 422, "empty_target"},
		{"empty device_ids", good(func(m map[string]any) { m["target"] = map[string]any{"device_ids": []string{}} }), 422, "empty_target"},
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

// The fan-out emits exactly the contracted data shape through the
// existing driver, counts accepted, and skips unknown ids.
func TestAppPushFanoutPayload(t *testing.T) {
	db := openAppPushTestDB(t)
	addNotifyDevice(t, db, "dev-2")
	sender := &captureSender{}
	mux, _ := openAppPushMux(t, db, sender)
	auth := "Bearer " + appPushTestToken

	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("all: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := decodePush202(t, w)
	if accepted != 2 || pushID == "" {
		t.Fatalf("all: push_id=%q accepted=%v, want id + 2", pushID, accepted)
	}
	if len(pushID) != 32 || !isLowerHex(pushID) {
		t.Fatalf("push_id %q is not 128-bit hex", pushID)
	}
	if len(sender.payloads) != 2 {
		t.Fatalf("want 2 payloads, got %d", len(sender.payloads))
	}
	want := map[string]string{
		"type":    "app_install",
		"push_id": pushID,
		"apk_url": appPushTestURL,
		"sha256":  appPushTestSHA,
		"package": "com.example.app",
		"version": "1.2.3",
		"label":   "Test App",
	}
	for i, p := range sender.payloads {
		if len(p) != len(want) {
			t.Fatalf("payload %d keys %v, want exactly %v", i, p, want)
		}
		for k, v := range want {
			if p[k] != v {
				t.Fatalf("payload %d[%q] = %q, want %q (full: %v)", i, k, p[k], v, p)
			}
		}
	}

	// Unknown ids are tolerated: still 202, accepted counts live devices.
	sender.payloads = nil
	w = postAppPush(t, mux, validAppPushBody(`{"device_ids":["dev-1","nope"]}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("mixed ids: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 1 {
		t.Fatalf("mixed ids: accepted=%v, want 1", accepted)
	}

	// Absent version rides as an empty flat string (shape stays stable).
	sender.payloads = nil
	body := `{"target":{"device_ids":["dev-1"]},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) + `,"package":"com.example.app","label":"No version"}`
	if w := postAppPush(t, mux, body, nil, auth, false); w.Code != http.StatusAccepted {
		t.Fatalf("no version: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if len(sender.payloads) != 1 || sender.payloads[0]["version"] != "" {
		t.Fatalf("no version: payload %v, want empty version key", sender.payloads)
	}
	if _, ok := sender.payloads[0]["version"]; !ok {
		t.Fatal("no version: version key must be present (flat stable shape)")
	}
}

// failDeviceSender fails a single named device so the test can prove one
// device's failure never aborts the rest of the fan-out.
type failDeviceSender struct {
	fail string
	sent []string
}

func (s *failDeviceSender) Send(_ context.Context, d notify.Device, _ map[string]string) error {
	if d.ID == s.fail {
		return errors.New("test send failure for " + d.ID)
	}
	s.sent = append(s.sent, d.ID)
	return nil
}

func TestAppPushSendFailureIsolated(t *testing.T) {
	db := openAppPushTestDB(t)
	addNotifyDevice(t, db, "dev-2")
	sender := &failDeviceSender{fail: "dev-1"}
	mux, _ := openAppPushMux(t, db, sender)
	auth := "Bearer " + appPushTestToken

	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 1 {
		t.Fatalf("accepted=%v, want 1 (dev-2 delivered despite dev-1 failing)", accepted)
	}
	if len(sender.sent) != 1 || sender.sent[0] != "dev-2" {
		t.Fatalf("delivered %v, want [dev-2]", sender.sent)
	}
}

// A driver that fails every send is 500 driver_unavailable. The audit row
// stays with accepted:0 as the record of the attempt (audit-first: the
// row lands before fan-out, so a total send failure cannot un-record it).
func TestAppPushAllSendsFail(t *testing.T) {
	db := openAppPushTestDB(t)
	sender := &failDeviceSender{fail: "dev-1"}
	mux, _ := openAppPushMux(t, db, sender)
	auth := "Bearer " + appPushTestToken

	w := postAppPush(t, mux, validAppPushBody(`{"device_ids":["dev-1"]}`), nil, auth, false)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "driver_unavailable" {
		t.Fatalf("error %q, want driver_unavailable", code)
	}
	var accepted int
	if err := db.QueryRow(`SELECT accepted FROM app_pushes`).Scan(&accepted); err != nil {
		t.Fatalf("audit row missing after total failure: %v", err)
	}
	if accepted != 0 {
		t.Fatalf("accepted = %d, want 0", accepted)
	}
}

// Unknown JSON fields are tolerated (match notify): only undecodable
// bodies are 400 invalid_json.
func TestAppPushUnknownFieldsTolerated(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	body := `{"target":{"all":true},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) +
		`,"package":"com.example.app","label":"Extra keys","bogus":1,"nested":{"x":[1,2]}}`
	if w := postAppPush(t, mux, body, nil, auth, false); w.Code != http.StatusAccepted {
		t.Fatalf("unknown fields: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
}

// {"all":true} absorbs device_ids entirely — even a non-empty list —
// normalizing to All (unlike notify's ambiguous_target). The audit row
// records the canonical {"all":true}.
func TestAppPushAllIgnoresDeviceIDs(t *testing.T) {
	for _, target := range []string{
		`{"all":true,"device_ids":["dev-1"]}`,
		`{"all":true,"device_ids":[]}`,
	} {
		db := openAppPushTestDB(t)
		addNotifyDevice(t, db, "dev-2")
		mux, _ := openAppPushMux(t, db, &captureSender{})
		w := postAppPush(t, mux, validAppPushBody(target), nil, "Bearer "+appPushTestToken, false)
		if w.Code != http.StatusAccepted {
			t.Fatalf("%s: status %d, want 202 (%s)", target, w.Code, w.Body.String())
		}
		pushID, accepted := decodePush202(t, w)
		if accepted != 2 {
			t.Fatalf("%s: accepted=%v, want 2 (All wins)", target, accepted)
		}
		var stored string
		if err := db.QueryRow(`SELECT target FROM app_pushes WHERE push_id = ?`, pushID).Scan(&stored); err != nil {
			t.Fatalf("%s: audit row missing: %v", target, err)
		}
		if stored != `{"all":true}` {
			t.Fatalf("%s: audit target = %q, want canonical all:true", target, stored)
		}
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

// Audit-first: when the audit INSERT fails, the handler answers 500 and
// the driver is never touched — nothing is sent.
func TestAppPushAuditFirstDBFailure(t *testing.T) {
	db := openAppPushTestDB(t)
	sender := &captureSender{}
	mux, _ := openAppPushMux(t, db, sender)
	if _, err := db.Exec(`DROP TABLE app_pushes`); err != nil {
		t.Fatal(err)
	}

	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, "Bearer "+appPushTestToken, false)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if len(sender.payloads) != 0 {
		t.Fatalf("audit failure sent %d payloads, want 0", len(sender.payloads))
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

// with NO audit row (rejected before audit, mirroring notify which writes
// no history there). Genuinely zero targets stay 202 accepted:0 behind
// the same stub.
func TestAppPushStubDriverUnavailable(t *testing.T) {
	for _, sender := range []notify.Sender{notify.NoopSender{}, notify.LogSender{}} {
		db := openAppPushTestDB(t)
		mux, _ := openAppPushMux(t, db, sender)
		auth := "Bearer " + appPushTestToken

		w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, auth, false)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("%T with targets: status %d, want 500 (%s)", sender, w.Code, w.Body.String())
		}
		if code := errorCode(t, w); code != "driver_unavailable" {
			t.Fatalf("%T with targets: error %q, want driver_unavailable", sender, code)
		}
		var n int
		if err := db.QueryRow(`SELECT COUNT(*) FROM app_pushes`).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Fatalf("%T stub rejection wrote %d audit rows, want 0", sender, n)
		}

		// Zero targets are still 202 accepted:0 behind the same stub.
		w = postAppPush(t, mux, validAppPushBody(`{"device_ids":["nope"]}`), nil, auth, false)
		if w.Code != http.StatusAccepted {
			t.Fatalf("%T zero targets: status %d, want 202 (%s)", sender, w.Code, w.Body.String())
		}
		if _, accepted := decodePush202(t, w); accepted != 0 {
			t.Fatalf("%T zero targets: accepted=%v, want 0", sender, accepted)
		}
	}
}

// Revoked devices are excluded from both target forms; genuinely zero
// targets are a normal 202 with accepted:0.
func TestAppPushSkipsRevoked(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken
	if _, err := RevokeDevice(db, "dev-1"); err != nil {
		t.Fatal(err)
	}

	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("revoked all: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 0 {
		t.Fatalf("revoked all: accepted=%v, want 0", accepted)
	}

	w = postAppPush(t, mux, validAppPushBody(`{"device_ids":["dev-1"]}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("revoked id: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 0 {
		t.Fatalf("revoked id: accepted=%v, want 0", accepted)
	}
}

// The audit row records the push with a redacted operator identity, and
// notification_history stays untouched.
func TestAppPushAuditRow(t *testing.T) {
	db := openAppPushTestDB(t)
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
	if err := db.QueryRow(
		`SELECT operator, target, apk_url, sha256, package, version, label, created_at
		 FROM app_pushes WHERE push_id = ?`, pushID).
		Scan(&operator, &target, &apkURL, &sha, &pkg, &version, &label, &created); err != nil {
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
	for _, v := range []string{operator, target, apkURL, sha, pkg, label} {
		if strings.Contains(v, appPushTestToken) {
			t.Fatalf("audit row leaks the token value in %q", v)
		}
	}

	// Session-authenticated push labels the operator without the secret.
	sessBody := `{"target":{"device_ids":["dev-1"]},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) + `,"package":"com.example.app","label":"No version"}`
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
	if sessTarget != `{"device_ids":["dev-1"]}` {
		t.Fatalf("session target = %q, want canonical device_ids", sessTarget)
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
