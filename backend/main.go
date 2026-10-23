// Package main is the Phone Companion backend entrypoint.
//
// P0 BASE skeleton: wiring and contracts only. WebUI login/session is
// wired here; pairing, notifications, encryption, and widget logic are
// TODOs owned by later PRs.
package main

import (
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

func main() {
	cfg := LoadConfig()
	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid configuration: %v", err)
	}

	db, err := OpenDB(cfg.DBPath)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}
	defer db.Close()
	if err := Migrate(db); err != nil {
		log.Fatalf("migrate database: %v", err)
	}
	if err := notify.Migrate(db); err != nil {
		log.Fatalf("migrate notifications: %v", err)
	}

	// Notifications (P4): fail fast on unknown NOTIFY_DRIVER so a typo can
	// never silently become a black hole.
	sender, err := notify.SenderFromEnv(os.Getenv("NOTIFY_DRIVER"), cfg.FCMProjectID, cfg.FCMCredentials)
	if err != nil {
		log.Fatalf("notify driver: %v", err)
	}
	log.Printf("notify driver: %T", sender)

	// FCM credentials are only meaningful for the fcm driver, but when that
	// driver IS selected the key file is load-bearing: fail closed at boot
	// (existence + no group/other access, i.e. 0600-style) instead of
	// starting a push driver that can never authenticate.
	if strings.EqualFold(strings.TrimSpace(os.Getenv("NOTIFY_DRIVER")), "fcm") {
		if err := checkFCMCredentialsFile(cfg.FCMCredentials); err != nil {
			log.Fatalf("fcm credentials: %v", err)
		}
	}

	SetDeviceDB(db)

	sessionStore, err := NewSessionStore(db, cfg.SessionSecret)
	if err != nil {
		log.Fatalf("session store: %v", err)
	}
	// Fail-closed escape hatch for plain-HTTP development (default stays
	// always-Secure; see Config.AllowInsecureCookies and
	// docs/setup.md "Plain-HTTP development").
	SetAllowInsecureSessionCookie(cfg.AllowInsecureCookies)
	csrf := webui.NewCSRFProtection(cfg.SessionSecret)
	webui.ExternalBaseURL = cfg.ExternalBaseURL
	if webui.ExternalBaseURL == "" {
		log.Printf("WARNING: EXTERNAL_BASE_URL unset — pairing QR falls back to request Host (dev-only)")
	}

	base := newBaseMux(DefaultWidgetRegistry(), sessionStore, csrf)
	// Same notify wiring as NewMux (kept inline: webui.RegisterRoutes below
	// needs the bare *http.ServeMux, while ListenAndServe takes the
	// no-store-wrapped handler).
	base.Handle("POST /api/v1/notify", notifyAdminChain(sessionStore, csrf, handleNotify(db, sender)))
	RegisterNotifyWebUIRoutes(base, db, sender, sessionStore, csrf)
	webui.RegisterRoutes(base, sessionStore, webui.Credentials{
		Username:     cfg.AdminUser,
		PasswordHash: cfg.AdminPasswordHash,
		Password:     cfg.AdminPassword,
	}, csrf)
	mux := withAuthNoStore(base)

	// P3 widgets: SIGHUP-triggered reload of $WIDGET_DIR (plus best-effort
	// mtime recheck per request; see widgets_load.go).
	StartWidgetSIGHUPReloader(DefaultWidgetRegistry())

	addr := ":" + cfg.Port
	log.Printf("phone-companion backend listening on %s (widgets: %s)", addr, cfg.WidgetDir)
	// No-store pinning lives inside NewMux (withAuthNoStore), so every
	// router — production and test — carries it. Do not wrap again here.
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}

// checkFCMCredentialsFile enforces the FCM credential contract at boot (fail
// closed): the service-account JSON must exist, be a regular file, and grant
// no access to group/other (0600 in production; 0400 also passes since it is
// strictly tighter — what matters is that no group/other bits are set).
func checkFCMCredentialsFile(path string) error {
	if strings.TrimSpace(path) == "" {
		return errors.New("FCM_CREDENTIALS_FILE must be set when NOTIFY_DRIVER=fcm")
	}
	fi, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("stat FCM_CREDENTIALS_FILE: %w", err)
	}
	if !fi.Mode().IsRegular() {
		return fmt.Errorf("FCM_CREDENTIALS_FILE %q is not a regular file", path)
	}
	if perm := fi.Mode().Perm(); perm&0o077 != 0 {
		return fmt.Errorf("FCM_CREDENTIALS_FILE %q has mode %04o (group/other access); want 0600", path, perm)
	}
	return nil
}
