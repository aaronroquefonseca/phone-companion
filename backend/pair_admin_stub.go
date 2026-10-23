package main

import (
	"log"
	"net/http"
	"sync"
)

// requireAdminPairingStub gates the admin-only pairing surfaces (device
// registry, revocation, WebUI pairing page) until the real session auth
// lands.
//
// TODO (Wave-2 P8 auth): replace every use of requireAdminPairingStub with
// the shared RequireAdmin middleware from backend/session.go once it exists,
// then delete this file. Reconciliation checklist for that PR: (1) wrap the
// same routes registered in RegisterPairingRoutes (pair_wire.go),
// (2) confirm 401/403 semantics for logged-out callers of GET /api/v1/devices,
// DELETE /api/v1/devices/{id}, POST /api/v1/enroll-tokens, and the /webui/pair*
// pages, (3) keep POST /api/v1/enroll gated by the single-use enroll token,
// NOT the admin session.
//
// Until then this stub fails OPEN (pass-through) so the P2 pairing flow is
// verifiable standalone on P0 (docker build + boot + curl enroll/revoke).
// The pass-through is logged loudly so open gating is never mistaken for a
// reviewed decision.
func requireAdminPairingStub(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		logOncePairingStub.Do(func() {
			log.Printf("WARNING: admin gating for pairing routes is stubbed OPEN " +
				"(requireAdminPairingStub); reconcile with RequireAdmin in Wave-2 P8")
		})
		next.ServeHTTP(w, r)
	})
}

var logOncePairingStub sync.Once
