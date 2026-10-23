// Package main — WebUI cookie session store.
//
// Server-side sessions for the browser WebUI (see docs/api-contract.md
// "WebUI session"). Each login mints a 256-bit random token; only a SHA-256
// hash of the token is persisted (sessions table), so a database read never
// discloses a live session. The cookie value additionally carries an
// HMAC-SHA256 signature keyed by SESSION_SECRET, verified before any DB
// lookup, so forged cookies fail fast without touching storage.
//
// Cookie: pc_session, Path=/, HttpOnly, Secure, SameSite=Lax, 24h expiry.
// Secure is always set because production sits behind a TLS-terminating
// reverse proxy (see docker-compose.example.yml).
package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"net/http"
	"strings"
	"time"
)

// SessionCookieName is the WebUI session cookie pinned by docs/api-contract.md.
const SessionCookieName = "pc_session"

// SessionTTL is the absolute lifetime of a WebUI session.
const SessionTTL = 24 * time.Hour

// schemaSessionsV1 holds one row per live WebUI session. Only the token
// hash is stored — never the raw token from the cookie.
const schemaSessionsV1 = `
CREATE TABLE IF NOT EXISTS sessions (
	token_hash TEXT PRIMARY KEY,
	created_at TEXT NOT NULL,
	expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
`

// migrateSessions creates the sessions table. Called from Migrate in db.go.
func migrateSessions(db *sql.DB) error {
	_, err := db.Exec(schemaSessionsV1)
	return err
}

// SessionStore mints, validates, and revokes WebUI sessions.
type SessionStore struct {
	db     *sql.DB
	secret []byte
	ttl    time.Duration
	now    func() time.Time
}

// NewSessionStore returns a store backed by db, signing cookies with
// sessionSecret. The secret must already have passed Config.Validate.
func NewSessionStore(db *sql.DB, sessionSecret string) *SessionStore {
	return &SessionStore{db: db, secret: []byte(sessionSecret), ttl: SessionTTL, now: time.Now}
}

// CreateSession mints a session, persists its hash, and sets the cookie.
func (s *SessionStore) CreateSession(w http.ResponseWriter, _ *http.Request) error {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return err
	}
	now := s.now().UTC()
	exp := now.Add(s.ttl)
	if _, err := s.db.Exec(
		`INSERT INTO sessions (token_hash, created_at, expires_at) VALUES (?, ?, ?)`,
		hashToken(raw[:]), now.Format(time.RFC3339), exp.Format(time.RFC3339),
	); err != nil {
		return err
	}
	setSessionCookie(w, encodeSessionValue(raw[:], s.secret), exp)
	return nil
}

// ValidateSession reports whether the request carries a live session.
// Expired rows are deleted opportunistically.
func (s *SessionStore) ValidateSession(r *http.Request) bool {
	c, err := r.Cookie(SessionCookieName)
	if err != nil || c.Value == "" {
		return false
	}
	raw, ok := decodeSessionValue(c.Value, s.secret)
	if !ok {
		return false
	}
	var expires string
	err = s.db.QueryRow(
		`SELECT expires_at FROM sessions WHERE token_hash = ?`, hashToken(raw),
	).Scan(&expires)
	if err != nil {
		return false
	}
	exp, err := time.Parse(time.RFC3339, expires)
	if err != nil {
		return false
	}
	if !s.now().UTC().Before(exp) {
		_, _ = s.db.Exec(`DELETE FROM sessions WHERE token_hash = ?`, hashToken(raw))
		return false
	}
	return true
}

// DestroySession revokes the request's session (if any) and clears the cookie.
// It is idempotent: requests without a session still get a cleared cookie.
func (s *SessionStore) DestroySession(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(SessionCookieName); err == nil && c.Value != "" {
		if raw, ok := decodeSessionValue(c.Value, s.secret); ok {
			_, _ = s.db.Exec(`DELETE FROM sessions WHERE token_hash = ?`, hashToken(raw))
		}
	}
	clearSessionCookie(w)
}

// hashToken identifies a session row without persisting the raw token.
func hashToken(raw []byte) string {
	sum := sha256.Sum256(raw)
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// encodeSessionValue renders "<token>.<hmac>" for the cookie.
func encodeSessionValue(raw, secret []byte) string {
	mac := hmac.New(sha256.New, secret)
	mac.Write(raw)
	return base64.RawURLEncoding.EncodeToString(raw) + "." +
		base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// decodeSessionValue verifies the HMAC and returns the raw token.
func decodeSessionValue(value string, secret []byte) ([]byte, bool) {
	parts := strings.Split(value, ".")
	if len(parts) != 2 {
		return nil, false
	}
	raw, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil || len(raw) != 32 {
		return nil, false
	}
	sig, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil || len(sig) != sha256.Size {
		return nil, false
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write(raw)
	if subtle.ConstantTimeCompare(sig, mac.Sum(nil)) != 1 {
		return nil, false
	}
	return raw, true
}

func setSessionCookie(w http.ResponseWriter, value string, exp time.Time) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    value,
		Path:     "/",
		Expires:  exp,
		MaxAge:   int(SessionTTL.Seconds()),
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
	})
}

func clearSessionCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   true,
		SameSite: http.SameSiteLaxMode,
	})
}
