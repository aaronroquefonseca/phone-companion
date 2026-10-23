// Package main — HTTP routes (P0 BASE skeleton).
//
// Only GET /healthz is implemented. The /api/v1/* routes from
// docs/api-contract.md are registered as 501 stubs so the URL contract is
// pinned early; implementations land in later PRs.
package main

import (
	"encoding/json"
	"net/http"
)

// NewMux builds the HTTP router.
func NewMux() *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", handleHealthz)

	// Pairing (P2): enroll + device registry + revocation + WebUI.
	// See devices.go / pair_wire.go / webui/.
	RegisterPairingRoutes(mux)
	// TODO (widgets PR): GET /api/v1/widgets
	mux.HandleFunc("GET /api/v1/widgets", notImplemented("widgets"))
	// TODO (notifications PR): POST /api/v1/notify
	mux.HandleFunc("POST /api/v1/notify", notImplemented("notify"))

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
