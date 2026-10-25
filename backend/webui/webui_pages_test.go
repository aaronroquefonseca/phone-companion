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
	// renameFound/renameErr script RenameDevice; tokenStatus/tokenStatusErr
	// script TokenStatus.
	renameFound    bool
	renameErr      error
	tokenStatus    TokenStatus
	tokenStatusErr error
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

func (f *fakeStore) PurgeDevice(_ context.Context, _ string) (bool, error) {
	return true, nil
}

func (f *fakeStore) RenameDevice(_ context.Context, _, _ string) (bool, error) {
	if f.renameErr != nil {
		return false, f.renameErr
	}
	return f.renameFound, nil
}

func (f *fakeStore) TokenStatus(_ context.Context, _ string) (TokenStatus, error) {
	if f.tokenStatusErr != nil {
		return TokenStatus{}, f.tokenStatusErr
	}
	return f.tokenStatus, nil
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
	// calls counts SendNotify invocations (pins PRG no-resubmit: the
	// confirmation GET must not call the service again).
	calls int
}

func (f *fakeNotifyService) SendNotify(_ context.Context, req NotifyRequest) (NotifyResult, error) {
	f.calls++
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
	// Unknown authed paths render inside the shared shell (nav + onward
	// link), never a bare http.NotFound.
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("non-/ path content type %q, want html", ct)
	}
	if body := w.Body.String(); !strings.Contains(body, "/webui/pair") || !strings.Contains(body, "Back to dashboard") {
		t.Errorf("non-/ 404 page must keep nav and an onward link: %s", body)
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
	emptyPage := w.Body.String()
	if !strings.Contains(emptyPage, "No widgets configured") {
		t.Error("empty registry must render the empty state")
	}
	// Empty state links onward: docs pointer ($WIDGET_DIR) + dashboard link.
	if !strings.Contains(emptyPage, "$WIDGET_DIR") || !strings.Contains(emptyPage, "backend/README.md") {
		t.Error("widgets empty state must point at the $WIDGET_DIR docs")
	}
	if !strings.Contains(emptyPage, `href="/"`) {
		t.Error("widgets empty state must link onward")
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

	// Success is PRG: 303 to /webui/notify?sent=<id>&accepted=<n>. The GET
	// then renders the confirmation without fanning out again.
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
	if w.Code != http.StatusSeeOther {
		t.Fatalf("got %d, want 303 (%s)", w.Code, w.Body.String())
	}
	loc := w.Header().Get("Location")
	if !strings.HasPrefix(loc, "/webui/notify?sent=nid-7&accepted=1") {
		t.Fatalf("PRG location %q, want sent=nid-7&accepted=1", loc)
	}
	gw := httptest.NewRecorder()
	HandleNotifyGet(store, csrf)(gw, authedPageReq("GET", loc, ""))
	if gw.Code != http.StatusOK {
		t.Fatalf("confirmation GET: got %d, want 200", gw.Code)
	}
	if body := gw.Body.String(); !strings.Contains(body, "nid-7") || !strings.Contains(body, `class="success"`) {
		t.Errorf("confirmation page must celebrate with the id in green: %s", body)
	}
	if svc.calls != 1 {
		t.Errorf("PRG second GET must not fan out again (calls=%d)", svc.calls)
	}
	if svc.got.DeviceID != "dev-1" || svc.got.All || svc.got.Title != "Hello" || svc.got.Priority != "high" {
		t.Errorf("service got mangled request: %+v", svc.got)
	}

	// accepted:0 renders neutral guidance — never a green celebration.
	svcZero := &fakeNotifyService{res: NotifyResult{NotificationID: "nid-0", Accepted: 0}}
	form.Set("target", "all")
	w = httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svcZero)(w, notifyPostReq(cookie, form))
	if w.Code != http.StatusSeeOther {
		t.Fatalf("zero: got %d, want 303", w.Code)
	}
	zw := httptest.NewRecorder()
	HandleNotifyGet(store, csrf)(zw, authedPageReq("GET", w.Header().Get("Location"), ""))
	zbody := zw.Body.String()
	if strings.Contains(zbody, `class="success"`) {
		t.Error("accepted:0 must NOT celebrate in green")
	}
	if !strings.Contains(zbody, "pair a device first") {
		t.Error("accepted:0 must point at pairing")
	}

	// Forged PRG params render the plain form, never a confirmation.
	for _, forged := range []string{
		"/webui/notify?sent=../../etc&accepted=1",
		"/webui/notify?sent=nid-7&accepted=many",
		"/webui/notify?accepted=1",
	} {
		fw := httptest.NewRecorder()
		HandleNotifyGet(store, csrf)(fw, authedPageReq("GET", forged, ""))
		if fw.Code != http.StatusOK {
			t.Errorf("forged %s: got %d, want 200", forged, fw.Code)
		}
		if fbody := fw.Body.String(); strings.Contains(fbody, "Notification sent") {
			t.Errorf("forged %s must not render a confirmation", forged)
		}
	}

	// Secret checkbox maps through.
	svc2 := &fakeNotifyService{res: NotifyResult{NotificationID: "n", Accepted: 1}}
	form.Set("csrf_token", tok)
	form.Set("target", "dev-1")
	form.Set("secret", "true")
	w = httptest.NewRecorder()
	HandleNotifyPost(store, csrf, svc2)(w, notifyPostReq(cookie, form))
	if w.Code != http.StatusSeeOther {
		t.Fatalf("secret: got %d, want 303", w.Code)
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

	// Anonymous: sign-in form, no operator nav. The anonymous brand points
	// at /login (not a looping / that would bounce straight back).
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
	if !strings.Contains(page, `class="brand"><a href="/login"`) {
		t.Error("anonymous brand link must point at /login, not loop on /")
	}
	if !strings.Contains(page, "autofocus") {
		t.Error("username field must autofocus")
	}

	// Signed-in: redirect to the dashboard (no second signed-in page here).
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}
	req := httptest.NewRequest("GET", "/login", nil)
	req.AddCookie(cookie)
	// Flip the memStore to valid via the handler's store.
	if ms, ok := h.store.(*memStore); ok {
		ms.valid = true
	}
	w = httptest.NewRecorder()
	h.handleLoginGet(w, req)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("signed-in login page: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/" {
		t.Errorf("signed-in login redirect to %q, want /", loc)
	}

	// Signed-out flash: the logout redirect lands here with ?signed_out=1.
	signedOut := httptest.NewRecorder()
	h.store.(*memStore).valid = false
	h.handleLoginGet(signedOut, httptest.NewRequest("GET", "/login?signed_out=1", nil))
	if !strings.Contains(signedOut.Body.String(), "Signed out.") {
		t.Error("?signed_out=1 must render the signed-out flash")
	}
	// Forged values render no notice.
	forged := httptest.NewRecorder()
	h.handleLoginGet(forged, httptest.NewRequest("GET", "/login?signed_out=yes-please", nil))
	if strings.Contains(forged.Body.String(), "Signed out.") {
		t.Error("non-whitelisted signed_out value must render no flash")
	}
}

func TestPairRestyleKeepsBehavior(t *testing.T) {
	store := &fakeStore{
		rawToken: "raw-token-xyz",
		devices:  []DeviceInfo{{ID: "dev-1", Name: "Pixel", CreatedAt: "2026-01-01"}},
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	h := HandlePairPage(store, time.Minute, csrf)

	// GET never mints: empty state with a generate-token button, registry
	// listed, error notice still honored — no QR bytes on a plain load.
	req := authedPageReq("GET", "/webui/pair?error=device_not_found", "")
	w := httptest.NewRecorder()
	h(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	page := w.Body.String()
	if strings.Contains(page, "data:image/png;base64,") || strings.Contains(page, "raw-token-xyz") {
		t.Error("GET /webui/pair must not mint a token (no QR on plain loads)")
	}
	for _, want := range []string{`action="/webui/pair/mint"`, "Generate pairing token", "Pixel", "Device not found"} {
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

	// POST /webui/pair/mint renders the QR with a human countdown (no raw
	// UTC in body text), copy control, and refresh action.
	tok := csrfFieldToken(t, page)
	form := url.Values{"csrf_token": {tok}}.Encode()
	mintReq := authedPageReq("POST", "/webui/pair/mint", form)
	mintReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	mw := httptest.NewRecorder()
	HandlePairMint(store, time.Minute, csrf)(mw, mintReq)
	if mw.Code != http.StatusOK {
		t.Fatalf("mint: got %d, want 200 (%s)", mw.Code, mw.Body.String())
	}
	minted := mw.Body.String()
	for _, want := range []string{"data:image/png;base64,", "raw-token-xyz", "expires in ", "Copy token", "Issue a fresh token", "csrf-token"} {
		if !strings.Contains(minted, want) {
			t.Errorf("minted pair page missing %q", want)
		}
	}
}

// The minted QR panel carries a human countdown ("expires in N min") with
// the exact timestamp confined to the title tooltip, an EXTERNAL_BASE_URL
// fallback warning, and no raw UTC in body text.
func TestPairMintCountdownAndFallbackWarning(t *testing.T) {
	oldBase := ExternalBaseURL
	ExternalBaseURL = ""
	t.Cleanup(func() { ExternalBaseURL = oldBase })
	store := &fakeStore{rawToken: "raw-countdown-tok"}
	csrf := NewCSRFProtection(testCSRFSecret)
	get := authedPageReq("GET", "/webui/pair", "")
	getw := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(getw, get)
	tok := csrfFieldToken(t, getw.Body.String())

	form := url.Values{"csrf_token": {tok}}.Encode()
	mintReq := authedPageReq("POST", "/webui/pair/mint", form)
	mintReq.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	mw := httptest.NewRecorder()
	HandlePairMint(store, time.Minute, csrf)(mw, mintReq)
	if mw.Code != http.StatusOK {
		t.Fatalf("mint: got %d, want 200", mw.Code)
	}
	page := mw.Body.String()
	if !strings.Contains(page, "expires in ") {
		t.Error("minted page must carry a human countdown, not raw UTC")
	}
	if !strings.Contains(page, `title="Expires at `) {
		t.Error("exact expiry must survive as a title tooltip")
	}
	if !strings.Contains(page, "EXTERNAL_BASE_URL is not set") {
		t.Error("fallback origin must warn in-page when EXTERNAL_BASE_URL is unset")
	}
	// Auto-refresh fetch path: JSON with the same CSRF via header.
	jreq := authedPageReq("POST", "/webui/pair/mint", "")
	jreq.Header.Set("Accept", "application/json")
	jreq.Header.Set("X-CSRF-Token", tok)
	jw := httptest.NewRecorder()
	HandlePairMint(store, time.Minute, csrf)(jw, jreq)
	if jw.Code != http.StatusOK {
		t.Fatalf("mint JSON: got %d, want 200", jw.Code)
	}
	if ct := jw.Header().Get("Content-Type"); !strings.Contains(ct, "application/json") {
		t.Errorf("mint JSON content type %q, want json", ct)
	}
	if body := jw.Body.String(); !strings.Contains(body, "raw-countdown-tok") || !strings.Contains(body, "qr_data_uri") {
		t.Errorf("mint JSON must carry token + QR: %s", body)
	}
}

// Minting requires the session CSRF token; failures render in the shell.
func TestPairMintRequiresCSRF(t *testing.T) {
	store := &fakeStore{rawToken: "x"}
	csrf := NewCSRFProtection(testCSRFSecret)
	w := httptest.NewRecorder()
	HandlePairMint(store, time.Minute, csrf)(w, authedPageReq("POST", "/webui/pair/mint", ""))
	if w.Code != http.StatusForbidden {
		t.Errorf("mint without CSRF: got %d, want 403", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("mint CSRF failure content type %q, want html", ct)
	}
}

// Revoke is two-step: first POST renders a confirm interstitial naming the
// device; the confirming POST revokes and flashes ?revoked=<name>.
func TestPairRevokeConfirmFlow(t *testing.T) {
	store := &fakeStore{
		rawToken: "x",
		devices:  []DeviceInfo{{ID: "dev-9", Name: "Old Phone", CreatedAt: "2026-01-01"}},
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	tok, ok := csrf.TokenForRequest(cookieRequest(&http.Cookie{Name: "pc_session", Value: "x"}))
	if !ok {
		t.Fatal("no CSRF token minted")
	}
	post := func(form url.Values) *httptest.ResponseRecorder {
		req := authedPageReq("POST", "/webui/pair/revoke", form.Encode())
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		w := httptest.NewRecorder()
		HandlePairRevoke(store, csrf)(w, req)
		return w
	}
	// Unknown ID skips the interstitial: straight to ?error=device_not_found.
	w := post(url.Values{"device_id": {"nope"}, "csrf_token": {tok}})
	if w.Code != http.StatusSeeOther {
		t.Fatalf("unknown revoke: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/webui/pair?error=device_not_found" {
		t.Fatalf("unknown revoke redirect to %q", loc)
	}
	// Known ID, no confirm: interstitial names the device.
	w = post(url.Values{"device_id": {"dev-9"}, "csrf_token": {tok}})
	if w.Code != http.StatusOK {
		t.Fatalf("confirm interstitial: got %d, want 200", w.Code)
	}
	if body := w.Body.String(); !strings.Contains(body, "Confirm revoke") || !strings.Contains(body, "Old Phone") {
		t.Errorf("interstitial must name the device: %s", body)
	}
	// Confirming POST: 303 with the success flash.
	w = post(url.Values{"device_id": {"dev-9"}, "csrf_token": {tok}, "confirm": {"yes"}})
	if w.Code != http.StatusSeeOther {
		t.Fatalf("confirmed revoke: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); !strings.HasPrefix(loc, "/webui/pair?revoked=") {
		t.Fatalf("confirmed revoke redirect to %q, want ?revoked=", loc)
	}
	// The flash renders as a success notice with the device name.
	gw := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(gw, authedPageReq("GET", w.Header().Get("Location"), ""))
	if body := gw.Body.String(); !strings.Contains(body, `class="success"`) || !strings.Contains(body, "Old Phone") {
		t.Errorf("revoked flash must succeed visibly: %s", body)
	}
}

// Purge is two-step and revoked-only: active rows are refused, revoked rows
// confirm then hard-delete with a success flash.
func TestPairPurgeFlow(t *testing.T) {
	store := &fakeStore{
		rawToken: "x",
		devices: []DeviceInfo{
			{ID: "live-1", Name: "Live", CreatedAt: "2026-01-01"},
			{ID: "gone-1", Name: "Gone", CreatedAt: "2026-01-01", Revoked: true},
		},
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	tok, ok := csrf.TokenForRequest(cookieRequest(&http.Cookie{Name: "pc_session", Value: "x"}))
	if !ok {
		t.Fatal("no CSRF token minted")
	}
	post := func(form url.Values) *httptest.ResponseRecorder {
		req := authedPageReq("POST", "/webui/pair/purge", form.Encode())
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		w := httptest.NewRecorder()
		HandlePairPurge(store, csrf)(w, req)
		return w
	}
	// Active rows cannot be purged.
	w := post(url.Values{"device_id": {"live-1"}, "csrf_token": {tok}, "confirm": {"yes"}})
	if w.Code != http.StatusSeeOther {
		t.Fatalf("active purge: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/webui/pair?error=not_revoked" {
		t.Fatalf("active purge redirect to %q, want ?error=not_revoked", loc)
	}
	// Revoked row: interstitial first, then purge with flash.
	w = post(url.Values{"device_id": {"gone-1"}, "csrf_token": {tok}})
	if w.Code != http.StatusOK {
		t.Fatalf("purge interstitial: got %d, want 200", w.Code)
	}
	if body := w.Body.String(); !strings.Contains(body, "Confirm purge") || !strings.Contains(body, "Gone") {
		t.Errorf("purge interstitial must name the device: %s", body)
	}
	w = post(url.Values{"device_id": {"gone-1"}, "csrf_token": {tok}, "confirm": {"yes"}})
	if w.Code != http.StatusSeeOther {
		t.Fatalf("confirmed purge: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); !strings.HasPrefix(loc, "/webui/pair?show=revoked&purged=") {
		t.Fatalf("confirmed purge redirect to %q", loc)
	}
}

// The default pair view hides revoked rows behind a counted toggle; revoked
// rows render dimmed when shown.
func TestPairRevokedToggleDefaultHide(t *testing.T) {
	store := &fakeStore{
		rawToken: "x",
		devices: []DeviceInfo{
			{ID: "live-1", Name: "Live", CreatedAt: "2026-01-01"},
			{ID: "gone-1", Name: "Gone", CreatedAt: "2026-01-01", Revoked: true, RevokedAt: "2026-01-02"},
		},
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	w := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(w, authedPageReq("GET", "/webui/pair", ""))
	page := w.Body.String()
	if !strings.Contains(page, "Live") {
		t.Error("default view must show active devices")
	}
	if strings.Contains(page, "gone-1") {
		t.Error("default view must hide revoked rows")
	}
	if !strings.Contains(page, "Show revoked (1)") {
		t.Error("default view must offer the counted revoked toggle")
	}
	ws := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(ws, authedPageReq("GET", "/webui/pair?show=revoked", ""))
	shown := ws.Body.String()
	if !strings.Contains(shown, "gone-1") || !strings.Contains(shown, `class="revoked"`) {
		t.Error("show=revoked must render dimmed revoked rows")
	}
	if !strings.Contains(shown, "Purge") {
		t.Error("revoked rows must offer the purge action")
	}
}

// First-run dashboard orders pairing before notify.
func TestDashboardFirstRunPairFirst(t *testing.T) {
	csrf := NewCSRFProtection(testCSRFSecret)
	w := httptest.NewRecorder()
	HandleDashboard(&fakeStore{}, &fakeLister{}, csrf)(w, authedPageReq("GET", "/", ""))
	page := w.Body.String()
	if !strings.Contains(page, "Pair your first phone") {
		t.Error("zero-device dashboard must order pairing first")
	}
	if !strings.Contains(page, "Manage devices") || !strings.Contains(page, "Pair a new device") {
		t.Error("pair-link cards must stay distinct (registry vs fresh token)")
	}
}

// Skipped (invalid) widget directories surface as a counted, name-only
// warning linking to logs/docs — never payload bytes.
func TestWidgetsSkippedWarning(t *testing.T) {
	lister := &skippedFakeLister{
		fakeLister: &fakeLister{views: []WidgetView{
			{ID: "notes", Type: "text", Title: "Notes", UpdatedAt: "2026-01-01T00:00:00Z", Payload: []byte(`{"body":"hi"}`)},
		}},
		skipped: []string{"broken-dir"},
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	w := httptest.NewRecorder()
	HandleWidgetsPage(lister, csrf)(w, authedPageReq("GET", "/webui/widgets", ""))
	page := w.Body.String()
	if !strings.Contains(page, "broken-dir") || !strings.Contains(page, "backend/README.md") {
		t.Errorf("skipped widgets must warn with names + docs pointer: %s", page)
	}
}

type skippedFakeLister struct {
	*fakeLister
	skipped []string
}

func (s *skippedFakeLister) ListSkippedWidgets(_ context.Context) []string {
	return s.skipped
}

// GET /logout explains the POST-only sign-out inside the shell (405).
func TestLogoutMethodNotAllowed(t *testing.T) {
	h, _ := testHandler()
	w := httptest.NewRecorder()
	h.handleLogoutMethodNotAllowed(w, authedPageReq("GET", "/logout", ""))
	if w.Code != http.StatusMethodNotAllowed {
		t.Errorf("got %d, want 405", w.Code)
	}
	if body := w.Body.String(); !strings.Contains(body, "POST /logout") || !strings.Contains(body, "Back to dashboard") {
		t.Errorf("405 page must explain + link onward: %s", body)
	}
}
