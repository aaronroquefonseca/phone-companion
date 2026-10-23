package webui

import (
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/crypto/bcrypt"
)

type memStore struct {
	valid bool
}

func (m *memStore) CreateSession(w http.ResponseWriter, _ *http.Request) error {
	m.valid = true
	http.SetCookie(w, &http.Cookie{Name: "pc_session", Value: "x"})
	return nil
}

func (m *memStore) ValidateSession(_ *http.Request) bool { return m.valid }

func (m *memStore) DestroySession(w http.ResponseWriter, _ *http.Request) {
	m.valid = false
	http.SetCookie(w, &http.Cookie{Name: "pc_session", MaxAge: -1})
}

func testHandler() (*handler, *memStore) {
	hash, _ := bcrypt.GenerateFromPassword([]byte("correct-horse-pw"), bcrypt.MinCost)
	store := &memStore{}
	h := &handler{store: store, creds: Credentials{
		Username:     "admin",
		PasswordHash: string(hash),
	}, csrf: NewCSRFProtection(testCSRFSecret)}
	return h, store
}

// testCSRFSecret signs test session cookies for CSRF token minting. It must
// stay in sync with testHandler's protector.
const testCSRFSecret = "test-csrf-secret-0123456789abcdef"

// loginReq builds a POST /login form request through the shared login
// limiter (callers reset it first for determinism).
func loginReq(user, pass string) *http.Request {
	form := url.Values{"username": {user}, "password": {pass}}.Encode()
	req := httptest.NewRequest("POST", "/login", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	return req
}

func postLogin(h *handler, user, pass string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	h.handleLoginPost(w, loginReq(user, pass))
	return w
}

func TestLoginGetRendersForm(t *testing.T) {
	resetLoginLimiter()
	h, _ := testHandler()
	w := httptest.NewRecorder()
	h.handleLoginGet(w, httptest.NewRequest("GET", "/login", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("content type %q, want html", ct)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("login page Cache-Control %q, want no-store", cc)
	}
}

func TestLoginSuccessAndFailure(t *testing.T) {
	resetLoginLimiter()
	h, store := testHandler()

	ok := postLogin(h, "admin", "correct-horse-pw")
	if ok.Code != http.StatusSeeOther {
		t.Errorf("success: got %d, want 303", ok.Code)
	}
	if loc := ok.Header().Get("Location"); loc != "/" {
		t.Errorf("success: redirect to %q, want /", loc)
	}
	if c := ok.Result().Cookies(); len(c) == 0 {
		t.Error("success: expected Set-Cookie")
	}
	if cc := ok.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("login response Cache-Control %q, want no-store", cc)
	}

	// POST-while-authed redirects too (no credential check, no bcrypt).
	already := postLogin(h, "admin", "wrong-password")
	if already.Code != http.StatusSeeOther {
		t.Errorf("authed post: got %d, want 303", already.Code)
	}
	if loc := already.Header().Get("Location"); loc != "/" {
		t.Errorf("authed post: redirect to %q, want /", loc)
	}

	for name, tc := range map[string][2]string{
		"wrong password": {"admin", "nope"},
		"wrong user":     {"root", "correct-horse-pw"},
		"empty":          {"", ""},
	} {
		store.valid = false
		w := postLogin(h, tc[0], tc[1])
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s: got %d, want 401", name, w.Code)
		}
		body := w.Body.String()
		if !strings.Contains(body, "Invalid username or password") {
			t.Errorf("%s: expected generic error page", name)
		}
		// Username echoes back so a typo is visible (password never does).
		if tc[0] != "" && !strings.Contains(body, `value="`+tc[0]+`"`) {
			t.Errorf("%s: expected echoed username in form", name)
		}
		if !strings.Contains(body, "autofocus") {
			t.Errorf("%s: expected autofocus on username", name)
		}
	}
}

// Oversize fields are rejected with 400 BEFORE bcrypt runs (bcrypt only
// hashes the first 72 bytes, so longer inputs are pure CPU amplifier).
func TestLoginRejectsOversizeFields(t *testing.T) {
	resetLoginLimiter()
	h, _ := testHandler()
	long := strings.Repeat("x", maxLoginFieldLen+1)
	for name, tc := range map[string][2]string{
		"long username": {long, "correct-horse-pw"},
		"long password": {"admin", long},
	} {
		w := postLogin(h, tc[0], tc[1])
		if w.Code != http.StatusBadRequest {
			t.Errorf("%s: got %d, want 400", name, w.Code)
		}
	}
	// Boundary: exactly 256 bytes still reaches verification (401, not 400).
	w := postLogin(h, strings.Repeat("u", maxLoginFieldLen), "x")
	if w.Code != http.StatusUnauthorized {
		t.Errorf("boundary: got %d, want 401", w.Code)
	}
}

func TestLoginRateLimit(t *testing.T) {
	resetLoginLimiter()
	h, _ := testHandler()
	now := loginNow()
	frozen := now
	loginNow = func() time.Time { return frozen }
	t.Cleanup(func() { loginNow = time.Now })

	// Burn the per-IP bucket with failures (wrong password keeps it cheap-ish
	// at MinCost; the point is counting, not bcrypt cost).
	for i := 0; i < maxLoginsPerWindow; i++ {
		if w := postLogin(h, "admin", "nope"); w.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d: got %d, want 401", i, w.Code)
		}
	}
	w := postLogin(h, "admin", "correct-horse-pw")
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("limited login: got %d, want 429", w.Code)
	}
	if w.Header().Get("Retry-After") == "" {
		t.Error("limited login: missing Retry-After header")
	}
	// Limiter exhaustion renders the layout-wrapped operator page (nav-less
	// while anonymous, but inside the shared shell with an onward link),
	// never bare text.
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("limited login content type %q, want html", ct)
	}
	if body := w.Body.String(); !strings.Contains(body, "rate_limited") || !strings.Contains(body, "Back to Sign in") {
		t.Errorf("limited login page missing code/onward link: %s", body)
	}

	// Window passes → login works again.
	frozen = now.Add(2 * loginWindow)
	w = postLogin(h, "admin", "correct-horse-pw")
	if w.Code != http.StatusSeeOther {
		t.Fatalf("post-window login: got %d, want 303", w.Code)
	}
}

func TestLogoutClearsAndRedirects(t *testing.T) {
	h, store := testHandler()
	// A live session cookie so the CSRF token binds to something real.
	lw := httptest.NewRecorder()
	if err := h.store.CreateSession(lw, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	var cookie *http.Cookie
	for _, c := range lw.Result().Cookies() {
		cookie = c
	}
	if cookie == nil {
		t.Fatal("no session cookie minted")
	}
	tok, ok := NewCSRFProtection(testCSRFSecret).TokenForRequest(cookieRequest(cookie))
	if !ok || tok == "" {
		t.Fatal("no CSRF token minted for test session")
	}
	form := url.Values{"csrf_token": {tok}}.Encode()
	req := httptest.NewRequest("POST", "/logout", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(cookie)
	w := httptest.NewRecorder()
	h.handleLogoutPost(w, req)
	if w.Code != http.StatusSeeOther {
		t.Errorf("got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/login?signed_out=1" {
		t.Errorf("logout redirect to %q, want /login?signed_out=1", loc)
	}
	if store.valid {
		t.Error("expected session destroyed")
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("logout Cache-Control %q, want no-store", cc)
	}
}

func TestLogoutRequiresCSRF(t *testing.T) {
	h, _ := testHandler()
	lw := httptest.NewRecorder()
	if err := h.store.CreateSession(lw, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	var cookie *http.Cookie
	for _, c := range lw.Result().Cookies() {
		cookie = c
	}

	// No token → 403, session survives. The failure renders inside the
	// shared shell (nav/chrome + onward link), never bare text.
	form := url.Values{}.Encode()
	req := httptest.NewRequest("POST", "/logout", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(cookie)
	w := httptest.NewRecorder()
	h.handleLogoutPost(w, req)
	if w.Code != http.StatusForbidden {
		t.Errorf("logout without CSRF: got %d, want 403", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("logout CSRF failure content type %q, want html", ct)
	}
	if body := w.Body.String(); !strings.Contains(body, "csrf_mismatch") {
		t.Errorf("logout CSRF failure page missing code: %s", body)
	}

	// Wrong token → 403.
	form = url.Values{"csrf_token": {"wrong"}}.Encode()
	req = httptest.NewRequest("POST", "/logout", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(cookie)
	w = httptest.NewRecorder()
	h.handleLogoutPost(w, req)
	if w.Code != http.StatusForbidden {
		t.Errorf("logout with bad CSRF: got %d, want 403", w.Code)
	}
}

// cookieRequest builds the minimal request TokenForRequest needs: the
// session cookie it binds the token to.
func cookieRequest(cookie *http.Cookie) *http.Request {
	req := httptest.NewRequest("POST", "/logout", nil)
	req.AddCookie(cookie)
	return req
}

// Authed GET /login redirects to the dashboard (303 to /): the signed-in
// page is gone, so the regression this test pins (the logout form must
// carry a session-bound CSRF token or every browser logout 403s) now drives
// the exact browser flow through the dashboard nav: read the token out of
// the rendered nav logout form, submit it, expect the signed-out flash.
func TestLoginPageLogoutFormCarriesCSRF(t *testing.T) {
	resetLoginLimiter()
	h, store := testHandler()
	lw := httptest.NewRecorder()
	if err := h.store.CreateSession(lw, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	var cookie *http.Cookie
	for _, c := range lw.Result().Cookies() {
		cookie = c
	}
	if cookie == nil {
		t.Fatal("no session cookie minted")
	}

	// Authed GET /login leaves the sign-in surface for the dashboard.
	getReq := httptest.NewRequest("GET", "/login", nil)
	getReq.AddCookie(cookie)
	get := httptest.NewRecorder()
	h.handleLoginGet(get, getReq)
	if get.Code != http.StatusSeeOther {
		t.Fatalf("signed-in login page: got %d, want 303", get.Code)
	}
	if loc := get.Header().Get("Location"); loc != "/" {
		t.Fatalf("signed-in login redirect to %q, want /", loc)
	}

	// The dashboard nav logout form carries the session-bound token.
	dash := httptest.NewRecorder()
	HandleDashboard(&fakeStore{}, &fakeLister{}, h.csrf)(dash, authedPageReq("GET", "/", ""))
	page := dash.Body.String()
	const marker = `name="csrf_token" value="`
	i := strings.Index(page, marker)
	if i < 0 {
		t.Fatalf("dashboard nav has no csrf_token logout field (browser logout would always 403):\n%s", page)
	}
	rest := page[i+len(marker):]
	j := strings.Index(rest, `"`)
	if j < 0 {
		t.Fatal("unterminated csrf_token value in logout form")
	}
	tok := rest[:j]
	if tok == "" {
		t.Fatal("empty CSRF token in logout form")
	}

	form := url.Values{"csrf_token": {tok}}.Encode()
	req := httptest.NewRequest("POST", "/logout", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.AddCookie(cookie)
	w := httptest.NewRecorder()
	h.handleLogoutPost(w, req)
	if w.Code != http.StatusSeeOther {
		t.Errorf("browser-form logout: got %d, want 303", w.Code)
	}
	if store.valid {
		t.Error("browser-form logout: expected session destroyed")
	}
}

// A pre-auth spray (one attempt per source IP) must not grow the limiter
// map without bound: past maxLoginLimiterIPs the insert path sweeps and
// evicts, and every single attempt still gets its honest verdict.
func TestLoginLimiterSprayBounded(t *testing.T) {
	resetLoginLimiter()
	h, _ := testHandler()
	for i := 0; i < maxLoginLimiterIPs+100; i++ {
		req := loginReq("admin", "nope")
		req.RemoteAddr = fmt.Sprintf("10.9.%d.%d:1234", i/256, i%256)
		w := httptest.NewRecorder()
		h.handleLoginPost(w, req)
		if w.Code != http.StatusUnauthorized {
			t.Fatalf("spray %d: got %d, want 401", i, w.Code)
		}
	}
	loginMu.Lock()
	n := len(loginByIP)
	loginMu.Unlock()
	if n > maxLoginLimiterIPs {
		t.Errorf("limiter tracked %d IPs, want <= %d", n, maxLoginLimiterIPs)
	}
}

// Fully-expired buckets are dropped, not retained as empty shells: after
// the window passes the same IP logs in cleanly and the map holds exactly
// one bucket.
func TestLoginLimiterDropsEmptyBuckets(t *testing.T) {
	resetLoginLimiter()
	h, _ := testHandler()
	now := loginNow()
	frozen := now
	loginNow = func() time.Time { return frozen }
	t.Cleanup(func() { loginNow = time.Now })

	req := loginReq("admin", "nope")
	req.RemoteAddr = "10.1.2.3:1234"
	if w := func() *httptest.ResponseRecorder {
		w := httptest.NewRecorder()
		h.handleLoginPost(w, req)
		return w
	}(); w.Code != http.StatusUnauthorized {
		t.Fatalf("first attempt: got %d, want 401", w.Code)
	}

	frozen = now.Add(2 * loginWindow)
	req2 := loginReq("admin", "nope")
	req2.RemoteAddr = "10.1.2.3:1234"
	w := httptest.NewRecorder()
	h.handleLoginPost(w, req2)
	if w.Code != http.StatusUnauthorized {
		t.Errorf("post-window attempt: got %d, want 401 (stale bucket must not linger into a false 429)", w.Code)
	}
	loginMu.Lock()
	n := len(loginByIP)
	loginMu.Unlock()
	if n != 1 {
		t.Errorf("limiter holds %d buckets, want exactly 1 (no empty shells)", n)
	}
}

func TestRequireAdmin(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNoContent)
	})

	authed := &memStore{valid: true}
	w := httptest.NewRecorder()
	RequireAdmin(authed, next).ServeHTTP(w, httptest.NewRequest("GET", "/devices", nil))
	if w.Code != http.StatusNoContent {
		t.Errorf("authed page: got %d, want 204", w.Code)
	}

	anon := &memStore{}
	w = httptest.NewRecorder()
	RequireAdmin(anon, next).ServeHTTP(w, httptest.NewRequest("GET", "/devices", nil))
	if w.Code != http.StatusSeeOther {
		t.Errorf("anon page: got %d, want 303", w.Code)
	}

	w = httptest.NewRecorder()
	RequireAdmin(anon, next).ServeHTTP(w, httptest.NewRequest("GET", "/api/v1/widgets", nil))
	if w.Code != http.StatusUnauthorized {
		t.Errorf("anon api: got %d, want 401", w.Code)
	}
	if !strings.Contains(w.Body.String(), `"unauthorized"`) {
		t.Error("anon api: expected JSON error code")
	}
}
