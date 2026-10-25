package webui

// Live pairing detection + rename affordance tests: name validation, the
// rename POST (PRG flashes, validation rejects, CSRF gating), the
// token-status poll shapes, and the QR-panel markup the poll script needs.

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestValidateDeviceName(t *testing.T) {
	for _, tc := range []struct {
		name  string
		input string
		want  string
		ok    bool
	}{
		{"simple", "Pixel 8", "Pixel 8", true},
		{"trims", "  Tab S9  ", "Tab S9", true},
		{"emoji", "📱 Mara's phone", "📱 Mara's phone", true},
		{"cjk", "手机", "手机", true},
		{"punct", "Aaron's Pixel (work) #2", "Aaron's Pixel (work) #2", true},
		{"empty", "", "", false},
		{"spaces", "   ", "", false},
		{"newline", "Phone\nX", "", false},
		{"tab", "Phone\tX", "", false},
		{"del", "Phone\x7f", "", false},
		{"overlong-bytes", strings.Repeat("a", 201), "", false},
		{"overlong-emoji", strings.Repeat("🚀", 51), "", false}, // 204 bytes
	} {
		got, err := ValidateDeviceName(tc.input)
		if tc.ok {
			if err != nil || got != tc.want {
				t.Errorf("%s: got %q err=%v, want %q", tc.name, got, err, tc.want)
			}
		} else if !errors.Is(err, ErrInvalidDeviceName) {
			t.Errorf("%s: got %q err=%v, want ErrInvalidDeviceName", tc.name, got, err)
		}
	}
	// Boundary: exactly 200 bytes passes (byte-exact, never truncated).
	if got, err := ValidateDeviceName(strings.Repeat("b", 200)); err != nil || len(got) != 200 {
		t.Errorf("200-byte boundary: got len=%d err=%v", len(got), err)
	}
	// Invalid UTF-8 is rejected (never stored corrupt).
	if _, err := ValidateDeviceName("bad\xffname"); !errors.Is(err, ErrInvalidDeviceName) {
		t.Errorf("invalid utf8: got err=%v, want ErrInvalidDeviceName", err)
	}
}

func renamePostReq(cookie *http.Cookie, form url.Values) *http.Request {
	req := httptest.NewRequest("POST", "/webui/pair/rename", strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	if cookie != nil {
		req.AddCookie(cookie)
	}
	return req
}

// Rename happy path is PRG: 303 to ?renamed=<name>, and the follow-up GET
// renders the green flash with nav intact.
func TestPairRenameSuccessPRG(t *testing.T) {
	store := &fakeStore{
		rawToken:    "x",
		devices:     []DeviceInfo{{ID: "dev-1", Name: "Old", CreatedAt: "2026-01-01"}},
		renameFound: true,
	}
	csrf := NewCSRFProtection(testCSRFSecret)
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}
	tok, ok := csrf.TokenForRequest(cookieRequest(cookie))
	if !ok {
		t.Fatal("no CSRF token minted")
	}

	w := httptest.NewRecorder()
	HandlePairRename(store, csrf)(w, renamePostReq(cookie, url.Values{
		"device_id":  {"dev-1"},
		"new_name":   {"Pixel 8 Pro"},
		"csrf_token": {tok},
	}))
	if w.Code != http.StatusSeeOther {
		t.Fatalf("got %d, want 303 (%s)", w.Code, w.Body.String())
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("rename Cache-Control %q, want no-store", cc)
	}
	loc := w.Header().Get("Location")
	if !strings.HasPrefix(loc, "/webui/pair?renamed=Pixel+8+Pro") {
		t.Fatalf("rename redirect to %q, want ?renamed=", loc)
	}
	gw := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(gw, authedPageReq("GET", loc, ""))
	if gw.Code != http.StatusOK {
		t.Fatalf("flash page: got %d, want 200", gw.Code)
	}
	if body := gw.Body.String(); !strings.Contains(body, `class="success"`) || !strings.Contains(body, "Pixel 8 Pro") {
		t.Errorf("renamed flash must succeed visibly: %s", body)
	}
}

// Per-row rename affordance: each active row carries a collapsed <details>
// disclosure with a prefilled, CSRF-guarded rename form — no list clutter.
func TestPairRenameAffordanceMarkup(t *testing.T) {
	store := &fakeStore{devices: []DeviceInfo{{ID: "dev-7", Name: "Old Name", CreatedAt: "2026-01-01"}}}
	csrf := NewCSRFProtection(testCSRFSecret)
	w := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(w, authedPageReq("GET", "/webui/pair", ""))
	page := w.Body.String()
	for _, want := range []string{
		`<details><summary>Rename</summary>`,
		`action="/webui/pair/rename"`,
		`name="new_name" value="Old Name"`,
		`id="device-list-wrap"`,
	} {
		if !strings.Contains(page, want) {
			t.Errorf("pair page missing rename affordance %q", want)
		}
	}
}

// Validation failures redirect with ?error=invalid_name (PRG, no dead end);
// the follow-up page explains in-page with nav intact.
func TestPairRenameValidationReject(t *testing.T) {
	store := &fakeStore{renameFound: true}
	csrf := NewCSRFProtection(testCSRFSecret)
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}
	tok, ok := csrf.TokenForRequest(cookieRequest(cookie))
	if !ok {
		t.Fatal("no CSRF token minted")
	}
	for _, tc := range []struct {
		name  string
		value string
	}{
		{"empty", ""},
		{"spaces", "   "},
		{"overlong", strings.Repeat("n", 201)},
		{"newline", "Phone\nX"},
		{"control", "Phone\x01"},
	} {
		w := httptest.NewRecorder()
		HandlePairRename(store, csrf)(w, renamePostReq(cookie, url.Values{
			"device_id":  {"dev-1"},
			"new_name":   {tc.value},
			"csrf_token": {tok},
		}))
		if w.Code != http.StatusSeeOther {
			t.Fatalf("%s: got %d, want 303", tc.name, w.Code)
		}
		if loc := w.Header().Get("Location"); loc != "/webui/pair?error=invalid_name" {
			t.Fatalf("%s: redirect to %q, want ?error=invalid_name", tc.name, loc)
		}
	}
	// The notice renders in-page with nav.
	gw := httptest.NewRecorder()
	HandlePairPage(store, time.Minute, csrf)(gw, authedPageReq("GET", "/webui/pair?error=invalid_name", ""))
	if body := gw.Body.String(); !strings.Contains(body, "single-line") || !strings.Contains(body, `href="/"`) {
		t.Errorf("invalid_name notice must explain in-page with nav: %s", body)
	}
	// A store-level invalid (defense-in-depth for future callers) maps to
	// the same operator error, never a 500.
	store.renameErr = ErrInvalidDeviceName
	w := httptest.NewRecorder()
	HandlePairRename(store, csrf)(w, renamePostReq(cookie, url.Values{
		"device_id": {"dev-1"}, "new_name": {"Fine"}, "csrf_token": {tok},
	}))
	if w.Code != http.StatusSeeOther {
		t.Fatalf("store-invalid: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/webui/pair?error=invalid_name" {
		t.Fatalf("store-invalid redirect to %q", loc)
	}
}

func TestPairRenameRequiresCSRFAndKnownID(t *testing.T) {
	store := &fakeStore{renameFound: false}
	csrf := NewCSRFProtection(testCSRFSecret)
	cookie := &http.Cookie{Name: "pc_session", Value: "x"}

	// No token → 403 inside the shared shell (nav + onward link).
	w := httptest.NewRecorder()
	HandlePairRename(store, csrf)(w, renamePostReq(cookie, url.Values{"device_id": {"d"}, "new_name": {"N"}}))
	if w.Code != http.StatusForbidden {
		t.Fatalf("no CSRF: got %d, want 403", w.Code)
	}
	if body := w.Body.String(); !strings.Contains(body, "csrf_mismatch") || !strings.Contains(body, "Back to pairing") {
		t.Errorf("CSRF failure must render in-shell with onward link: %s", body)
	}

	// Unknown ID skips the update: 303 with ?error=device_not_found.
	tok, ok := csrf.TokenForRequest(cookieRequest(cookie))
	if !ok {
		t.Fatal("no CSRF token minted")
	}
	w = httptest.NewRecorder()
	HandlePairRename(store, csrf)(w, renamePostReq(cookie, url.Values{
		"device_id": {"nope"}, "new_name": {"N"}, "csrf_token": {tok},
	}))
	if w.Code != http.StatusSeeOther {
		t.Fatalf("unknown: got %d, want 303", w.Code)
	}
	if loc := w.Header().Get("Location"); loc != "/webui/pair?error=device_not_found" {
		t.Fatalf("unknown redirect to %q", loc)
	}
}

// Token-status poll shapes: live names nothing, used names the device,
// expired/unknown stop the poll; malformed hashes are 400 JSON. All carry
// no-store + JSON content type.
func TestTokenStatusShapes(t *testing.T) {
	const digest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	for _, tc := range []struct {
		name   string
		status TokenStatus
		stErr  error
		code   int
		want   []string
	}{
		{"live", TokenStatus{Status: TokenLive, ExpiresAt: "2026-01-01T00:00:00Z", ExpiresEpoch: 42}, nil, 200,
			[]string{`"status":"live"`, `"expires_epoch":42`}},
		{"used", TokenStatus{Status: TokenUsed, DeviceID: "dev-1", DeviceName: "Pixel"}, nil, 200,
			[]string{`"status":"used"`, `"device_id":"dev-1"`, `"device_name":"Pixel"`}},
		{"expired", TokenStatus{Status: TokenExpired}, nil, 200, []string{`"status":"expired"`}},
		{"unknown", TokenStatus{Status: TokenUnknown}, nil, 200, []string{`"status":"unknown"`}},
		{"store-error", TokenStatus{}, errors.New("boom"), 500, []string{`"error":"internal"`}},
	} {
		store := &fakeStore{tokenStatus: tc.status, tokenStatusErr: tc.stErr}
		w := httptest.NewRecorder()
		HandleTokenStatus(store)(w, authedPageReq("GET", "/webui/pair/token-status?token_hash="+digest, ""))
		if w.Code != tc.code {
			t.Fatalf("%s: got %d, want %d (%s)", tc.name, w.Code, tc.code, w.Body.String())
		}
		if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
			t.Errorf("%s: Cache-Control %q, want no-store", tc.name, cc)
		}
		if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "application/json") {
			t.Errorf("%s: content type %q, want json", tc.name, ct)
		}
		for _, want := range tc.want {
			if !strings.Contains(w.Body.String(), want) {
				t.Errorf("%s: body missing %q: %s", tc.name, want, w.Body.String())
			}
		}
	}

	// Malformed hashes → 400 JSON (never a lookup, never HTML).
	for _, target := range []string{
		"/webui/pair/token-status",
		"/webui/pair/token-status?token_hash=",
		"/webui/pair/token-status?token_hash=xyz",
		"/webui/pair/token-status?token_hash=" + strings.Repeat("g", 64),
	} {
		w := httptest.NewRecorder()
		HandleTokenStatus(&fakeStore{})(w, authedPageReq("GET", target, ""))
		if w.Code != http.StatusBadRequest {
			t.Errorf("%s: got %d, want 400", target, w.Code)
		}
		if body := w.Body.String(); !strings.Contains(body, "invalid_request") {
			t.Errorf("%s: body %q, want invalid_request", target, body)
		}
	}

	// Nil store fails closed with JSON 500 (never a panic, never HTML).
	w := httptest.NewRecorder()
	HandleTokenStatus(nil)(w, authedPageReq("GET", "/webui/pair/token-status?token_hash="+digest, ""))
	if w.Code != http.StatusInternalServerError {
		t.Errorf("nil store: got %d, want 500", w.Code)
	}
}

// The minted QR panel carries everything the live poll needs: the panel id,
// the token hash (never the raw secret in a URL), and the countdown the
// poll shares the expiry with.
func TestMintedPanelCarriesPollWiring(t *testing.T) {
	store := &fakeStore{rawToken: "live-poll-token-abc"}
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
	if !strings.Contains(page, `id="pair-qr-panel"`) || !strings.Contains(page, "data-token-hash=") {
		t.Error("minted panel must carry id + data-token-hash for the live poll")
	}
	if strings.Contains(page, "live-poll-token-abc"+`?`) || strings.Contains(page, "token-status?token_hash=live") {
		t.Error("raw token must never appear in a poll URL")
	}
	// data-token-hash must be the SHA-256 hex of the raw token.
	const marker = `data-token-hash="`
	i := strings.Index(page, marker)
	if i < 0 {
		t.Fatal("no data-token-hash in minted panel")
	}
	hash := page[i+len(marker):]
	if j := strings.Index(hash, `"`); j >= 0 {
		hash = hash[:j]
	}
	if len(hash) != 64 || !isHexTokenHash(strings.ToLower(hash)) {
		t.Errorf("data-token-hash %q is not a SHA-256 hex digest", hash)
	}

	// The JSON auto-refresh shape carries the same hash so a refreshed
	// token keeps the poll in sync.
	jreq := authedPageReq("POST", "/webui/pair/mint", "")
	jreq.Header.Set("Accept", "application/json")
	jreq.Header.Set("X-CSRF-Token", tok)
	jw := httptest.NewRecorder()
	HandlePairMint(store, time.Minute, csrf)(jw, jreq)
	if jw.Code != http.StatusOK {
		t.Fatalf("mint JSON: got %d, want 200", jw.Code)
	}
	if body := jw.Body.String(); !strings.Contains(body, `"token_hash"`) {
		t.Errorf("mint JSON must carry token_hash for poll continuity: %s", body)
	}
}

// The live poll script runs only while a token is displayed, polls every
// ~3s, stops on used/expired, backs off on 429, and builds DOM with
// textContent (never innerHTML from device names).
func TestLivePollScriptBehavior(t *testing.T) {
	store := &fakeStore{rawToken: "poll-script-token"}
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
	page := mw.Body.String()

	for _, want := range []string{
		"token-status?token_hash=",
		"3000",
		"__pairLiveDone",
		"Retry-After",
		"device-list-wrap",
		"textContent",
	} {
		if !strings.Contains(page, want) {
			t.Errorf("live poll script missing %q", want)
		}
	}
	// No innerHTML assignment from polled JSON (XSS surface): the only
	// innerHTML write is the server-rendered list swap.
	if n := strings.Count(page, ".innerHTML="); n != 1 {
		t.Errorf("innerHTML writes: %d, want exactly 1 (server-rendered list swap)", n)
	}

	// The plain pair page (no token) ships no poll wiring at all.
	plain := getw.Body.String()
	if strings.Contains(plain, "token-status") {
		t.Error("tokenless page must not ship poll wiring")
	}
}
