// Package main — temporary admin gate for POST /api/v1/notify.
//
// TODO (Wave-2 P9 auth): replace requireAdminNotifyStub with the real
// WebUI-session check (backend/session.go RequireAdmin) once that PR lands
// on main. This stub is intentionally pass-through so P4 stands alone on
// P0; it MUST NOT ship past Wave-2 — every notify request needs an
// operator session, never a device token.
package main

import "net/http"

// requireAdminNotifyStub guards the notify handler until Wave-2 P9 provides
// real session auth. Uniquely named so the swap is a clean delete.
func requireAdminNotifyStub(next http.Handler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// TODO (Wave-2 P9): enforce WebUI admin session here; reject
		// missing/invalid credentials with 401 and valid-but-not-admin
		// with 403 per docs/api-contract.md auth schemes.
		next.ServeHTTP(w, r)
	}
}
