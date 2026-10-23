package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

func openTestDB(t *testing.T) string {
	t.Helper()
	path := t.TempDir() + "/test.db"
	db, err := OpenDB(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	return path
}

func testStore(t *testing.T, secret string) *SessionStore {
	t.Helper()
	path := openTestDB(t)
	db, err := OpenDB(path)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	s, err := NewSessionStore(db, secret)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func sessionCookie(resp *httptest.ResponseRecorder) *http.Cookie {
	for _, c := range resp.Result().Cookies() {
		if c.Name == SessionCookieName {
			return c
		}
	}
	return nil
}

func TestSessionRoundTrip(t *testing.T) {
	s := testStore(t, strings.Repeat("s", 32))

	w := httptest.NewRecorder()
	if err := s.CreateSession(w, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	c := sessionCookie(w)
	if c == nil {
		t.Fatal("no session cookie set")
	}
	if !c.HttpOnly || !c.Secure || c.SameSite != http.SameSiteLaxMode || c.Path != "/" {
		t.Errorf("weak cookie attributes: %+v", c)
	}

	req := httptest.NewRequest("GET", "/", nil)
	req.AddCookie(c)
	if !s.ValidateSession(req) {
		t.Error("fresh session should validate")
	}

	dw := httptest.NewRecorder()
	s.DestroySession(dw, req)
	if got := sessionCookie(dw); got == nil || got.MaxAge != -1 {
		t.Error("logout should clear the cookie")
	}
	if s.ValidateSession(req) {
		t.Error("destroyed session should not validate")
	}
}

func TestSessionRejectsTamperedCookie(t *testing.T) {
	s := testStore(t, strings.Repeat("s", 32))
	w := httptest.NewRecorder()
	if err := s.CreateSession(w, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	c := sessionCookie(w)

	bad := httptest.NewRequest("GET", "/", nil)
	tampered := *c
	tampered.Value = c.Value[:len(c.Value)-2] + "xx"
	bad.AddCookie(&tampered)
	if s.ValidateSession(bad) {
		t.Error("tampered cookie should not validate")
	}

	// Same DB, different signing secret: signature check must fail first.
	other, err := NewSessionStore(s.db, strings.Repeat("o", 32))
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest("GET", "/", nil)
	req.AddCookie(c)
	if other.ValidateSession(req) {
		t.Error("cookie signed under another secret should not validate")
	}
	// Sanity: the original store still accepts it.
	if !s.ValidateSession(req) {
		t.Error("original store should still accept the cookie")
	}
}

func TestSessionExpiryEnforced(t *testing.T) {
	s := testStore(t, strings.Repeat("s", 32))
	past := time.Now().UTC().Add(-time.Hour)
	future := time.Now().UTC()
	s.now = func() time.Time { return past }
	w := httptest.NewRecorder()
	if err := s.CreateSession(w, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	s.now = func() time.Time { return future.Add(SessionTTL + time.Minute) }
	req := httptest.NewRequest("GET", "/", nil)
	req.AddCookie(sessionCookie(w))
	if s.ValidateSession(req) {
		t.Error("expired session should not validate")
	}
}

func TestNewSessionStoreRejectsBadConfig(t *testing.T) {
	if _, err := NewSessionStore(nil, strings.Repeat("s", 32)); err == nil {
		t.Error("nil db: expected fail-closed error")
	}
	path := t.TempDir() + "/test.db"
	db, err := OpenDB(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if _, err := NewSessionStore(db, "too-short"); err == nil {
		t.Error("short secret: expected fail-closed error")
	}
	if _, err := NewSessionStore(db, strings.Repeat("s", 32)); err != nil {
		t.Errorf("valid config: unexpected error %v", err)
	}
}

// The split single-statement Execs (session table + index, history table +
// index, device token index) must all materialize: a silently skipped index
// would turn every expiry sweep and lookup into a full-table scan.
func TestMigratedIndexesExist(t *testing.T) {
	path := t.TempDir() + "/idx.db"
	db, err := OpenDB(path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	if err := notify.Migrate(db); err != nil {
		t.Fatal(err)
	}
	for _, idx := range []string{
		"idx_sessions_expires",
		"idx_notification_history_notification_id",
		"idx_devices_token_hash",
	} {
		var name string
		if err := db.QueryRow(
			`SELECT name FROM sqlite_master WHERE type = 'index' AND name = ?`, idx).Scan(&name); err != nil {
			t.Errorf("index %s missing after migrate: %v", idx, err)
		}
	}
}

func TestCreateSessionRotatesPriorAndSweepsExpired(t *testing.T) {
	s := testStore(t, strings.Repeat("s", 32))

	w1 := httptest.NewRecorder()
	if err := s.CreateSession(w1, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	first := sessionCookie(w1)
	if first == nil {
		t.Fatal("no session cookie issued")
	}

	// Plant an expired row directly: the next Create must sweep it.
	if _, err := s.db.Exec(
		`INSERT INTO sessions (token_hash, created_at, expires_at) VALUES (?, ?, ?)`,
		"expired-row",
		time.Now().UTC().Add(-2*time.Hour).Format(time.RFC3339),
		time.Now().UTC().Add(-time.Hour).Format(time.RFC3339)); err != nil {
		t.Fatal(err)
	}

	// Second login presenting the first cookie: rotation destroys the prior
	// session and the sweep removes the expired row — one live row remains.
	req := httptest.NewRequest("POST", "/login", nil)
	req.AddCookie(first)
	w2 := httptest.NewRecorder()
	if err := s.CreateSession(w2, req); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := s.db.QueryRow(`SELECT COUNT(*) FROM sessions`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Errorf("want exactly 1 live session row after rotation+sweep, got %d", n)
	}
	second := sessionCookie(w2)
	if second == nil {
		t.Fatal("no rotated session cookie issued")
	}
	oldReq := httptest.NewRequest("GET", "/", nil)
	oldReq.AddCookie(first)
	if s.ValidateSession(oldReq) {
		t.Error("presented prior session should be destroyed by rotation")
	}
	newReq := httptest.NewRequest("GET", "/", nil)
	newReq.AddCookie(second)
	if !s.ValidateSession(newReq) {
		t.Error("new session should validate")
	}
}
