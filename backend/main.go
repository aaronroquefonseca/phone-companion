// Package main is the Phone Companion backend entrypoint.
//
// P0 BASE skeleton: wiring and contracts only. WebUI login/session is
// wired here; pairing, notifications, encryption, and widget logic are
// TODOs owned by later PRs.
package main

import (
	"log"
	"net/http"
	"os"

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

	SetDeviceDB(db)

	store := NewSessionStore(db, cfg.SessionSecret)
	mux := NewMux(db, sender, store)
	webui.RegisterRoutes(mux, store, webui.Credentials{
		Username:     cfg.AdminUser,
		PasswordHash: cfg.AdminPasswordHash,
		Password:     cfg.AdminPassword,
	})

	addr := ":" + cfg.Port
	log.Printf("phone-companion backend listening on %s (widgets: %s)", addr, cfg.WidgetDir)
	if err := http.ListenAndServe(addr, mux); err != nil {
		log.Fatal(err)
	}
}
