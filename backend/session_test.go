package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
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
	return NewSessionStore(db, secret)
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
	other := NewSessionStore(s.db, strings.Repeat("o", 32))
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
