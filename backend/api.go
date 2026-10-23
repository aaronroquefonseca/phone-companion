// Package main — HTTP routes (P0 BASE skeleton + P3 widgets).
//
// Only GET /healthz and the widgets endpoints are implemented. The remaining
// /api/v1/* routes from docs/api-contract.md are registered as 501 stubs so
// the URL contract is pinned early; implementations land in later PRs.
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// NewMux builds the HTTP router. sessions gates the operator-only
// pairing routes (see pair_wire.go); POST /api/v1/enroll stays on
// enroll-token auth and never touches sessions.
func NewMux(db *sql.DB, sender notify.Sender, sessions webui.SessionStore, csrf *webui.CSRFProtection) *http.ServeMux {
	mux := NewMuxWithWidgets(DefaultWidgetRegistry(), sessions, csrf)
	// Implemented (notifications P4): POST /api/v1/notify
	mux.HandleFunc("POST /api/v1/notify", requireAdminNotifyStub(handleNotify(db, sender)))
	return mux
}

// NewMuxWithWidgets builds the HTTP router backed by a specific registry
// (used by tests to inject a temp-dir registry). A nil sessions store
// fails closed (denyAllSessions) so widget tests need no auth fixtures.
func NewMuxWithWidgets(reg *WidgetRegistry, sessions webui.SessionStore, csrf *webui.CSRFProtection) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", handleHealthz)

	// Pairing (P2+P8): enroll + device registry + revocation + WebUI.
	// See devices.go / pair_wire.go / webui/.
	RegisterPairingRoutes(mux, sessions, csrf)
	// P3 widgets: poll endpoint (device Bearer auth) + single-widget fetch
	// (additive v1 extension; envelope per docs/api-contract.md).
	mux.HandleFunc("GET /api/v1/widgets", reg.handleWidgetsList)
	mux.HandleFunc("GET /api/v1/widgets/{id}", reg.handleWidgetsGet)

	return mux
}

func handleHealthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

// notImplemented pins an API route before its PR lands.
func notImplemented(name string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotImplemented)
		_ = json.NewEncoder(w).Encode(map[string]string{
			"error": name + " not implemented (skeleton stub)",
		})
	}
}
