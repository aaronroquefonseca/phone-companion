package main

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// RegisterPairingRoutes wires the P2 pairing surfaces with P1 admin gating.
//
//   - POST /api/v1/enroll stays on enroll-token auth ONLY (single-use QR
//     token consumed atomically). It MUST NOT require the admin session —
//     the phone has no session cookie, and MUST NOT require the API write
//     guard below for the same reason.
//   - POST /api/v1/device/fcm-token is the same phone-credential class:
//     device Bearer auth ONLY (token backfill after onNewToken rotation or
//     widgets poll; see device_token.go). No session, no write guard.
//   - Every other route here is operator-only behind webui.RequireAdmin:
//     POST /api/v1/enroll-tokens (issue), GET /api/v1/devices (list),
//     DELETE /api/v1/devices/{id} (revoke), GET /webui/pair,
//     POST /webui/pair/revoke (CSRF-guarded form).
//   - POST /api/v1/enroll-tokens, DELETE /api/v1/devices/{id}, and
//     POST /api/v1/notify additionally require explicit
//     non-browser intent via requireAPIWriteGuard (X-Requested-With or
//     session-bound CSRF token) so a bare session cookie alone cannot mint
//     tokens, revoke devices, or send cross-site. Missing proof fails closed
//     with 403 JSON. Phone enroll (POST /api/v1/enroll) stays exempt:
//     token-only auth, no session, no guard.
//
// Unauthenticated callers get 401 JSON on /api/* and a 303 to /login on
// /webui/*, per docs/api-contract.md.
func RegisterPairingRoutes(mux *http.ServeMux, sessions webui.SessionStore, csrf *webui.CSRFProtection) {
	if sessions == nil {
		sessions = denyAllSessions{}
	}
	mux.Handle("POST /api/v1/enroll", http.HandlerFunc(HandleEnroll))
	mux.Handle("POST /api/v1/device/fcm-token", http.HandlerFunc(HandleUpdateFCMToken))
	mux.Handle("POST /api/v1/enroll-tokens", webui.RequireAdmin(sessions, requireAPIWriteGuard(csrf, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowEnrollIssue(w, r) {
			return
		}
		HandleIssueEnrollToken(w, r)
	}))))
	mux.Handle("GET /api/v1/devices", webui.RequireAdmin(sessions, http.HandlerFunc(HandleListDevices)))
	// Revoke is state-changing operator API: same write guard as
	// enroll-tokens (X-Requested-With or session-bound CSRF token).
	mux.Handle("DELETE /api/v1/devices/{id}", webui.RequireAdmin(sessions, requireAPIWriteGuard(csrf, http.HandlerFunc(HandleRevokeDevice))))

	store := webuiStoreAdapter{}
	mux.Handle("GET /webui/pair", webui.RequireAdmin(sessions, webui.HandlePairPage(store, EnrollTokenTTL, csrf)))
	// Minting is the rate-limited step (not page loads): the 30/min bucket
	// stays on this endpoint. Exhaustion renders the layout-wrapped 429
	// operator page (not bare text) with a Retry-After hint.
	mux.Handle("POST /webui/pair/mint", webui.RequireAdmin(sessions, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !allowEnrollMintPage(w, r, csrf) {
			return
		}
		webui.HandlePairMint(store, EnrollTokenTTL, csrf)(w, r)
	})))
	mux.Handle("POST /webui/pair/revoke", webui.RequireAdmin(sessions, webui.HandlePairRevoke(store, csrf)))
	mux.Handle("POST /webui/pair/purge", webui.RequireAdmin(sessions, webui.HandlePairPurge(store, csrf)))
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

func (webuiStoreAdapter) PurgeDevice(_ context.Context, id string) (bool, error) {
	if deviceDB == nil {
		return false, errors.New("device DB not wired")
	}
	return PurgeDevice(deviceDB, id)
}

// apiWriteHeader is the conventional non-browser marker required on
// state-changing operator API POSTs. Fetch/XHR clients send it; a
// cross-site form cannot set it without a CORS preflight, which browsers
// block without explicit opt-in. The session-bound CSRF token is accepted
// as an alternative so non-fetch operator tooling with access to the
// WebUI token still works.
const (
	apiWriteHeader      = "X-Requested-With"
	apiWriteHeaderValue = "XMLHttpRequest"
)

// validAPIWriteRequest reports whether a state-changing operator API
// request carries explicit intent: either the X-Requested-With header or
// a session-bound CSRF token (webui VerifyRequest: csrf_token form field
// or X-CSRF-Token header). Local on purpose: webui.VerifyRequest's
// signature is owned by the parallel auth batch — DO NOT move this into
// webui or change webui.VerifyRequest.
func validAPIWriteRequest(r *http.Request, csrf *webui.CSRFProtection) bool {
	if r == nil {
		return false
	}
	if strings.EqualFold(strings.TrimSpace(r.Header.Get(apiWriteHeader)), apiWriteHeaderValue) {
		return true
	}
	if csrf != nil && csrf.VerifyRequest(r) {
		return true
	}
	return false
}

// requireAPIWriteGuard fails closed with 403 JSON when neither proof is
// present. It runs INSIDE webui.RequireAdmin so unauthenticated callers
// still get 401 (auth first, CSRF second).
func requireAPIWriteGuard(csrf *webui.CSRFProtection, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !validAPIWriteRequest(r, csrf) {
			writeAPIError(w, http.StatusForbidden, "forbidden")
			return
		}
		next.ServeHTTP(w, r)
	})
}

// enrollIssueLimiter bounds token issuance (both the JSON API and the
// WebUI page mint single-use tokens): maxEnrollIssuesPerWindow per client
// IP per window. In-memory only — a restart clears it, which is safe
// because outstanding tokens live in the DB, not the limiter. The limit
// exists to stop QR-refresh loops and token-spray from filling the
// enroll_tokens table; legitimate pairing needs ~1 token per scan.
//
// The map itself is bounded (maxEnrollLimiterIPs): entries whose stamps
// all expired are deleted on sight, and a full map is swept then
// LRU-evicted (oldest last activity) so a distinct-IP spray cannot grow
// memory without bound while a live bucket still limits.
const (
	maxEnrollIssuesPerWindow = 30
	enrollIssueWindow        = time.Minute
	maxEnrollLimiterIPs      = 10000
)

var enrollIssueMu sync.Mutex
var enrollIssueByIP = map[string][]time.Time{}

// enrollIssueNow is overridden in tests for deterministic pruning.
var enrollIssueNow = time.Now

// takeEnrollIssueSlot consumes one token-mint slot for the caller's IP.
// Reports (allowed, retryAfterSeconds). Pure bucket operation — no response
// written; callers map exhaustion onto their surface (JSON 429 for /api/*,
// layout-wrapped 429 page for /webui/*).
func takeEnrollIssueSlot(r *http.Request) (bool, int) {
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
	_, exists := enrollIssueByIP[ip]
	if len(kept) == 0 {
		// No live stamps: drop the empty bucket so idle IPs do not
		// linger. The current request re-creates the entry below.
		if exists {
			delete(enrollIssueByIP, ip)
			exists = false
		}
		kept = nil
	}
	if len(kept) >= maxEnrollIssuesPerWindow {
		enrollIssueByIP[ip] = kept
		enrollIssueMu.Unlock()
		retry := int((enrollIssueWindow - now.Sub(kept[0]) + time.Second - 1) / time.Second)
		if retry < 1 {
			retry = 1
		}
		return false, retry
	}
	if !exists {
		// New bucket: make room first so the map stays bounded under
		// distinct-IP spray.
		if len(enrollIssueByIP) >= maxEnrollLimiterIPs {
			evictEnrollLimiterLocked(now)
		}
		if len(enrollIssueByIP) >= maxEnrollLimiterIPs {
			// Sweep found nothing expirable (all buckets live): evict
			// one entry to preserve the bound; correctness (per-IP
			// limiting for live buckets) is unaffected.
			for k := range enrollIssueByIP {
				delete(enrollIssueByIP, k)
				break
			}
		}
	}
	enrollIssueByIP[ip] = append(kept, now)
	enrollIssueMu.Unlock()
	return true, 0
}

// allowEnrollIssue reports whether the caller may mint a token now. On
// exhaustion it writes 429 (JSON for /api/*, plain text otherwise) and
// returns false. API flavor; the WebUI mint page uses allowEnrollMintPage
// (layout-wrapped 429) instead.
func allowEnrollIssue(w http.ResponseWriter, r *http.Request) bool {
	ok, retry := takeEnrollIssueSlot(r)
	if ok {
		return true
	}
	w.Header().Set("Retry-After", strconv.Itoa(retry))
	if len(r.URL.Path) >= 5 && r.URL.Path[:5] == "/api/" {
		writeAPIError(w, http.StatusTooManyRequests, "rate_limited")
	} else {
		http.Error(w, "rate_limited", http.StatusTooManyRequests)
	}
	return false
}

// allowEnrollMintPage gates POST /webui/pair/mint with the shared issuance
// bucket. On exhaustion it renders the layout-wrapped operator 429 page
// (nav/chrome kept, Retry-After set) instead of bare text.
func allowEnrollMintPage(w http.ResponseWriter, r *http.Request, csrf *webui.CSRFProtection) bool {
	ok, retry := takeEnrollIssueSlot(r)
	if ok {
		return true
	}
	w.Header().Set("Retry-After", strconv.Itoa(retry))
	webui.WriteOperatorError(w, r, csrf, true, "pairing", http.StatusTooManyRequests,
		"Too many pairing tokens", "Too many pairing tokens issued — please wait a minute and try again (rate_limited).",
		"/webui/pair", "Back to pairing")
	return false
}

// evictEnrollLimiterLocked frees one slot. Caller must hold enrollIssueMu.
// It first sweeps expired stamps (deleting buckets that go empty), then —
// only if still at cap — evicts the bucket with the oldest last activity
// (LRU-ish: legitimate bursty IPs keep recent stamps, spray IPs are old).
func evictEnrollLimiterLocked(now time.Time) {
	for ip, stamps := range enrollIssueByIP {
		kept := stamps[:0]
		for _, t := range stamps {
			if now.Sub(t) < enrollIssueWindow {
				kept = append(kept, t)
			}
		}
		if len(kept) == 0 {
			delete(enrollIssueByIP, ip)
		} else if len(kept) != len(stamps) {
			enrollIssueByIP[ip] = kept
		}
	}
	if len(enrollIssueByIP) < maxEnrollLimiterIPs {
		return
	}
	var oldestIP string
	var oldestLast time.Time
	first := true
	for ip, stamps := range enrollIssueByIP {
		last := time.Time{}
		for _, t := range stamps {
			if t.After(last) {
				last = t
			}
		}
		if first || last.Before(oldestLast) {
			oldestIP, oldestLast = ip, last
			first = false
		}
	}
	if oldestIP != "" {
		delete(enrollIssueByIP, oldestIP)
	}
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
