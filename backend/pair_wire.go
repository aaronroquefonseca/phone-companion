package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// RegisterPairingRoutes wires the P2 pairing surfaces with P1 admin gating.
//
//   - POST /api/v1/enroll stays on enroll-token auth ONLY (single-use QR
//     token consumed atomically). It MUST NOT require the admin session —
//     the phone has no session cookie.
//   - Every other route here is operator-only behind webui.RequireAdmin:
//     POST /api/v1/enroll-tokens (issue), GET /api/v1/devices (list),
//     DELETE /api/v1/devices/{id} (revoke), GET /webui/pair,
//     POST /webui/pair/revoke (CSRF-guarded form).
//
// Unauthenticated callers get 401 JSON on /api/* and a 302 to /login on
// /webui/*, per docs/api-contract.md.
func RegisterPairingRoutes(mux *http.ServeMux, sessions webui.SessionStore, csrf *webui.CSRFProtection) {
	if sessions == nil {
		sessions = denyAllSessions{}
	}
	mux.Handle("POST /api/v1/enroll", http.HandlerFunc(HandleEnroll))
	mux.Handle("POST /api/v1/enroll-tokens", webui.RequireAdmin(sessions, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowEnrollIssue(w, r) {
			return
		}
		HandleIssueEnrollToken(w, r)
	})))
	mux.Handle("GET /api/v1/devices", webui.RequireAdmin(sessions, http.HandlerFunc(HandleListDevices)))
	mux.Handle("DELETE /api/v1/devices/{id}", webui.RequireAdmin(sessions, http.HandlerFunc(HandleRevokeDevice)))

	store := webuiStoreAdapter{}
	mux.Handle("GET /webui/pair", webui.RequireAdmin(sessions, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowEnrollIssue(w, r) {
			return
		}
		webui.HandlePairPage(store, EnrollTokenTTL, csrf)(w, r)
	})))
	mux.Handle("POST /webui/pair/revoke", webui.RequireAdmin(sessions, webui.HandlePairRevoke(store, csrf)))
}

// denyAllSessions fails closed when RegisterPairingRoutes is miswired with
// a nil session store: every pairing operator route becomes unauthorized
// instead of open.
type denyAllSessions struct{}

func (denyAllSessions) CreateSession(_ http.ResponseWriter, _ *http.Request) error {
	return errors.New("session store not wired")
}
func (denyAllSessions) ValidateSession(_ *http.Request) bool                  { return false }
func (denyAllSessions) DestroySession(_ http.ResponseWriter, _ *http.Request) {}

// webuiStoreAdapter implements webui.Store against the main package's
// pairing store. It lives here (not in devices.go) to keep the webui glue
// in one place. Every method fails closed when deviceDB is nil so a
// miswired server returns 500 instead of panicking.
type webuiStoreAdapter struct{}

func (webuiStoreAdapter) IssueEnrollToken(_ context.Context, ttl time.Duration) (string, time.Time, error) {
	if deviceDB == nil {
		return "", time.Time{}, errors.New("device DB not wired")
	}
	return IssueEnrollToken(deviceDB, ttl)
}

func (webuiStoreAdapter) ListDevices(_ context.Context) ([]webui.DeviceInfo, error) {
	if deviceDB == nil {
		return nil, errors.New("device DB not wired")
	}
	devices, err := ListDevices(deviceDB)
	if err != nil {
		return nil, err
	}
	out := make([]webui.DeviceInfo, 0, len(devices))
	for _, d := range devices {
		out = append(out, webui.DeviceInfo{
			ID:        d.ID,
			Name:      d.Name,
			CreatedAt: d.CreatedAt,
			Revoked:   d.Revoked,
			RevokedAt: d.RevokedAt,
		})
	}
	return out, nil
}

func (webuiStoreAdapter) RevokeDevice(_ context.Context, id string) (bool, error) {
	if deviceDB == nil {
		return false, errors.New("device DB not wired")
	}
	return RevokeDevice(deviceDB, id)
}

// enrollIssueLimiter bounds token issuance (both the JSON API and the
// WebUI page mint single-use tokens): maxEnrollIssuesPerWindow per client
// IP per window. In-memory only — a restart clears it, which is safe
// because outstanding tokens live in the DB, not the limiter. The limit
// exists to stop QR-refresh loops and token-spray from filling the
// enroll_tokens table; legitimate pairing needs ~1 token per scan.
const (
	maxEnrollIssuesPerWindow = 30
	enrollIssueWindow        = time.Minute
)

var enrollIssueMu sync.Mutex
var enrollIssueByIP = map[string][]time.Time{}

// enrollIssueNow is overridden in tests for deterministic pruning.
var enrollIssueNow = time.Now

// allowEnrollIssue reports whether the caller may mint a token now. On
// exhaustion it writes 429 (JSON for /api/*, plain text otherwise) and
// returns false.
func allowEnrollIssue(w http.ResponseWriter, r *http.Request) bool {
	ip := enrollClientIP(r)
	now := enrollIssueNow()
	enrollIssueMu.Lock()
	stamps := enrollIssueByIP[ip]
	kept := stamps[:0]
	for _, t := range stamps {
		if now.Sub(t) < enrollIssueWindow {
			kept = append(kept, t)
		}
	}
	if len(kept) >= maxEnrollIssuesPerWindow {
		enrollIssueByIP[ip] = kept
		enrollIssueMu.Unlock()
		if len(r.URL.Path) >= 5 && r.URL.Path[:5] == "/api/" {
			writeAPIError(w, http.StatusTooManyRequests, "rate_limited")
		} else {
			http.Error(w, "rate_limited", http.StatusTooManyRequests)
		}
		return false
	}
	enrollIssueByIP[ip] = append(kept, now)
	enrollIssueMu.Unlock()
	return true
}

func enrollClientIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// resetEnrollIssueLimiter clears issuance history. Tests only.
func resetEnrollIssueLimiter() {
	enrollIssueMu.Lock()
	enrollIssueByIP = map[string][]time.Time{}
	enrollIssueMu.Unlock()
}
