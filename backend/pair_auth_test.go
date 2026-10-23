// Package main — P8 pairing end-to-end auth reconciliation tests.
//
// Covers: unauthenticated pairing routes (401 JSON on /api/*, 302 to
// /login on /webui/*), authenticated happy paths, enroll-token single-use,
// CSRF reject/accept on the revoke form, rate limiting, UTF-8-safe names,
// nil-guarded store adapter, and EXTERNAL_BASE_URL QR behavior.
package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

const pairTestSecret = "test-secret-0123456789abcdef-test"

func setupPairingMux(t *testing.T) (*http.ServeMux, *SessionStore, *webui.CSRFProtection, *http.Cookie) {
	t.Helper()
	db := openDevicesTestDB(t)
	oldDB := deviceDB
	SetDeviceDB(db)
	t.Cleanup(func() { SetDeviceDB(oldDB) })

	oldBase := webui.ExternalBaseURL
	webui.ExternalBaseURL = ""
	t.Cleanup(func() { webui.ExternalBaseURL = oldBase })
	resetEnrollIssueLimiter()

	sessions := NewSessionStore(db, pairTestSecret)
	csrf := webui.NewCSRFProtection(pairTestSecret)
	mux := NewMux(db, notify.NoopSender{}, sessions, csrf)

	w := httptest.NewRecorder()
	if err := sessions.CreateSession(w, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	cookie := sessionCookie(w)
	if cookie == nil {
		t.Fatal("no session cookie minted")
	}
	if SessionCookieName != "pc_session" {
		t.Fatalf("SessionCookieName %q diverged from webui reader", SessionCookieName)
	}
	if cookie.Name != SessionCookieName {
		t.Fatalf("session cookie name %q, want pc_session", cookie.Name)
	}
	return mux, sessions, csrf, cookie
}

func doReq(t *testing.T, mux *http.ServeMux, method, target, body, contentType string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	var reader *strings.Reader
	if body == "" {
		reader = strings.NewReader("")
	} else {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, target, reader)
	req.RemoteAddr = "192.0.2.1:1234"
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func TestPairingUnauthenticatedGating(t *testing.T) {
	mux, _, _, _ := setupPairingMux(t)

	// JSON APIs → 401 with {"error":"unauthorized"}.
	for _, tc := range []struct{ method, target, body string }{
		{"POST", "/api/v1/enroll-tokens", ""},
		{"GET", "/api/v1/devices", ""},
		{"DELETE", "/api/v1/devices/some-id", ""},
	} {
		w := doReq(t, mux, tc.method, tc.target, tc.body, "application/json", nil)
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s %s: got %d, want 401", tc.method, tc.target, w.Code)
		}
		var got map[string]string
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || got["error"] != "unauthorized" {
			t.Errorf("%s %s: want JSON unauthorized, got %q", tc.method, tc.target, w.Body.String())
		}
		if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "application/json") {
			t.Errorf("%s %s: content type %q, want json", tc.method, tc.target, ct)
		}
	}

	// WebUI pages → 302 to /login.
	for _, tc := range []struct{ method, target, body string }{
		{"GET", "/webui/pair", ""},
		{"POST", "/webui/pair/revoke", url.Values{"device_id": {"x"}}.Encode()},
	} {
		ct := ""
		if tc.method == "POST" {
			ct = "application/x-www-form-urlencoded"
		}
		w := doReq(t, mux, tc.method, tc.target, tc.body, ct, nil)
		if w.Code != http.StatusFound {
			t.Errorf("%s %s: got %d, want 302", tc.method, tc.target, w.Code)
		}
		if loc := w.Header().Get("Location"); loc != "/login" {
			t.Errorf("%s %s: redirect to %q, want /login", tc.method, tc.target, loc)
		}
	}
}

func TestPairingEnrollTokenOnlyNoSession(t *testing.T) {
	mux, _, _, cookie := setupPairingMux(t)

	// Issue a token WITH the session (operator-only).
	w := doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	if w.Code != http.StatusCreated {
		t.Fatalf("issue token: got %d (%s), want 201", w.Code, w.Body.String())
	}
	var issued map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &issued); err != nil {
		t.Fatal(err)
	}
	token := issued["enroll_token"]
	if token == "" {
		t.Fatal("empty enroll_token")
	}

	// Enroll WITHOUT any session cookie: token-only auth must succeed.
	enrollBody := `{"enroll_token":` + strconv(token) + `,"device_name":"Pixel 8","fcm_token":"fcm-p8-1"}`
	w = doReq(t, mux, "POST", "/api/v1/enroll", enrollBody, "application/json", nil)
	if w.Code != http.StatusCreated {
		t.Fatalf("enroll without session: got %d (%s), want 201", w.Code, w.Body.String())
	}
	var enrolled map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &enrolled); err != nil {
		t.Fatal(err)
	}
	if enrolled["device_id"] == "" || enrolled["device_token"] == "" || enrolled["device_key"] == "" {
		t.Fatalf("incomplete enroll response: %v", enrolled)
	}
	keyBytes, err := base64.StdEncoding.DecodeString(enrolled["device_key"])
	if err != nil || len(keyBytes) != 32 {
		t.Fatalf("device_key must be base64 256-bit, got %q err=%v", enrolled["device_key"], err)
	}

	// Single-use: second POST with the same token fails closed with 410,
	// even WITH a valid admin session (session must not bypass token).
	w = doReq(t, mux, "POST", "/api/v1/enroll", enrollBody, "application/json", cookie)
	if w.Code != http.StatusGone {
		t.Fatalf("replay with session: got %d (%s), want 410", w.Code, w.Body.String())
	}

	// Missing token → 400, bogus token → 410.
	w = doReq(t, mux, "POST", "/api/v1/enroll", `{"device_name":"x"}`, "application/json", nil)
	if w.Code != http.StatusBadRequest {
		t.Errorf("missing token: got %d, want 400", w.Code)
	}
	w = doReq(t, mux, "POST", "/api/v1/enroll", `{"enroll_token":"bogus"}`, "application/json", nil)
	if w.Code != http.StatusGone {
		t.Errorf("bogus token: got %d, want 410", w.Code)
	}
}

func strconv(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

func TestPairingDevicesListRevokeHappyPath(t *testing.T) {
	mux, _, _, cookie := setupPairingMux(t)

	// Pair one device via token flow.
	w := doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	var issued map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &issued); err != nil {
		t.Fatal(err)
	}
	enrollBody := `{"enroll_token":` + strconv(issued["enroll_token"]) + `,"device_name":"E2E Phone","fcm_token":"fcm-e2e-1"}`
	w = doReq(t, mux, "POST", "/api/v1/enroll", enrollBody, "application/json", nil)
	var enrolled map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &enrolled); err != nil {
		t.Fatal(err)
	}
	id, devToken := enrolled["device_id"], enrolled["device_token"]

	// Authenticated list contains the device; secret-free.
	w = doReq(t, mux, "GET", "/api/v1/devices", "", "", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("list: got %d (%s)", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, id) {
		t.Fatalf("list missing device %s: %s", id, body)
	}
	if strings.Contains(body, devToken) {
		t.Fatal("device list leaks raw bearer token")
	}

	// Device Bearer auth works pre-revoke.
	req, _ := http.NewRequest("GET", "/api/v1/widgets", nil)
	req.Header.Set("Authorization", "Bearer "+devToken)
	if _, err := AuthenticateDevice(deviceDB, req); err != nil {
		t.Fatalf("auth before revoke: %v", err)
	}

	// Revoke → 204; already-revoked re-revoke stays 204 (idempotent);
	// unknown ID → 404 device_not_found.
	w = doReq(t, mux, "DELETE", "/api/v1/devices/"+id, "", "", cookie)
	if w.Code != http.StatusNoContent {
		t.Fatalf("revoke: got %d (%s), want 204", w.Code, w.Body.String())
	}
	w = doReq(t, mux, "DELETE", "/api/v1/devices/"+id, "", "", cookie)
	if w.Code != http.StatusNoContent {
		t.Fatalf("re-revoke idempotent: got %d, want 204", w.Code)
	}
	w = doReq(t, mux, "DELETE", "/api/v1/devices/does-not-exist", "", "", cookie)
	if w.Code != http.StatusNotFound {
		t.Fatalf("unknown revoke: got %d, want 404", w.Code)
	}
	var404 := map[string]string{}
	if err := json.Unmarshal(w.Body.Bytes(), &var404); err != nil || var404["error"] != "device_not_found" {
		t.Fatalf("unknown revoke body: %q", w.Body.String())
	}

	// Revocation is immediate: Bearer auth now fails.
	if _, err := AuthenticateDevice(deviceDB, req); err == nil {
		t.Fatal("revoked token still authenticates")
	}
}

func TestPairingWebUIPageAndCSRFRevoke(t *testing.T) {
	mux, _, _, cookie := setupPairingMux(t)

	// Authenticated page renders with CSRF token + QR payload.
	w := doReq(t, mux, "GET", "/webui/pair", "", "", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("pair page: got %d (%s)", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Fatalf("pair page content type %q, want html", ct)
	}
	page := w.Body.String()
	csrfToken := extractCSRFToken(t, page)

	// Pair a device to have something to revoke via the form.
	iw := doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	var issued map[string]string
	if err := json.Unmarshal(iw.Body.Bytes(), &issued); err != nil {
		t.Fatal(err)
	}
	enrollBody := `{"enroll_token":` + strconv(issued["enroll_token"]) + `,"device_name":"WebUI Phone","fcm_token":"fcm-webui-1"}`
	ew := doReq(t, mux, "POST", "/api/v1/enroll", enrollBody, "application/json", nil)
	var enrolled map[string]string
	if err := json.Unmarshal(ew.Body.Bytes(), &enrolled); err != nil {
		t.Fatal(err)
	}
	id := enrolled["device_id"]

	// POST without CSRF → 403 (fail closed).
	form := url.Values{"device_id": {id}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/revoke", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusForbidden {
		t.Fatalf("revoke without CSRF: got %d, want 403", w.Code)
	}

	// POST with wrong CSRF → 403.
	form = url.Values{"device_id": {id}, "csrf_token": {"wrong"}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/revoke", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusForbidden {
		t.Fatalf("revoke with bad CSRF: got %d, want 403", w.Code)
	}

	// POST with valid CSRF → 303 back to /webui/pair; device revoked.
	form = url.Values{"device_id": {id}, "csrf_token": {csrfToken}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/revoke", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("revoke with CSRF: got %d (%s), want 303", w.Code, w.Body.String())
	}
	if loc := w.Header().Get("Location"); loc != "/webui/pair" {
		t.Fatalf("revoke redirect to %q, want /webui/pair", loc)
	}
	found, err := RevokeDevice(deviceDB, id)
	if err != nil || !found {
		t.Fatalf("device should now read revoked: found=%v err=%v", found, err)
	}

	// Unknown ID with valid CSRF → 303 with ?error=device_not_found, and
	// the follow-up page shows the operator notice as HTML.
	form = url.Values{"device_id": {"no-such-device"}, "csrf_token": {csrfToken}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/revoke", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("unknown revoke: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/webui/pair?error=device_not_found" {
		t.Fatalf("unknown revoke redirect to %q", loc)
	}
	w = doReq(t, mux, "GET", "/webui/pair?error=device_not_found", "", "", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("notice page: got %d", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Fatalf("notice page content type %q", ct)
	}
	if !strings.Contains(w.Body.String(), "Device not found") {
		t.Fatalf("notice page missing unknown-id UX: %s", w.Body.String())
	}

	// Cross-session CSRF: token minted for another session is rejected.
	other := httptest.NewRecorder()
	sess2 := NewSessionStore(deviceDB, pairTestSecret)
	if err := sess2.CreateSession(other, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	otherCookie := sessionCookie(other)
	otherPage := doReq(t, mux, "GET", "/webui/pair", "", "", otherCookie)
	otherToken := extractCSRFToken(t, otherPage.Body.String())
	form = url.Values{"device_id": {id}, "csrf_token": {otherToken}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/revoke", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusForbidden {
		t.Fatalf("cross-session CSRF: got %d, want 403", w.Code)
	}
}

func extractCSRFToken(t *testing.T, page string) string {
	t.Helper()
	const marker = `name="csrf_token" value="`
	i := strings.Index(page, marker)
	if i < 0 {
		const meta = `<meta name="csrf-token" content="`
		i = strings.Index(page, meta)
		if i < 0 {
			t.Fatalf("pair page missing csrf_token field")
		}
		rest := page[i+len(meta):]
		j := strings.Index(rest, `"`)
		if j < 0 {
			t.Fatal("unterminated csrf meta value")
		}
		if tok := rest[:j]; tok != "" {
			return tok
		}
		t.Fatal("empty CSRF token on pair page")
	}
	rest := page[i+len(marker):]
	j := strings.Index(rest, `"`)
	if j < 0 {
		t.Fatal("unterminated csrf_token value")
	}
	tok := rest[:j]
	if tok == "" {
		t.Fatal("empty CSRF token on pair page")
	}
	return tok
}

func TestPairingWebUIErrorContentType(t *testing.T) {
	// Nil store forces the 500 error path; it must still be HTML.
	h := webui.HandlePairPage(nil, EnrollTokenTTL, webui.NewCSRFProtection(pairTestSecret))
	req := httptest.NewRequest("GET", "/webui/pair", nil)
	req.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	h(w, req)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("got %d, want 500", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Fatalf("error path content type %q, want html", ct)
	}
}

func TestPairingRateLimitOnTokenIssuance(t *testing.T) {
	mux, _, _, cookie := setupPairingMux(t)
	now := time.Now()
	enrollIssueNow = func() time.Time { return now }
	t.Cleanup(func() { enrollIssueNow = time.Now })

	// Fill the per-IP bucket (test client IP is 192.0.2.1).
	enrollIssueMu.Lock()
	full := make([]time.Time, maxEnrollIssuesPerWindow)
	for i := range full {
		full[i] = now
	}
	enrollIssueByIP["192.0.2.1"] = full
	enrollIssueMu.Unlock()

	w := doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("limited API issue: got %d, want 429", w.Code)
	}
	var got map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || got["error"] != "rate_limited" {
		t.Fatalf("limited API body: %q", w.Body.String())
	}

	w = doReq(t, mux, "GET", "/webui/pair", "", "", cookie)
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("limited page issue: got %d, want 429", w.Code)
	}

	// Window passes → issuance works again.
	enrollIssueNow = func() time.Time { return now.Add(2 * enrollIssueWindow) }
	w = doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	if w.Code != http.StatusCreated {
		t.Fatalf("post-window issue: got %d (%s), want 201", w.Code, w.Body.String())
	}
}

func TestPairingUTF8SafeNameTruncation(t *testing.T) {
	db := openDevicesTestDB(t)
	long := strings.Repeat("🚀", 100) // 400 bytes, 100 runes
	id, _, _, err := EnrollDevice(db, long, "fcm-utf8")
	if err != nil {
		t.Fatal(err)
	}
	var stored string
	if err := db.QueryRow(`SELECT name FROM devices WHERE id = ?`, id).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if len(stored) > 200 {
		t.Fatalf("stored name %d bytes, want <=200", len(stored))
	}
	if !utf8.ValidString(stored) {
		t.Fatal("stored name is invalid UTF-8 (rune split)")
	}
	if stored == "" {
		t.Fatal("stored name empty")
	}
}

func TestPairingStoreAdapterNilGuard(t *testing.T) {
	oldDB := deviceDB
	SetDeviceDB(nil)
	t.Cleanup(func() { SetDeviceDB(oldDB) })
	var a webuiStoreAdapter
	if _, _, err := a.IssueEnrollToken(t.Context(), time.Minute); err == nil {
		t.Error("IssueEnrollToken(nil db): want error")
	}
	if _, err := a.ListDevices(t.Context()); err == nil {
		t.Error("ListDevices(nil db): want error")
	}
	if _, err := a.RevokeDevice(t.Context(), "x"); err == nil {
		t.Error("RevokeDevice(nil db): want error")
	}
}

func TestPairingQRExternalBaseURL(t *testing.T) {
	webui.ExternalBaseURL = "https://example.com/"
	t.Cleanup(func() { webui.ExternalBaseURL = "" })
	// Note: trailing slash is normalized by main; normalize here the same way.
	webui.ExternalBaseURL = "https://example.com"
	req := httptest.NewRequest("GET", "/webui/pair", nil)
	req.Host = "evil.test"
	req.Header.Set("X-Forwarded-Proto", "http")
	payload, err := webui.EnrollPayloadForRequest(req, "tok123")
	if err != nil {
		t.Fatal(err)
	}
	var got map[string]string
	if err := json.Unmarshal([]byte(payload), &got); err != nil {
		t.Fatal(err)
	}
	if got["enroll_url"] != "https://example.com/api/v1/enroll" {
		t.Fatalf("configured base ignored: %s", payload)
	}
	if got["enroll_token"] != "tok123" {
		t.Fatalf("token not preserved: %s", payload)
	}

	// Dev fallback: Host + X-Forwarded-Proto.
	webui.ExternalBaseURL = ""
	req = httptest.NewRequest("GET", "/webui/pair", nil)
	req.Host = "inner.local:8080"
	req.Header.Set("X-Forwarded-Proto", "https")
	payload, err = webui.EnrollPayloadForRequest(req, "tok123")
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal([]byte(payload), &got); err != nil {
		t.Fatal(err)
	}
	if got["enroll_url"] != "https://inner.local:8080/api/v1/enroll" {
		t.Fatalf("dev fallback wrong: %s", payload)
	}
}

func TestConfigExternalBaseURLValidation(t *testing.T) {
	c := validTestConfig()
	c.ExternalBaseURL = "https://example.com"
	if err := c.Validate(); err != nil {
		t.Fatalf("valid base rejected: %v", err)
	}
	c.ExternalBaseURL = ""
	if err := c.Validate(); err != nil {
		t.Fatalf("empty base (dev fallback) rejected: %v", err)
	}
	for _, bad := range []string{
		"http://example.com/enroll",
		"https://example.com?x=1",
		"ftp://example.com",
		"example.com",
	} {
		c.ExternalBaseURL = bad
		if err := c.Validate(); err == nil {
			t.Errorf("base %q: expected rejection", bad)
		}
	}
}
