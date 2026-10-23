// Package main — admin gate for POST /api/v1/notify.
//
// Wave-2 P9: the temporary NOTIFY_UNAUTHENTICATED bypass is gone (deleted
// with notify_auth_stub.go). This endpoint is operator-only behind the WebUI
// session: webui.RequireAdmin rejects missing/invalid sessions with 401
// {"error":"unauthorized"} per docs/api-contract.md auth schemes.
//
// Device Bearer tokens never authorize here. A request carrying one is
// rejected with 403 {"error":"forbidden"} ("valid credential, not allowed"),
// even if it also carries a session, so a device credential can never be
// mistaken for operator intent.
package main

import (
	"net/http"
	"strings"

	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// rejectNotifyDeviceToken refuses any request carrying a device-style
// Authorization header before session auth runs. POST /api/v1/notify is an
// operator endpoint (WebUI session); device Bearer tokens belong to
// device-polled endpoints (GET /api/v1/widgets) and must never grant
// operator actions — fail closed with 403 per the contract's "never a
// device token" rule.
func rejectNotifyDeviceToken(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if h := strings.TrimSpace(r.Header.Get("Authorization")); h != "" {
			scheme := h
			if i := strings.IndexByte(h, ' '); i >= 0 {
				scheme = h[:i]
			}
			if strings.EqualFold(scheme, "bearer") {
				writeAPIError(w, http.StatusForbidden, "forbidden")
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// notifyAdminChain gates the notify handler: device tokens are rejected
// with 403 first, then the WebUI session is required (401 without one),
// then the operator write guard requires explicit non-browser intent
// (X-Requested-With or a session-bound CSRF token, same as enroll-tokens)
// so a bare session cookie alone cannot send cross-site. Runs inside
// RequireAdmin so unauthenticated callers still get 401 (auth first,
// CSRF second). Phone enroll stays exempt (token-only auth, no session).
func notifyAdminChain(sessions webui.SessionStore, csrf *webui.CSRFProtection, next http.Handler) http.Handler {
	return rejectNotifyDeviceToken(webui.RequireAdmin(sessions, requireAPIWriteGuard(csrf, next)))
}
