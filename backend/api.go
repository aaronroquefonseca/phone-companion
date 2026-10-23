// Package main — HTTP routes (P0 BASE skeleton).
//
// Only GET /healthz is implemented. The /api/v1/* routes from
// docs/api-contract.md are registered as 501 stubs so the URL contract is
// pinned early; implementations land in later PRs.
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// NewMux builds the HTTP router. sessions backs the operator gate for
// POST /api/v1/notify (WebUI session via webui.RequireAdmin, 401 without
// one; device Bearer tokens are rejected with 403 before auth runs — see
// notify_auth.go).
func NewMux(db *sql.DB, sender notify.Sender, sessions webui.SessionStore) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", handleHealthz)

	// Pairing (P2): enroll + device registry + revocation + WebUI.
	// See devices.go / pair_wire.go / webui/.
	RegisterPairingRoutes(mux)
	// TODO (widgets PR): GET /api/v1/widgets
	mux.HandleFunc("GET /api/v1/widgets", notImplemented("widgets"))
	// Implemented (notifications P9): POST /api/v1/notify is operator-only
	// (WebUI session). 202 {"notification_id","accepted"}: accepted counts
	// successful per-device sends; accepted:0 still returns 202 and means
	// no devices matched (unknown device_ids, or an empty registry) — not
	// an error. See handleNotify.
	mux.Handle("POST /api/v1/notify", notifyAdminChain(sessions, handleNotify(db, sender)))

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
