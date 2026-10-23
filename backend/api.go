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

// NewMux builds the HTTP router. sessions backs the operator gates for
// pairing (see pair_wire.go) and POST /api/v1/notify (WebUI session via
// webui.RequireAdmin, 401 without one; device Bearer tokens are rejected
// with 403 before auth runs — see notify_auth.go). POST /api/v1/enroll
// stays on enroll-token auth and never touches sessions.
func NewMux(db *sql.DB, sender notify.Sender, sessions webui.SessionStore, csrf *webui.CSRFProtection) *http.ServeMux {
	mux := NewMuxWithWidgets(DefaultWidgetRegistry(), sessions, csrf)
	// Implemented (notifications P9): POST /api/v1/notify is operator-only
	// (WebUI session). 202 {"notification_id","accepted"}: accepted counts
	// successful per-device sends; accepted:0 still returns 202 and means
	// no devices matched (unknown device_ids, or an empty registry) — not
	// an error. See handleNotify.
	mux.Handle("POST /api/v1/notify", notifyAdminChain(sessions, handleNotify(db, sender)))
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
