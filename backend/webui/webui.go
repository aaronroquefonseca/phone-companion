// Package webui serves the browser WebUI: login/logout and the
// session gate for future operator pages (pairing, devices, notify).
//
// Plain stdlib HTML only — no CSS/JS framework. Credential verification
// supports ADMIN_PASSWORD_HASH (bcrypt, preferred) or the ADMIN_PASSWORD
// bootstrap fallback. Login failures always return the same generic error
// so usernames cannot be enumerated.
package webui

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"golang.org/x/crypto/bcrypt"
)

// SessionStore is the cookie session backend. The concrete implementation
// lives in package main (backend/session.go); this interface keeps webui
// decoupled from it.
type SessionStore interface {
	CreateSession(w http.ResponseWriter, r *http.Request) error
	ValidateSession(r *http.Request) bool
	DestroySession(w http.ResponseWriter, r *http.Request)
}

// Credentials are the WebUI admin login, copied from Config at boot.
type Credentials struct {
	Username     string
	PasswordHash string
	Password     string
}

// RegisterRoutes wires the auth endpoints onto mux.
func RegisterRoutes(mux *http.ServeMux, store SessionStore, creds Credentials) {
	h := &handler{store: store, creds: creds}
	mux.HandleFunc("GET /login", h.handleLoginGet)
	mux.HandleFunc("POST /login", h.handleLoginPost)
	mux.HandleFunc("POST /logout", h.handleLogoutPost)
}

// RequireAdmin gates future operator pages and APIs behind the WebUI
// session. Browser pages redirect to /login; /api/* calls get a 401 JSON
// error per docs/api-contract.md.
func RequireAdmin(store SessionStore, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if store.ValidateSession(r) {
			next.ServeHTTP(w, r)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/api/") {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"error":"unauthorized"}`))
			return
		}
		http.Redirect(w, r, "/login", http.StatusFound)
	})
}

type handler struct {
	store SessionStore
	creds Credentials
}

// verify runs both comparisons unconditionally so a wrong username does
// not short-circuit the password check (no user enumeration oracle).
func (h *handler) verify(username, password string) bool {
	userOK := subtle.ConstantTimeCompare([]byte(username), []byte(h.creds.Username)) == 1
	var passOK bool
	if h.creds.PasswordHash != "" {
		passOK = bcrypt.CompareHashAndPassword([]byte(h.creds.PasswordHash), []byte(password)) == nil
	} else {
		passOK = subtle.ConstantTimeCompare([]byte(password), []byte(h.creds.Password)) == 1
	}
	return userOK && passOK
}
