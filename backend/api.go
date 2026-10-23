// Package main — HTTP routes (health check + pairing + widgets + notify).
//
// Implemented: GET /healthz, the pairing/device-registry/WebUI routes (see
// devices.go / pair_wire.go / webui/), the P3 widgets endpoints, and
// POST /api/v1/notify (operator-only). Further /api/v1/* routes from
// docs/api-contract.md land in their own PRs.
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// NewMux builds the HTTP router. sessions backs the operator gates for
// pairing (see pair_wire.go) and POST /api/v1/notify (WebUI session via
// webui.RequireAdmin, 401 without one; device Bearer tokens are rejected
// with 403 before auth runs — see notify_auth.go). POST /api/v1/enroll
// stays on enroll-token auth and never touches sessions.
//
// The returned handler already includes the auth no-store pin
// (withAuthNoStore): credential-bearing paths are uncacheable on every
// router built here, including the test routers — main.go must NOT add a
// second layer.
func NewMux(db *sql.DB, sender notify.Sender, sessions webui.SessionStore, csrf *webui.CSRFProtection) http.Handler {
	mux := newBaseMux(DefaultWidgetRegistry(), sessions, csrf)
	// Implemented (notifications P9): POST /api/v1/notify is operator-only
	// (WebUI session). 202 {"notification_id","accepted"}: accepted counts
	// successful per-device sends; accepted:0 still returns 202 and means
	// no devices matched (unknown device_ids, or an empty registry) — not
	// an error. See handleNotify.
	mux.Handle("POST /api/v1/notify", notifyAdminChain(sessions, csrf, handleNotify(db, sender)))
	// Operator test-notification form (same fan-out pipeline as the API).
	RegisterNotifyWebUIRoutes(mux, db, sender, sessions, csrf)
	return withAuthNoStore(mux)
}

// NewMuxWithWidgets builds the HTTP router backed by a specific registry
// (used by tests to inject a temp-dir registry). A nil sessions store
// fails closed (denyAllSessions) so widget tests need no auth fixtures.
// Like NewMux, the returned handler carries the auth no-store pin.
//
// Operator surface here: dashboard + widgets preview (registry-backed).
// The notify test form is intentionally absent: it needs a database and a
// Sender, which this constructor does not take — use NewMux for it.
func NewMuxWithWidgets(reg *WidgetRegistry, sessions webui.SessionStore, csrf *webui.CSRFProtection) http.Handler {
	return withAuthNoStore(newBaseMux(reg, sessions, csrf))
}

// newBaseMux assembles the bare *http.ServeMux so the exported constructors
// (and same-package tests needing extra routes, e.g. the e2e harness) can
// wrap it with withAuthNoStore exactly once.
func newBaseMux(reg *WidgetRegistry, sessions webui.SessionStore, csrf *webui.CSRFProtection) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", handleHealthz)

	// Operator dashboard (login-gated home). Exact-path only inside the
	// handler: the "GET /" pattern is a catch-all, and non-"/" paths 404.
	if sessions == nil {
		sessions = denyAllSessions{}
	}
	mux.Handle("GET /", webui.RequireAdmin(sessions, webui.HandleDashboard(webuiStoreAdapter{}, webuiWidgetAdapter{reg}, csrf)))
	mux.Handle("GET /webui/widgets", webui.RequireAdmin(sessions, webui.HandleWidgetsPage(webuiWidgetAdapter{reg}, csrf)))

	// Pairing (P2+P8): enroll + device registry + revocation + WebUI.
	// See devices.go / pair_wire.go / webui/.
	RegisterPairingRoutes(mux, sessions, csrf)
	// P3 widgets: poll endpoint (device Bearer auth) + single-widget fetch
	// (additive v1 extension; envelope per docs/api-contract.md).
	mux.HandleFunc("GET /api/v1/widgets", withWidgetCachePolicy(reg.handleWidgetsList))
	mux.HandleFunc("GET /api/v1/widgets/{id}", withWidgetCachePolicy(reg.handleWidgetsGet))

	return mux
}

func handleHealthz(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]string{"status": "ok"})
}

// authNoStore reports whether a path carries credentials or single-use
// tokens and must never be cached. The login/logout handlers set no-store
// themselves (see webui/login.go, session.go); this middleware covers the
// enroll and pair endpoints owned by other files (devices.go, pair_wire.go,
// webui/pair.go) without editing them — batch C owns api.go while those
// files belong to parallel batches.
func authNoStore(path string) bool {
	if path == "/login" || path == "/logout" {
		return true
	}
	if path == "/api/v1/enroll" || path == "/api/v1/enroll-tokens" {
		return true
	}
	// Operator WebUI: the dashboard home plus every page under /webui/
	// (pairing, widgets preview, notify form) all carry session or
	// single-use-token state.
	if path == "/" || strings.HasPrefix(path, "/webui/") {
		return true
	}
	return false
}

// withAuthNoStore sets Cache-Control: no-store on credential-bearing paths.
// Handlers that set their own Cache-Control (widgets) overwrite this later,
// which is intended: this middleware only upgrades endpoints that set none.
func withAuthNoStore(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if authNoStore(r.URL.Path) {
			w.Header().Set("Cache-Control", "no-store")
		}
		next.ServeHTTP(w, r)
	})
}

// widgetCacheCapture is a buffering ResponseWriter so the cache policy can
// inspect the served envelope before headers go out.
type widgetCacheCapture struct {
	header http.Header
	body   []byte
	status int
}

func (c *widgetCacheCapture) Header() http.Header { return c.header }
func (c *widgetCacheCapture) Write(b []byte) (int, error) {
	c.body = append(c.body, b...)
	return len(b), nil
}
func (c *widgetCacheCapture) WriteHeader(s int) { c.status = s }

// withWidgetCachePolicy enforces the widget cache contract without touching
// widgets.go (owned by a parallel batch): non-secret envelopes keep the
// handler's `private, max-age=60, must-revalidate`; any response carrying a
// secret envelope is downgraded to `private, no-store, must-revalidate`
// because its ciphertext is minted fresh per request and must never sit in
// a cache. Detection is a body substring match for the compact-JSON marker
// `"type":"secret"`; over-matching only ever tightens caching (safe
// direction), and non-200 responses pass through untouched.
func withWidgetCachePolicy(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		rec := &widgetCacheCapture{header: http.Header{}}
		next(rec, r)
		status := rec.status
		if status == 0 {
			status = http.StatusOK
		}
		for k, vs := range rec.header {
			for _, v := range vs {
				w.Header().Add(k, v)
			}
		}
		if status == http.StatusOK && strings.Contains(string(rec.body), `"type":"secret"`) {
			w.Header().Set("Cache-Control", "private, no-store, must-revalidate")
		}
		w.WriteHeader(status)
		_, _ = w.Write(rec.body)
	}
}
