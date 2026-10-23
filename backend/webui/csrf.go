// Package webui — session-bound CSRF protection for operator POST forms.
//
// The WebUI session cookie is HttpOnly + SameSite=Lax, which blunts but
// does not eliminate CSRF (top-level navigations still send the cookie).
// Every state-changing WebUI form therefore carries a per-session token:
//
//	token = base64url(HMAC_SHA256(csrfSecret, "webui-csrf-v1" || 0x00 || sessionCookieValue))
//
// The token is bound to the session cookie value (random per login), so an
// attacker page cannot forge it without reading the victim's cookie —
// which HttpOnly already denies. Verification recomputes the MAC from the
// presented cookie and constant-time compares.
//
// The HMAC key reuses SESSION_SECRET with a distinct domain-separation
// prefix ("webui-csrf-v1"), so no new secret needs provisioning. A nil or
// empty-secret protector fails closed (no tokens issued, all POSTs 403).
package webui

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
)

// webuiSessionCookieName must match main.SessionCookieName
// (backend/session.go). Kept as an unexported constant here so the webui
// package stays decoupled from main; a test in package main asserts
// equality.
const webuiSessionCookieName = "pc_session"

// csrfDomain separates the CSRF MAC from the session-cookie signature
// even though both key off SESSION_SECRET.
const csrfDomain = "webui-csrf-v1"

// CSRFProtection issues and verifies session-bound CSRF tokens.
type CSRFProtection struct {
	secret []byte
}

// NewCSRFProtection returns a protector keyed by secret (SESSION_SECRET).
// An empty secret yields a fail-closed protector.
func NewCSRFProtection(secret string) *CSRFProtection {
	return &CSRFProtection{secret: []byte(secret)}
}

// TokenForRequest derives the CSRF token for the request's session.
// It reports false when there is no session cookie or the protector is
// fail-closed, so callers never emit a usable token for anonymous users.
func (c *CSRFProtection) TokenForRequest(r *http.Request) (string, bool) {
	if c == nil || len(c.secret) == 0 || r == nil {
		return "", false
	}
	ck, err := r.Cookie(webuiSessionCookieName)
	if err != nil || ck.Value == "" {
		return "", false
	}
	mac := hmac.New(sha256.New, c.secret)
	mac.Write([]byte(csrfDomain))
	mac.Write([]byte{0x00})
	mac.Write([]byte(ck.Value))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), true
}

// VerifyRequest reports whether the request carries a valid CSRF token,
// either as the "csrf_token" form field or the "X-CSRF-Token" header
// (header checked second so plain HTML forms keep working).
func (c *CSRFProtection) VerifyRequest(r *http.Request) bool {
	if c == nil || len(c.secret) == 0 || r == nil {
		return false
	}
	expected, ok := c.TokenForRequest(r)
	if !ok || expected == "" {
		return false
	}
	got := r.FormValue("csrf_token")
	if got == "" {
		got = r.Header.Get("X-CSRF-Token")
	}
	if got == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(expected)) == 1
}
