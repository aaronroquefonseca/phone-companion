// Package main — single operator gate for POST /api/v1/notify and
// POST /api/v1/app-push.
//
// Exactly one chain guards both endpoints: a valid OPERATOR_API_TOKEN
// Bearer wins outright (machine credential, no write guard needed — CSRF
// proofs are for ambient cookies, not Bearers); ANY other Bearer value is
// 403 forbidden with no further lookup (device, revoked, or garbage — a
// presented credential never falls through to the session and never gets
// a DB oracle distinguishing which kind it was); otherwise the WebUI
// session is required (401 without one) plus the operator write guard
// (403 without X-Requested-With/CSRF token), same as the other operator
// POST routes. Runs inside RequireAdmin so unauthenticated callers still
// get 401 (auth first, CSRF second).
package main

import (
	"context"
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// operatorAPIToken is the machine operator credential from
// $OPERATOR_API_TOKEN (see Config). Wired once at boot via
// SetOperatorAPIToken; tests override it with cleanup. It is never
// logged and never returned in any response — audit rows record the
// fixed string "operator-token" instead.
var operatorAPIToken string

// SetOperatorAPIToken wires the operator API token for the operator chain.
// Callers must pass the validated Config value (Config.Validate refuses
// to boot on a missing/weak token); an empty value here fails closed
// downstream because validOperatorToken never matches it.
func SetOperatorAPIToken(tok string) {
	operatorAPIToken = tok
}

// operatorSessionUser is the WebUI admin login name from Config, used only
// to label session-authenticated audit rows ("session:<username>"). It
// carries no authority: authority comes from the session cookie itself.
var operatorSessionUser string

// SetOperatorSessionUser wires the admin username for audit labelling.
func SetOperatorSessionUser(name string) {
	operatorSessionUser = name
}

// operatorSessionLabel names a cookie-authenticated operator for audit
// rows. Never a secret: just the configured admin username (or "session"
// when unwired, e.g. tests that bypass main).
func operatorSessionLabel() string {
	if operatorSessionUser != "" {
		return "session:" + operatorSessionUser
	}
	return "session"
}

// operatorIdentityKey carries the resolved operator identity through the
// request context from the auth chain to the handler.
type operatorIdentityKey struct{}

// withOperatorIdentity stamps the operator identity onto the request.
func withOperatorIdentity(r *http.Request, id string) *http.Request {
	return r.WithContext(context.WithValue(r.Context(), operatorIdentityKey{}, id))
}

// operatorIdentity returns the identity stamped by the auth chain, or
// the session label when the chain authenticated via cookie.
func operatorIdentity(r *http.Request) string {
	return operatorIDFromContext(r.Context())
}

// operatorIDFromContext reads the chain-stamped operator identity, falling
// back to the session label. Handlers that only have a ctx (e.g. the
// shared fanoutNotify core, also reached from the WebUI form with no chain
// stamp) use this so every push record carries an operator label.
func operatorIDFromContext(ctx context.Context) string {
	if v, ok := ctx.Value(operatorIdentityKey{}).(string); ok && v != "" {
		return v
	}
	return operatorSessionLabel()
}

// bearerToken extracts an Authorization: Bearer value. It reports false
// for any other scheme (or an empty token) so non-Bearer credentials
// fall through to the session path.
func bearerToken(r *http.Request) (string, bool) {
	h := strings.TrimSpace(r.Header.Get("Authorization"))
	if h == "" {
		return "", false
	}
	scheme := h
	rest := ""
	if i := strings.IndexByte(h, ' '); i >= 0 {
		scheme, rest = h[:i], strings.TrimSpace(h[i+1:])
	}
	if !strings.EqualFold(scheme, "bearer") || rest == "" {
		return "", false
	}
	return rest, true
}

// validOperatorToken compares the presented Bearer value against the
// configured operator token. Both sides are SHA256-hashed first so the
// constant-time compare always sees fixed 32-byte inputs (a raw compare
// would short-circuit on length, leaking the expected length through
// timing). An unconfigured (empty) token never matches — fail closed.
func validOperatorToken(presented string) bool {
	if presented == "" || operatorAPIToken == "" {
		return false
	}
	p := sha256.Sum256([]byte(presented))
	s := sha256.Sum256([]byte(operatorAPIToken))
	return subtle.ConstantTimeCompare(p[:], s[:]) == 1
}

// operatorAdminChain is the single operator gate for POST /api/v1/notify
// and POST /api/v1/app-push. Phone enroll stays exempt (token-only auth,
// no session).
func operatorAdminChain(sessions webui.SessionStore, csrf *webui.CSRFProtection, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if tok, ok := bearerToken(r); ok {
			if validOperatorToken(tok) {
				next.ServeHTTP(w, withOperatorIdentity(r, "operator-token"))
				return
			}
			writeAPIError(w, http.StatusForbidden, "forbidden")
			return
		}
		webui.RequireAdmin(sessions, requireAPIWriteGuard(csrf, next)).ServeHTTP(w, r)
	})
}
