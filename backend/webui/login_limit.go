package webui

import (
	"net"
	"net/http"
	"strconv"
	"sync"
	"time"
)

// Login rate limiting (batch C hardening). Same token-bucket shape as the
// enroll-issuance limiter in the main package (see pair_wire.go
// allowEnrollIssue): at most maxLoginsPerWindow POST /login attempts per
// client IP per loginWindow, in-memory only. A restart clears the buckets,
// which is safe because outstanding sessions live in the DB, not the
// limiter. The limit exists to bound bcrypt work per IP: verify() runs a
// bcrypt comparison on every attempt, so unbounded POST /login is a remote
// CPU amplifier. Exhaustion answers 429 with a Retry-After hint (seconds
// until the oldest attempt in the window ages out).
const (
	maxLoginsPerWindow = 10
	loginWindow        = time.Minute
	// maxLoginLimiterIPs caps distinct client IPs tracked. Without a cap a
	// pre-auth spray (one attempt per spoofed/source IP) grows the map
	// without bound — a worse surface than the enroll limiter it mirrors.
	// Past the cap, insert sweeps expired entries, drops empties, then
	// evicts the least-recently-active bucket, all under loginMu.
	maxLoginLimiterIPs = 1024
)

var loginMu sync.Mutex
var loginByIP = map[string][]time.Time{}

// loginNow is overridden in tests for deterministic window expiry.
var loginNow = time.Now

// allowLogin reports whether the caller may attempt a login now. On
// exhaustion it writes 429 + Retry-After and returns false; callers must
// stop without touching bcrypt.
func allowLogin(w http.ResponseWriter, r *http.Request) bool {
	ip := loginClientIP(r)
	now := loginNow()
	loginMu.Lock()
	if _, ok := loginByIP[ip]; !ok && len(loginByIP) >= maxLoginLimiterIPs {
		sweepLoginBucketsLocked(now)
		if len(loginByIP) >= maxLoginLimiterIPs {
			evictOldestLoginBucketLocked()
		}
	}
	stamps := loginByIP[ip]
	kept := stamps[:0]
	for _, t := range stamps {
		if now.Sub(t) < loginWindow {
			kept = append(kept, t)
		}
	}
	if len(kept) >= maxLoginsPerWindow {
		loginByIP[ip] = kept
		loginMu.Unlock()
		retry := int((loginWindow - now.Sub(kept[0]) + time.Second - 1) / time.Second)
		if retry < 1 {
			retry = 1
		}
		w.Header().Set("Retry-After", strconv.Itoa(retry))
		http.Error(w, "rate_limited", http.StatusTooManyRequests)
		return false
	}
	if len(kept) == 0 {
		// Fully-expired bucket: drop the empty shell instead of retaining
		// it, so idle IPs never pin map slots between windows. The append
		// below recreates the entry.
		delete(loginByIP, ip)
	}
	loginByIP[ip] = append(kept, now)
	loginMu.Unlock()
	return true
}

// sweepLoginBucketsLocked prunes expired attempts from every bucket and
// drops buckets left empty. Call with loginMu held.
func sweepLoginBucketsLocked(now time.Time) {
	for ip, stamps := range loginByIP {
		kept := stamps[:0]
		for _, t := range stamps {
			if now.Sub(t) < loginWindow {
				kept = append(kept, t)
			}
		}
		if len(kept) == 0 {
			delete(loginByIP, ip)
		} else {
			loginByIP[ip] = kept
		}
	}
}

// evictOldestLoginBucketLocked drops the least-recently-active bucket. Stamps
// are appended in arrival order (loginNow is monotonic in production), so
// the last stamp is the newest. Call with loginMu held, only at cap.
func evictOldestLoginBucketLocked() {
	var (
		victim     string
		victimLast time.Time
		found      bool
	)
	for ip, stamps := range loginByIP {
		if len(stamps) == 0 {
			delete(loginByIP, ip)
			return
		}
		if last := stamps[len(stamps)-1]; !found || last.Before(victimLast) {
			victim, victimLast, found = ip, last, true
		}
	}
	if found {
		delete(loginByIP, victim)
	}
}

func loginClientIP(r *http.Request) string {
	if host, _, err := net.SplitHostPort(r.RemoteAddr); err == nil {
		return host
	}
	return r.RemoteAddr
}

// resetLoginLimiter clears login attempt history. Tests only.
func resetLoginLimiter() {
	loginMu.Lock()
	loginByIP = map[string][]time.Time{}
	loginMu.Unlock()
}
