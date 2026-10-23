package webui

// Operator pages tests: dashboard, widgets preview, notify form (render +
// CSRF + service mapping), and the login/pairing restyle invariants.

import (
	"context"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// fakeStore is a scriptable Store for page tests.
type fakeStore struct {
	devices  []DeviceInfo
	listErr  error
	rawToken string
	issueErr error
}

func (f *fakeStore) IssueEnrollToken(_ context.Context, _ time.Duration) (string, time.Time, error) {
	if f.issueErr != nil {
		return "", time.Time{}, f.issueErr
	}
	return f.rawToken, time.Now().Add(time.Minute), nil
}

func (f *fakeStore) ListDevices(_ context.Context) ([]DeviceInfo, error) {
	return f.devices, f.listErr
}

func (f *fakeStore) RevokeDevice(_ context.Context, _ string) (bool, error) {
	return true, nil
}

type fakeLister struct {
	views []WidgetView
	err   error
}

func (f *fakeLister) ListWidgets(_ context.Context) ([]WidgetView, error) {
	return f.views, f.err
}

type fakeNotifyService struct {
	res NotifyResult
	err error
	got NotifyRequest
}

func (f *fakeNotifyService) SendNotify(_ context.Context, req NotifyRequest) (NotifyResult, error) {
	f.got = req
	return f.res, f.err
}

// authedPageReq builds a request carrying a session cookie so the CSRF
// protector mints tokens for the nav/logout forms.
func authedPageReq(method, target string, body string) *http.Request {
	var reader *strings.Reader
	if body == "" {
		reader = strings.NewReader("")
	} else {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, target, reader)
	req.AddCookie(&http.Cookie{Name: "pc_session", Value: "x"})
	return req
}

func csrfFieldToken(t *testing.T, page string) string {
	t.Helper()
	const marker = `name="csrf_token" value="`
	i := strings.Index(page, marker)
	if i < 0 {
		t.Fatalf("page has no csrf_token field:\n%s", page)
	}
	rest := page[i+len(marker):]
	j := strings.Index(rest, `"`)
	if j < 0 {
		t.Fatal("unterminated csrf_token value")
	}
	tok := rest[:j]
	if tok == "" {
		t.Fatal("empty CSRF token in form")
	}
	return tok
}

func TestDashboardRendersCountsAndLinks(t *testing.T) {
	store := &fakeStore{devices: []DeviceInfo{{ID: "d1", Name: "Phone"}, {ID: "d2", Name: "Tablet"}}}
	lister := &fakeLister{views: []WidgetView{{ID: "a"}, {ID: "b"}, {ID: "c"}}}
	csrf := NewCSRFProtection(testCSRFSecret)

	w := httptest.NewRecorder()
	HandleDashboard(store, lister, csrf)(w, authedPageReq("GET", "/", ""))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("content type %q, want html", ct)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("Cache-Control %q, want no-store", cc)
	}
	page := w.Body.String()
	if !strings.Contains(page, `<span class="count">2</span>`) {
		t.Error("dashboard must show the device count")
	}
	if !strings.Contains(page, `<span class="count">3</span>`) {
		t.Error("dashboard must show the widget count")
	}
	for _, href := range []string{`href="/webui/pair"`, `href="/webui/widgets"`, `href="/webui/notify"`, `href="/" aria-current="page"`} {
		if !strings.Contains(page, href) {
			t.Errorf("dashboard missing link %s", href)
		}
	}
	for _, nav := range []string{">Home<", ">Pairing<", ">Widgets<", ">Notify<"} {
		if !strings.Contains(page, nav) {
			t.Errorf("dashboard nav missing %s", nav)
		}
	}
	csrfFieldToken(t, page)
}

func TestDashboardRejectsNonRootAndNilDeps(t *testing.T) {
	csrf := NewCSRFProtection(testCSRFSecret)
	store := &fakeStore{}
	lister := &fakeLister{}

	w := httptest.NewRecorder()
	HandleDashboard(store, lister, csrf)(w, authedPageReq("GET", "/nope", ""))
	if w.Code != http.StatusNotFound {
		t.Errorf("non-/ path: got %d, want 404", w.Code)
	}

	for name, h := range map[string]http.HandlerFunc{
		"nil store":  HandleDashboard(nil, lister, csrf),
		"nil lister": HandleDashboard(store, nil, csrf),
	} {
		w := httptest.NewRecorder()
		h(w, authedPageReq("GET", "/", ""))
		if w.Code != http.StatusInternalServerError {
			t.Errorf("%s: got %d, want 500", name, w.Code)
		}
		if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
			t.Errorf("%s: content type %q, want html", name, ct)
		}
	}
}

func TestWidgetsPageRendersAndMasksSecrets(t *testing.T) {
	const secretMarker = "SECRET-PLAINTEXT-MARKER-9f2c7a-do-not-render"
	lister := &fakeLister{views: []WidgetView{
		{ID: "notes", Type: "text", Title: "Notes", UpdatedAt: "2026-01-01T00:00:00Z", Payload: []byte(`{"body":"TEXT-BODY-MARKER-hello"}`)},
		{ID: "todos", Type: "list", Title: "Todos", UpdatedAt: "2026-01-01T00:00:00Z", Payload: []byte(`{"items":[{"id":"1","text":"LIST-ITEM-MARKER-buy milk","done":true}]}`)},
		{ID: "health", Type: "status", Title: "Health", UpdatedAt: "2026-01-01T00:00:00Z", Payload: []byte(`{"state":"ok","detail":"STATUS-DETAIL-MARKER-all good"}`)},
		// Even if secret payload bytes were reachable, the renderer must
		// never emit them.
		{ID: "vault", Type: "secret", Title: "Vault", UpdatedAt: "2026-01-01T00:00:00Z", Payload: []byte(`{"secret":"` + secretMarker + `"}`), SecretRef: "dev-1"},
	}}
	csrf := NewCSRFProtection(testCSRFSecret)

	w := httptest.NewRecorder()
	HandleWidgetsPage(lister, csrf)(w, authedPageReq("GET", "/webui/widgets", ""))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("widgets page Cache-Control %q, want no-store", cc)
	}
	page := w.Body.String()
	for _, want := range []string{"TEXT-BODY-MARKER-hello", "LIST-ITEM-MARKER-buy milk", "STATUS-DETAIL-MARKER-all good", "Notes", "2026-01-01T00:00:00Z", "dev-1"} {
		if !strings.Contains(page, want) {
			t.Errorf("widgets preview missing %q", want)
		}
	}
	if strings.Contains(page, secretMarker) {
		t.Error("widgets preview leaks secret plaintext")
	}
	if !strings.Contains(strings.ToLower(page), "hidden") {
		t.Error("secret row must explain the value is hidden")
	}

	// Empty registry: friendly empty state, still 200.
	w = httptest.NewRecorder()
	HandleWidgetsPage(&fakeLister{}, csrf)(w, authedPageReq("GET", "/webui/widgets", ""))
	if w.Code != http.StatusOK {
		t.Fatalf("empty: got %d, want 200", w.Code)
	}
	if !strings.Contains(w.Body.String(), "No widgets configured") {
		t.Error("empty registry must render the empty state")
	}

	// Nil lister: HTML 500, never a bare error.
	w = httptest.NewRecorder()
	HandleWidgetsPage(nil, csrf)(w, authedPageReq("GET", "/webui/widgets", ""))
	if w.Code != http.StatusInternalServerError {
		t.Errorf("nil lister: got %d, want 500", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("nil lister content type %q, want html", ct)
	}
}

func TestNotifyGetRendersForm(t *testing.T) {
	store := &fakeStore{devices: []DeviceInfo{
		{ID: "dev-live", Name: "Pixel"},
		{ID: "dev-gone", Name: "Old", Revoked: true},
	}}
	csrf := NewCSRFProtection(testCSRFSecret)

	w := httptest.NewRecorder()
	HandleNotifyGet(store, csrf)(w, authedPageReq("GET", "/webui/notify", ""))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	page := w.Body.String()
	if !strings.Contains(page, `value="dev-live"`) || !strings.Contains(page, "Pixel") {
		t.Error("form must list the active device in the target dropdown")
	}
	if strings.Contains(page, "dev-gone") {
		t.Error("revoked devices must not appear in the target dropdown")
	}
	for _, want := range []string{`name="kind"`, `name="title"`, `name="body"`, `name="priority"`, `name="secret"`, `action="/webui/notify"`} {
		if !strings.Contains(page, want) {
			t.Errorf("notify form missing %s", want)
		}
	}
	csrfFieldToken(t, page)
}

func notifyPostReq(cookie *http.Cookie, form url.Values) *http.Request {
	req := httptest.NewRequest("POST", "/webui/notify", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if cookie != nil {
		req.AddCookie(cookie)
	}
	return req
}

func TestNotifyPostRequiresCSRF(t *testing.T) {
	store := &fakeStore{}
	csrf := NewCSRFProtection(testCSRFSecret)
	svc := &fakeNotifyService{res: NotifyResult{NotificationID: "n", Accepted: 1}}
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}

	// No token → 403, service untouched.
	w := httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc)(w, notifyPostReq(cookie, url.Values{"title": {"t"}, "body": {"b"}}))
	if w.Code != http.StatusForbidden {
		t.Errorf("no CSRF: got %d, want 403", w.Code)
	}

	// Wrong token → 403.
	w = httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc)(w, notifyPostReq(cookie, url.Values{"csrf_token": {"wrong"}, "title": {"t"}, "body": {"b"}}))
	if w.Code != http.StatusForbidden {
		t.Errorf("bad CSRF: got %d, want 403", w.Code)
	}
}

func TestNotifyPostSuccessAndValidation(t *testing.T) {
	store := &fakeStore{devices: []DeviceInfo{{ID: "dev-1", Name: "Pixel"}}}
	csrf := NewCSRFProtection(testCSRFSecret)
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}
	tok, ok := csrf.TokenForRequest(cookieRequest(cookie))
	if !ok {
		t.Fatal("no CSRF token minted")
	}

	svc := &fakeNotifyService{res: NotifyResult{NotificationID: "nid-7", Accepted: 1}}
	form := url.Values{
		"csrf_token": {tok},
		"target":     {"dev-1"},
		"kind":       {"note"},
		"title":      {"Hello"},
		"body":       {"World"},
		"priority":   {"high"},
	}
	w := httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc)(w, notifyPostReq(cookie, form))
	if w.Code != http.StatusAccepted {
		t.Fatalf("got %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "nid-7") {
		t.Error("success page must show the notification id")
	}
	if svc.got.DeviceID != "dev-1" || svc.got.All || svc.got.Title != "Hello" || svc.got.Priority != "high" {
		t.Errorf("service got mangled request: %+v", svc.got)
	}

	// Secret checkbox maps through.
	svc2 := &fakeNotifyService{res: NotifyResult{NotificationID: "n", Accepted: 1}}
	form.Set("csrf_token", tok)
	form.Set("secret", "true")
	w = httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc2)(w, notifyPostReq(cookie, form))
	if w.Code != http.StatusAccepted {
		t.Fatalf("secret: got %d, want 202", w.Code)
	}
	if !svc2.got.Secret {
		t.Error("secret checkbox must map onto the request")
	}

	// Validation errors re-render the form with the pipeline status.
	svc3 := &fakeNotifyService{err: &NotifyError{Code: "missing_title", Status: 422}}
	form.Del("secret")
	form.Set("title", "")
	w = httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc3)(w, notifyPostReq(cookie, form))
	if w.Code != http.StatusUnprocessableEntity {
		t.Errorf("validation: got %d, want 422 (%s)", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), "Title must be non-empty") {
		t.Error("validation page must explain the failure")
	}

	// Driver failures surface as 500 with operator copy, never raw errors.
	svc4 := &fakeNotifyService{err: &NotifyError{Code: "driver_unavailable", Status: 500}}
	w = httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc4)(w, notifyPostReq(cookie, form))
	if w.Code != http.StatusInternalServerError {
		t.Errorf("driver: got %d, want 500", w.Code)
	}
	if !strings.Contains(w.Body.String(), "driver") {
		t.Error("driver failure page must say the driver is unavailable")
	}
}

func TestLoginRestyleKeepsBehavior(t *testing.T) {
	h, _ := testHandler()

	// Anonymous: sign-in form, no operator nav.
	w := httptest.NewRecorder()
	h.handleLoginGet(w, httptest.NewRequest("GET", "/login", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200", w.Code)
	}
	page := w.Body.String()
	if !strings.Contains(page, "<form") || !strings.Contains(page, `action="/login"`) {
		t.Error("login page must render the sign-in form")
	}
	if strings.Contains(page, "/webui/pair") {
		t.Error("anonymous login page must not render operator nav")
	}

	// Signed-in: operator nav + CSRF-carrying logout form.
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}
	req := httptest.NewRequest("GET", "/login", nil)
	req.AddCookie(cookie)
	// Flip the memStore to valid via the handler's store.
	if ms, ok := h.store.(*memStore); ok {
		ms.valid = true
	}
	w = httptest.NewRecorder()
	h.handleLoginGet(w, req)
	page = w.Body.String()
	for _, want := range []string{`href="/webui/widgets"`, `href="/webui/notify"`, "Signed in as"} {
		if !strings.Contains(page, want) {
			t.Errorf("signed-in login page missing %q", want)
		}
	}
	csrfFieldToken(t, page)
}

func TestPairRestyleKeepsBehavior(t *testing.T) {
	store := &fakeStore{
		rawToken: "raw-token-xyz",
		devices:  []DeviceInfo{{ID: "dev-1", Name: "Pixel", CreatedAt: "2026-01-01"}},
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	h := HandlePairPage(store, time.Minute, csrf)

	req := authedPageReq("GET", "/webui/pair?error=device_not_found", "")
	w := httptest.NewRecorder()
	h(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	page := w.Body.String()
	for _, want := range []string{"data:image/png;base64,", "raw-token-xyz", "Pixel", "Device not found"} {
		if !strings.Contains(page, want) {
			t.Errorf("pair page missing %q", want)
		}
	}
	csrfFieldToken(t, page)
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("content type %q, want html", ct)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("Cache-Control %q, want no-store", cc)
	}
}
