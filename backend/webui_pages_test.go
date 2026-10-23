// Package main — WebUI operator surface integration tests (dashboard,
// widgets preview, notify form): auth gates, page content, secret masking,
// and the form round-trip through the shared fan-out pipeline.
package main

import (
	"database/sql"
	"net/http"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
	_ "modernc.org/sqlite"
)

const webuiTestSecret = "webui-test-session-secret-01234567"

// setupWebUITestMux mirrors production wiring (main.go): base mux with
// dashboard + widgets preview, the API notify route, the login routes, and
// the notify test form — all wrapped in the auth no-store pin. The widget
// registry is temp-dir-backed so previews are deterministic.
func setupWebUITestMux(t *testing.T) (mux http.Handler, db *sql.DB, csrf *webui.CSRFProtection, sender *captureSender, reg *WidgetRegistry, cookie *http.Cookie) {
	t.Helper()
	db, err := OpenDB(t.TempDir() + "/webui-test.db")
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	if err := notify.Migrate(db); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	oldDB := deviceDB
	SetDeviceDB(db)
	t.Cleanup(func() { SetDeviceDB(oldDB) })
	resetEnrollIssueLimiter()

	oldBase := webui.ExternalBaseURL
	webui.ExternalBaseURL = ""
	t.Cleanup(func() { webui.ExternalBaseURL = oldBase })

	sessions, err := NewSessionStore(db, webuiTestSecret)
	if err != nil {
		t.Fatal(err)
	}
	csrf = webui.NewCSRFProtection(webuiTestSecret)
	sender = &captureSender{}

	reg = NewWidgetRegistry(t.TempDir())
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}

	base := newBaseMux(reg, sessions, csrf)
	base.Handle("POST /api/v1/notify", notifyAdminChain(sessions, csrf, handleNotify(db, sender)))
	RegisterNotifyWebUIRoutes(base, db, sender, sessions, csrf)
	webui.RegisterRoutes(base, sessions, webui.Credentials{Username: "op", Password: "op-pass"}, csrf)
	mux = withAuthNoStore(base)

	w := httptest.NewRecorder()
	if err := sessions.CreateSession(w, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	cookie = sessionCookie(w)
	if cookie == nil {
		t.Fatal("no session cookie minted")
	}
	return mux, db, csrf, sender, reg, cookie
}

// webuiCSRFToken mints the session-bound token for the given cookie.
func webuiCSRFToken(t *testing.T, csrf *webui.CSRFProtection, cookie *http.Cookie) string {
	t.Helper()
	req := httptest.NewRequest("POST", "/webui/notify", nil)
	req.AddCookie(cookie)
	tok, ok := csrf.TokenForRequest(req)
	if !ok || tok == "" {
		t.Fatal("no CSRF token minted for test session")
	}
	return tok
}

// webuiFormPost posts a urlencoded operator form with session + CSRF token.
func webuiFormPost(t *testing.T, mux http.Handler, target string, cookie *http.Cookie, form url.Values) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest("POST", target, strings.NewReader(form.Encode()))
	req.RemoteAddr = "192.0.2.1:1234"
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if cookie != nil {
		req.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func webuiGet(t *testing.T, mux http.Handler, target string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest("GET", target, nil)
	req.RemoteAddr = "192.0.2.1:1234"
	if cookie != nil {
		req.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func TestWebUIOperatorGates(t *testing.T) {
	mux, _, _, _, _, _ := setupWebUITestMux(t)

	// Anonymous browser pages → 302 to /login.
	for _, target := range []string{"/", "/webui/pair", "/webui/widgets", "/webui/notify"} {
		w := webuiGet(t, mux, target, nil)
		if w.Code != http.StatusFound {
			t.Errorf("anon GET %s: got %d, want 302", target, w.Code)
		}
		if loc := w.Header().Get("Location"); loc != "/login" {
			t.Errorf("anon GET %s: redirect to %q, want /login", target, loc)
		}
	}
	// Anonymous form POSTs → 302 as well (auth first, CSRF second).
	for _, target := range []string{"/webui/notify", "/webui/pair/revoke"} {
		w := webuiFormPost(t, mux, target, nil, url.Values{"title": {"t"}})
		if w.Code != http.StatusFound {
			t.Errorf("anon POST %s: got %d, want 302", target, w.Code)
		}
	}
	// Authed form POSTs without CSRF → 403 (auth passes, CSRF fails).
	mux, _, csrf, _, _, cookie := setupWebUITestMux(t)
	_ = csrf
	for _, target := range []string{"/webui/notify", "/webui/pair/revoke"} {
		w := webuiFormPost(t, mux, target, cookie, url.Values{"title": {"t"}})
		if w.Code != http.StatusForbidden {
			t.Errorf("authed no-CSRF POST %s: got %d, want 403", target, w.Code)
		}
	}
}

func TestWebUIDashboardPage(t *testing.T) {
	mux, db, _, _, reg, cookie := setupWebUITestMux(t)

	if _, _, _, err := EnrollDevice(db, "Dash Phone", "fcm-dash"); err != nil {
		t.Fatal(err)
	}
	writeWidget(t, regDirOf(t, reg), "notes",
		"id = \"notes\"\ntype = \"text\"\ntitle = \"Notes\"\n", "hello dashboard", "")
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}

	w := webuiGet(t, mux, "/", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("dashboard: got %d (%s), want 200", w.Code, w.Body.String())
	}
	page := w.Body.String()
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("content type %q, want html", ct)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("Cache-Control %q, want no-store", cc)
	}
	for _, want := range []string{
		`href="/webui/pair"`, `href="/webui/widgets"`, `href="/webui/notify"`,
		`>Home<`, `>Pairing<`, `>Widgets<`, `>Notify<`,
		`<span class="count">1</span>`, // one device, one widget
	} {
		if !strings.Contains(page, want) {
			t.Errorf("dashboard missing %q", want)
		}
	}
}

// regDirOf returns the registry's backing directory for fixture writes.
func regDirOf(t *testing.T, reg *WidgetRegistry) string {
	t.Helper()
	reg.mu.RLock()
	defer reg.mu.RUnlock()
	return reg.dir
}

var notifyIDRe = regexp.MustCompile(`Notification ID: <code>([^<]+)</code>`)

func TestWebUIWidgetsPageMasksSecrets(t *testing.T) {
	mux, db, _, _, reg, cookie := setupWebUITestMux(t)

	id, _, _, err := EnrollDevice(db, "Vault Phone", "fcm-vault")
	if err != nil {
		t.Fatal(err)
	}
	const secretMarker = "WEBUI-PREVIEW-SECRET-MARKER-4d2c9b"
	dir := regDirOf(t, reg)
	writeWidget(t, dir, "notes",
		"id = \"notes\"\ntype = \"text\"\ntitle = \"Notes\"\n", "TEXT-PREVIEW-MARKER-visible", "")
	writeWidget(t, dir, "vault",
		"id = \"vault\"\ntype = \"secret\"\ntitle = \"Vault\"\nsecret_ref = \""+id+"\"\n",
		"", `{"secret": `+jsonQuote(secretMarker)+`}`)
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}

	w := webuiGet(t, mux, "/webui/widgets", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("widgets: got %d (%s), want 200", w.Code, w.Body.String())
	}
	page := w.Body.String()
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("Cache-Control %q, want no-store", cc)
	}
	if !strings.Contains(page, "TEXT-PREVIEW-MARKER-visible") {
		t.Error("text widget value must render")
	}
	if strings.Contains(page, secretMarker) {
		t.Error("widgets preview leaks secret plaintext")
	}
	if !strings.Contains(strings.ToLower(page), "hidden") {
		t.Error("secret row must note the value is hidden")
	}
	if !strings.Contains(page, id) {
		t.Error("secret row must show the target device")
	}
}

func TestWebUINotifyFormRoundTrip(t *testing.T) {
	mux, db, csrf, sender, _, cookie := setupWebUITestMux(t)

	id, _, _, err := EnrollDevice(db, "Notify Phone", "fcm-notify")
	if err != nil {
		t.Fatal(err)
	}
	tok := webuiCSRFToken(t, csrf, cookie)

	// The form lists the live device as a target.
	w := webuiGet(t, mux, "/webui/notify", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("notify form: got %d, want 200", w.Code)
	}
	if !strings.Contains(w.Body.String(), `value="`+id+`"`) {
		t.Error("notify form must offer the paired device as a target")
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("notify form Cache-Control %q, want no-store", cc)
	}

	// Plaintext round-trip: 202 + confirmation + history row + payload.
	w = webuiFormPost(t, mux, "/webui/notify", cookie, url.Values{
		"csrf_token": {tok},
		"target":     {id},
		"kind":       {"note"},
		"title":      {"Hello form"},
		"body":       {"from the operator console"},
		"priority":   {"high"},
	})
	if w.Code != http.StatusAccepted {
		t.Fatalf("notify post: got %d (%s), want 202", w.Code, w.Body.String())
	}
	m := notifyIDRe.FindStringSubmatch(w.Body.String())
	if len(m) != 2 || m[1] == "" {
		t.Fatalf("success page must show the notification id (%s)", w.Body.String())
	}
	if n, st := e2eHistoryRows(t, db, m[1], id); n != 1 || st[0] != notify.StatusQueued {
		t.Fatalf("history rows = %d %v, want 1 queued", n, st)
	}
	if len(sender.payloads) != 1 || sender.payloads[0]["title"] != "Hello form" {
		t.Fatalf("captured payloads = %v, want plaintext title", sender.payloads)
	}

	// Secret round-trip: envelopes only, never plaintext.
	sender.payloads = nil
	w = webuiFormPost(t, mux, "/webui/notify", cookie, url.Values{
		"csrf_token": {tok},
		"target":     {id},
		"kind":       {"note"},
		"title":      {"Form secret title"},
		"body":       {"Form secret body"},
		"priority":   {"default"},
		"secret":     {"true"},
	})
	if w.Code != http.StatusAccepted {
		t.Fatalf("secret notify post: got %d (%s), want 202", w.Code, w.Body.String())
	}
	if len(sender.payloads) != 1 {
		t.Fatalf("want 1 captured payload, got %d", len(sender.payloads))
	}
	if sender.payloads[0][notify.SecretFlagKey] != "true" {
		t.Fatalf("missing secret flag: %v", sender.payloads[0])
	}
	if _, ok := sender.payloads[0]["title"]; ok {
		t.Fatal("secret form post carries plaintext title")
	}

	// Validation failure: 422 + operator copy, echoed form, nothing sent.
	sender.payloads = nil
	w = webuiFormPost(t, mux, "/webui/notify", cookie, url.Values{
		"csrf_token": {tok},
		"target":     {"all"},
		"kind":       {"note"},
		"title":      {""},
		"body":       {"b"},
		"priority":   {"default"},
	})
	if w.Code != http.StatusUnprocessableEntity {
		t.Errorf("empty title: got %d, want 422 (%s)", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "Title must be non-empty") {
		t.Error("validation page must explain the failure")
	}
	if len(sender.payloads) != 0 {
		t.Errorf("failed validation sent %d payloads, want 0", len(sender.payloads))
	}
}
