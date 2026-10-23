// Package main is the Phone Companion backend entrypoint.
//
// P0 BASE skeleton: wiring and contracts only. Auth, pairing,
// notifications, encryption, and widget logic are TODOs owned by later PRs.
package main

import (
	"log"
	"net/http"
)

func main() {
	cfg := LoadConfig()

	// TODO (pairing PR): fail fast on missing/insecure required secrets.
	db, err := OpenDB(cfg.DBPath)
	if err != nil {
		log.Fatalf("open database: %v", err)
	}
	defer db.Close()
	if err := Migrate(db); err != nil {
		log.Fatalf("migrate database: %v", err)
	}

	// P3 widgets: SIGHUP-triggered reload of $WIDGET_DIR (plus best-effort
	// mtime recheck per request; see widgets.go).
	StartWidgetSIGHUPReloader(DefaultWidgetRegistry())

	addr := ":" + cfg.Port
	log.Printf("phone-companion backend listening on %s (widgets: %s)", addr, cfg.WidgetDir)
	if err := http.ListenAndServe(addr, NewMux()); err != nil {
		log.Fatal(err)
	}
}
