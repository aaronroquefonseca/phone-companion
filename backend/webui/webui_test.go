package webui

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

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
	return &handler{store: &memStore{}, creds: Credentials{
		Username:     "admin",
		PasswordHash: string(hash),
	}}, &memStore{}
}

func postLogin(h *handler, user, pass string) *httptest.ResponseRecorder {
	form := url.Values{"username": {user}, "password": {pass}}.Encode()
	req := httptest.NewRequest("POST", "/login", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	w := httptest.NewRecorder()
	h.handleLoginPost(w, req)
	return w
}

func TestLoginGetRendersForm(t *testing.T) {
	h, _ := testHandler()
	w := httptest.NewRecorder()
	h.handleLoginGet(w, httptest.NewRequest("GET", "/login", nil))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d, want 200", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Errorf("content type %q, want html", ct)
	}
}

func TestLoginSuccessAndFailure(t *testing.T) {
	h, _ := testHandler()

	ok := postLogin(h, "admin", "correct-horse-pw")
	if ok.Code != http.StatusSeeOther {
		t.Errorf("success: got %d, want 303", ok.Code)
	}
	if c := ok.Result().Cookies(); len(c) == 0 {
		t.Error("success: expected Set-Cookie")
	}

	for name, tc := range map[string][2]string{
		"wrong password": {"admin", "nope"},
		"wrong user":     {"root", "correct-horse-pw"},
		"empty":          {"", ""},
	} {
		w := postLogin(h, tc[0], tc[1])
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s: got %d, want 401", name, w.Code)
		}
		if !strings.Contains(w.Body.String(), "Invalid username or password") {
			t.Errorf("%s: expected generic error page", name)
		}
	}
}

func TestLogoutClearsAndRedirects(t *testing.T) {
	h, store := testHandler()
	req := httptest.NewRequest("POST", "/logout", nil)
	w := httptest.NewRecorder()
	h.handleLogoutPost(w, req)
	if w.Code != http.StatusSeeOther {
		t.Errorf("got %d, want 303", w.Code)
	}
	if store.valid {
		t.Error("expected session destroyed")
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
	if w.Code != http.StatusFound {
		t.Errorf("anon page: got %d, want 302", w.Code)
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
