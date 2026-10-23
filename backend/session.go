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
// Secure is set by default because production sits behind a TLS-terminating
// reverse proxy (see docker-compose.example.yml); plain-HTTP development may
// drop it explicitly via ALLOW_INSECURE_COOKIES=1 (dev-only, never in
// production — see docs/setup.md "Plain-HTTP development").
package main

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"net/http"
	"strings"
	"time"
)

// allowInsecureSessionCookie is the explicit, fail-closed escape hatch for
// plain-HTTP development (see Config.AllowInsecureCookies /
// ALLOW_INSECURE_COOKIES). Default false: cookies stay always-Secure.
// When true the Secure flag is dropped so browsers send pc_session over
// http:// loopback/LAN origins. NEVER enable in production: session cookies
// are live credentials and must ride TLS. Wired once at boot from main.go.
var allowInsecureSessionCookie bool

// SetAllowInsecureSessionCookie enables (true) or restores (false) the
// Secure flag on session cookies. Called once at boot from the validated
// Config; tests may toggle it with cleanup.
func SetAllowInsecureSessionCookie(allow bool) {
	allowInsecureSessionCookie = allow
}

// SessionCookieName is the WebUI session cookie pinned by docs/api-contract.md.
const SessionCookieName = "pc_session"

// SessionTTL is the absolute lifetime of a WebUI session.
const SessionTTL = 24 * time.Hour

// schemaSessionsV1Table holds one row per live WebUI session. Only the token
// hash is stored — never the raw token from the cookie.
const schemaSessionsV1Table = `
CREATE TABLE IF NOT EXISTS sessions (
	token_hash TEXT PRIMARY KEY,
	created_at TEXT NOT NULL,
	expires_at TEXT NOT NULL
);
`

// schemaSessionsV1Index speeds expiry sweeps and lookups. Kept as a separate
// statement from the table DDL: some drivers reject multi-statement Exec, so
// each statement gets its own Exec call below.
const schemaSessionsV1Index = `
CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);
`

// migrateSessions creates the sessions table. Called from Migrate in db.go.
func migrateSessions(db *sql.DB) error {
	if _, err := db.Exec(schemaSessionsV1Table); err != nil {
		return err
	}
	_, err := db.Exec(schemaSessionsV1Index)
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
// sessionSecret. The secret must already have passed Config.Validate; this
// constructor re-checks (nil db, secret shorter than 32 bytes) and fails
// closed with an error so a miswired server never serves with a weak or
// missing signer. Callers must refuse to boot on error.
func NewSessionStore(db *sql.DB, sessionSecret string) (*SessionStore, error) {
	if db == nil {
		return nil, errors.New("session: nil database (fail closed)")
	}
	if len(sessionSecret) < minSessionSecretBytes {
		return nil, errors.New("session: secret must be at least 32 bytes long")
	}
	return &SessionStore{db: db, secret: []byte(sessionSecret), ttl: SessionTTL, now: time.Now}, nil
}

// CreateSession mints a session, persists its hash, and sets the cookie.
//
// Rotation-invalidates-all: the presented session (if the request carries a
// live cookie) is destroyed BEFORE the new row is minted, so every login
// supersedes the credential it replaces — a stolen pre-login cookie dies on
// the victim's next login. Rotating SESSION_SECRET likewise invalidates every
// outstanding session at once (old HMACs stop verifying; orphaned rows are
// swept below and can never authenticate without a valid signature).
func (s *SessionStore) CreateSession(w http.ResponseWriter, r *http.Request) error {
	if s == nil || s.db == nil || len(s.secret) < minSessionSecretBytes {
		return errors.New("session: store not wired (fail closed)")
	}
	// Destroy the prior presented session first (rotation).
	if r != nil {
		if c, err := r.Cookie(SessionCookieName); err == nil && c.Value != "" {
			if raw, ok := decodeSessionValue(c.Value, s.secret); ok {
				_, _ = s.db.Exec(`DELETE FROM sessions WHERE token_hash = ?`, hashToken(raw))
			}
		}
		// Sweep expired rows on the write path so the table cannot grow
		// without bound when ValidateSession is never hit for them.
		_, _ = s.db.Exec(`DELETE FROM sessions WHERE expires_at <= ?`, s.now().UTC().Format(time.RFC3339))
	}
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
	// Single hash per request: reused for the lookup and the expiry delete.
	tokenHash := hashToken(raw)
	var expires string
	err = s.db.QueryRow(
		`SELECT expires_at FROM sessions WHERE token_hash = ?`, tokenHash,
	).Scan(&expires)
	if err != nil {
		return false
	}
	exp, err := time.Parse(time.RFC3339, expires)
	if err != nil {
		return false
	}
	if !s.now().UTC().Before(exp) {
		_, _ = s.db.Exec(`DELETE FROM sessions WHERE token_hash = ?`, tokenHash)
		return false
	}
	return true
}

// DestroySession revokes the request's session (if any) and clears the cookie.
// It is idempotent: requests without a session still get a cleared cookie.
func (s *SessionStore) DestroySession(w http.ResponseWriter, r *http.Request) {
	if s != nil && s.db != nil {
		if c, err := r.Cookie(SessionCookieName); err == nil && c.Value != "" {
			if raw, ok := decodeSessionValue(c.Value, s.secret); ok {
				_, _ = s.db.Exec(`DELETE FROM sessions WHERE token_hash = ?`, hashToken(raw))
			}
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
	// Session-bearing responses must never be cached: the Set-Cookie value
	// is a live credential.
	w.Header().Set("Cache-Control", "no-store")
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    value,
		Path:     "/",
		Expires:  exp,
		MaxAge:   int(SessionTTL.Seconds()),
		HttpOnly: true,
		Secure:   !allowInsecureSessionCookie,
		SameSite: http.SameSiteLaxMode,
	})
}

func clearSessionCookie(w http.ResponseWriter) {
	w.Header().Set("Cache-Control", "no-store")
	http.SetCookie(w, &http.Cookie{
		Name:     SessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		Secure:   !allowInsecureSessionCookie,
		SameSite: http.SameSiteLaxMode,
	})
}
