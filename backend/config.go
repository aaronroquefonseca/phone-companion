// Package main — backend configuration.
//
// Config is loaded from the environment so the same image runs anywhere
// (local Docker, homeserver, VPS behind a reverse proxy) with no code
// changes. See docker-compose.example.yml and docs/api-contract.md.
package main

import (
	"os"
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
	// AdminPassword is a bootstrap-only fallback used to derive the hash on
	// first run. Prefer ADMIN_PASSWORD_HASH. $ADMIN_PASSWORD.
	//
	// TODO (auth PR): implement hash-and-store-on-first-boot, then require
	// the hash afterwards.
	AdminPassword string
	// SessionSecret signs WebUI session cookies. $SESSION_SECRET.
	//
	// TODO (auth PR): enforce minimum length / fail closed when unset.
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
}

// LoadConfig reads the environment into a Config, applying defaults.
// Validation and fail-closed secret checks are TODOs for the auth PR.
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
	}
}

func getenv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
