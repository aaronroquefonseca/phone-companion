// Package main — temporary admin gate for POST /api/v1/notify.
//
// TODO (Wave-2 P9 auth): replace requireAdminNotifyStub with the real
// WebUI-session check (backend/session.go RequireAdmin) once that PR lands
// on main. This stub is intentionally pass-through so P4 stands alone on
// P0; it MUST NOT ship past Wave-2 — every notify request needs an
// operator session, never a device token.
package main

import (
	"encoding/json"
	"net/http"
	"os"
)

// requireAdminNotifyStub guards the notify handler until Wave-2 P9 provides
// real session auth. Uniquely named so the swap is a clean delete.
//
// Fail-closed default: requests are rejected with 403 unless the explicit
// escape hatch NOTIFY_UNAUTHENTICATED=1 is set (dev/CI/verification only —
// P9 real session auth must land before/with any real FCM send, and this
// flag must never be set in production).
func requireAdminNotifyStub(next http.Handler) http.HandlerFunc {
	open := os.Getenv("NOTIFY_UNAUTHENTICATED") == "1"
	return func(w http.ResponseWriter, r *http.Request) {
		// TODO (Wave-2 P9): enforce WebUI admin session here; reject
		// missing/invalid credentials with 401 and valid-but-not-admin
		// with 403 per docs/api-contract.md auth schemes.
		if !open {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusForbidden)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "forbidden"})
			return
		}
		next.ServeHTTP(w, r)
	}
}
