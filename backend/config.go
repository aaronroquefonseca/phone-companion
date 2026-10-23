// Package main — backend configuration.
//
// Config is loaded from the environment so the same image runs anywhere
// (local Docker, homeserver, VPS behind a reverse proxy) with no code
// changes. See docker-compose.example.yml and docs/api-contract.md.
package main

import (
	"errors"
	"net/url"
	"os"
	"strings"
)

// Config holds all backend runtime settings. Secrets must come from the
// environment (or a mounted secret file wired here later) — never from the
// repo. See AGENTS.md.
type Config struct {
	// Port the HTTP server listens on. $PORT, default 8080.
	Port string
	// AdminUser is the WebUI login name. $ADMIN_USER.
	AdminUser string
	// AdminPasswordHash is the preferred WebUI credential (e.g. bcrypt).
	// $ADMIN_PASSWORD_HASH.
	AdminPasswordHash string
	// AdminPassword is a bootstrap-only fallback verified directly when
	// ADMIN_PASSWORD_HASH is unset. Prefer ADMIN_PASSWORD_HASH.
	//
	// TODO (future hardening): hash-and-store-on-first-boot, then require
	// the hash afterwards.
	AdminPassword string
	// SessionSecret signs WebUI session cookies. $SESSION_SECRET.
	// Must be at least 32 bytes; enforced by Validate (fail closed).
	SessionSecret string
	// DBPath is the sqlite file location. $DB_PATH, default /data/phone-companion.db.
	DBPath string
	// WidgetDir is the mounted folder-per-widget directory.
	// $WIDGET_DIR, default /widgets.
	WidgetDir string

	// FCM settings are optional until push is implemented.
	// TODO (notifications PR): document service-account wiring.
	FCMProjectID   string // $FCM_PROJECT_ID
	FCMCredentials string // $FCM_CREDENTIALS_FILE (path to mounted service-account JSON)

	// ExternalBaseURL is the public origin used to build the WebUI pairing
	// QR payload (enroll_url). $EXTERNAL_BASE_URL, e.g.
	// "https://example.com". Optional: when empty the QR builder falls
	// back to the request Host + X-Forwarded-Proto, which is dev-only
	// (Host headers are client-controlled and spoofable behind a
	// misconfigured proxy). Production must set EXTERNAL_BASE_URL.
	ExternalBaseURL string // $EXTERNAL_BASE_URL
}

// Fail-closed bounds for auth secrets. These are deliberately strict: the
// backend refuses to boot rather than serve with guessable credentials.
const (
	// minSessionSecretBytes is the minimum accepted SESSION_SECRET length.
	minSessionSecretBytes = 32
	// minBootstrapPasswordLen is the minimum accepted ADMIN_PASSWORD length.
	minBootstrapPasswordLen = 12
	// minPasswordHashLen rejects empty/truncated ADMIN_PASSWORD_HASH values.
	minPasswordHashLen = 20
)

// LoadConfig reads the environment into a Config, applying defaults.
//
// LoadConfig does NOT validate: callers must call Validate and refuse to
// boot on error (fail closed). Validation lives here so every entrypoint
// shares one policy; see main.go.
func LoadConfig() Config {
	return Config{
		Port:              getenv("PORT", "8080"),
		AdminUser:         os.Getenv("ADMIN_USER"),
		AdminPasswordHash: os.Getenv("ADMIN_PASSWORD_HASH"),
		AdminPassword:     os.Getenv("ADMIN_PASSWORD"),
		SessionSecret:     os.Getenv("SESSION_SECRET"),
		DBPath:            getenv("DB_PATH", "/data/phone-companion.db"),
		WidgetDir:         getenv("WIDGET_DIR", "/widgets"),
		FCMProjectID:      os.Getenv("FCM_PROJECT_ID"),
		FCMCredentials:    os.Getenv("FCM_CREDENTIALS_FILE"),
		ExternalBaseURL:   normalizeBaseURL(os.Getenv("EXTERNAL_BASE_URL")),
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// Validate rejects missing or weak auth configuration. Error messages
// describe which setting is wrong but never include secret material.
func (c Config) Validate() error {
	if len(c.SessionSecret) < minSessionSecretBytes {
		return errors.New("SESSION_SECRET must be at least 32 bytes long")
	}
	if strings.HasPrefix(c.SessionSecret, "CHANGEME") {
		return errors.New("SESSION_SECRET still holds a placeholder value")
	}
	if strings.TrimSpace(c.AdminUser) == "" {
		return errors.New("ADMIN_USER must be set")
	}
	if strings.HasPrefix(c.AdminUser, "CHANGEME") {
		return errors.New("ADMIN_USER still holds a placeholder value")
	}
	if c.AdminPasswordHash == "" && c.AdminPassword == "" {
		return errors.New("set ADMIN_PASSWORD_HASH (preferred) or ADMIN_PASSWORD (bootstrap only)")
	}
	if c.AdminPasswordHash != "" {
		if len(c.AdminPasswordHash) < minPasswordHashLen {
			return errors.New("ADMIN_PASSWORD_HASH looks truncated")
		}
		if strings.HasPrefix(c.AdminPasswordHash, "CHANGEME") {
			return errors.New("ADMIN_PASSWORD_HASH still holds a placeholder value")
		}
	}
	if c.AdminPassword != "" {
		if len(c.AdminPassword) < minBootstrapPasswordLen {
			return errors.New("ADMIN_PASSWORD must be at least 12 characters long")
		}
		if strings.HasPrefix(c.AdminPassword, "CHANGEME") {
			return errors.New("ADMIN_PASSWORD still holds a placeholder value")
		}
	}
	if c.ExternalBaseURL != "" {
		u, err := url.Parse(c.ExternalBaseURL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			return errors.New("EXTERNAL_BASE_URL must be an http(s) origin like https://example.com")
		}
		if u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
			return errors.New("EXTERNAL_BASE_URL must be a bare origin with no path, query, or fragment")
		}
	}
	return nil
}

// normalizeBaseURL trims whitespace and trailing slashes so
// EXTERNAL_BASE_URL + "/api/v1/enroll" never doubles slashes.
func normalizeBaseURL(v string) string {
	v = strings.TrimSpace(v)
	for strings.HasSuffix(v, "/") && v != "" {
		v = strings.TrimSuffix(v, "/")
	}
	return v
}
