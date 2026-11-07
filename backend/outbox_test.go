// Package main — device outbox endpoint tests: auth matrix for all three
// device endpoints, wait/poll/ack lifecycle, revoked isolation, and the
// notify-path outbox lifecycle (the second half of "both POST endpoints"
// coverage; the app-push half lives in app_push_test.go).
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// gitLsFiles runs `git ls-files -z` in dir and returns the command so
// callers can read NUL-separated tracked paths from its output.
func gitLsFiles(dir string) *exec.Cmd {
	cmd := exec.Command("git", "ls-files", "-z")
	cmd.Dir = dir
	return cmd
}

// openOutboxMux is openAuthedMux with single-connection :memory: semantics
// (all queries share one sqlite memory DB, so concurrent long-poll
// goroutines see the same rows) plus the outbox routes from NewMux.
func openOutboxMux(t *testing.T, sender notify.Sender) (http.Handler, *sql.DB, *SessionStore) {
	t.Helper()
	db := openNotifyTestDB(t)
	db.SetMaxOpenConns(1)
	secret := strings.Repeat("s", 32)
	store, err := NewSessionStore(db, secret)
	if err != nil {
		t.Fatal(err)
	}
	return NewMux(db, sender, store, webui.NewCSRFProtection(secret)), db, store
}

// deviceReq issues one device-endpoint request with an optional Bearer.
func deviceReq(mux http.Handler, method, path, body, bearer string) *httptest.ResponseRecorder {
	var r *http.Request
	if body == "" {
		r = httptest.NewRequest(method, path, nil)
	} else {
		r = httptest.NewRequest(method, path, strings.NewReader(body))
	}
	if bearer != "" {
		r.Header.Set("Authorization", "Bearer "+bearer)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, r)
	return w
}

// pollAs returns the decoded commands for a device Bearer (asserts 200).
func pollAs(t *testing.T, mux http.Handler, bearer string) []map[string]any {
	t.Helper()
	w := deviceReq(mux, http.MethodGet, "/api/v1/outbox", "", bearer)
	if w.Code != http.StatusOK {
		t.Fatalf("poll: status %d, want 200 (%s)", w.Code, w.Body.String())
	}
	var got struct {
		Commands []map[string]any `json:"commands"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("poll: bad JSON: %v (%s)", err, w.Body.String())
	}
	if got.Commands == nil {
		t.Fatal("poll: commands must be [] when empty, never null")
	}
	return got.Commands
}

// waitAs returns the decoded events for a device Bearer (asserts 200).
func waitAs(t *testing.T, mux http.Handler, bearer string) []string {
	t.Helper()
	w := deviceReq(mux, http.MethodGet, "/api/v1/events/wait", "", bearer)
	if w.Code != http.StatusOK {
		t.Fatalf("wait: status %d, want 200 (%s)", w.Code, w.Body.String())
	}
	var got struct {
		Events []string `json:"events"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("wait: bad JSON: %v (%s)", err, w.Body.String())
	}
	if got.Events == nil {
		t.Fatal("wait: events must be [] on timeout, never null")
	}
	return got.Events
}

// ackAs acks ids as a device Bearer and returns the acked count.
func ackAs(t *testing.T, mux http.Handler, bearer string, ids []string) int {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"ids": ids})
	w := deviceReq(mux, http.MethodPost, "/api/v1/commands/ack", string(body), bearer)
	if w.Code != http.StatusOK {
		t.Fatalf("ack: status %d, want 200 (%s)", w.Code, w.Body.String())
	}
	var got struct {
		Acked int `json:"acked"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("ack: bad JSON: %v", err)
	}
	return got.Acked
}

// Auth matrix for all three device endpoints: valid device Bearer → 200;
// missing/invalid/revoked Bearer → 401; operator token → 403 (operator
// credentials never act as a device).
func TestDeviceEndpointAuthMatrix(t *testing.T) {
	mux, db, store := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")
	tokRev := addOutboxDevice(t, db, "dev-R")
	if _, err := RevokeDevice(db, "dev-R"); err != nil {
		t.Fatal(err)
	}
	const opToken = "device-matrix-op-token-0123456789abcdef"
	useOperatorToken(t, opToken)
	sessionCookie := loginCookie(t, store)

	// The matrix hits GET /api/v1/events/wait with a valid device (which
	// would otherwise hold the full 300s production timeout): cap it.
	old := outboxWaitTimeout
	outboxWaitTimeout = 50 * time.Millisecond
	t.Cleanup(func() { outboxWaitTimeout = old; resetOutboxWaiters() })

	paths := []struct {
		method, path, body string
	}{
		{http.MethodGet, "/api/v1/outbox", ""},
		{http.MethodGet, "/api/v1/events/wait", ""},
		{http.MethodPost, "/api/v1/commands/ack", `{"ids":[]}`},
	}
	for _, p := range paths {
		name := p.method + " " + p.path
		if w := deviceReq(mux, p.method, p.path, p.body, ""); w.Code != http.StatusUnauthorized {
			t.Errorf("%s no creds: status %d, want 401 (%s)", name, w.Code, w.Body.String())
		}
		if w := deviceReq(mux, p.method, p.path, p.body, "garbage-token"); w.Code != http.StatusUnauthorized {
			t.Errorf("%s garbage bearer: status %d, want 401 (%s)", name, w.Code, w.Body.String())
		}
		// A WebUI session cookie is meaningless on device endpoints: no
		// Bearer still means 401 (cookies never authorize devices).
		req := httptest.NewRequest(p.method, p.path, strings.NewReader(p.body))
		req.AddCookie(sessionCookie)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != http.StatusUnauthorized {
			t.Errorf("%s session cookie, no bearer: status %d, want 401 (%s)", name, w.Code, w.Body.String())
		}
		if w := deviceReq(mux, p.method, p.path, p.body, tokRev); w.Code != http.StatusUnauthorized {
			t.Errorf("%s revoked device: status %d, want 401 (%s)", name, w.Code, w.Body.String())
		}
		if w := deviceReq(mux, p.method, p.path, p.body, opToken); w.Code != http.StatusForbidden {
			t.Errorf("%s operator token: status %d, want 403 (%s)", name, w.Code, w.Body.String())
		}
		if w := deviceReq(mux, p.method, p.path, p.body, tokA); w.Code != http.StatusOK {
			t.Errorf("%s valid device: status %d, want 200 (%s)", name, w.Code, w.Body.String())
		}
	}
}

// Poll returns ONLY the caller's rows and pins the no-store, private
// cache contract.
func TestOutboxPollIsolationAndHeaders(t *testing.T) {
	mux, db, _ := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")
	tokB := addOutboxDevice(t, db, "dev-B")

	w := postAppPush(t, db, notify.NoopSender{}, validAppPushBody("dev-A"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("push: status %d (%s)", w.Code, w.Body.String())
	}

	cmds := pollAs(t, mux, tokA)
	if len(cmds) != 1 || cmds[0]["type"] != "app_install" {
		t.Fatalf("dev-A poll: %v, want 1 app_install command", cmds)
	}
	if cmds := pollAs(t, mux, tokB); len(cmds) != 0 {
		t.Fatalf("dev-B poll: %v, want none (isolation)", cmds)
	}

	// Header contract, byte-for-shape.
	req := httptest.NewRequest(http.MethodGet, "/api/v1/outbox", nil)
	req.Header.Set("Authorization", "Bearer "+tokA)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if cc := rec.Header().Get("Cache-Control"); cc != "no-store, private" {
		t.Fatalf("Cache-Control %q, want %q", cc, "no-store, private")
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("Content-Type %q, want application/json", ct)
	}
}

// Wait returns immediately when pending rows exist, else holds to the
// timeout and answers empty (short timeouts in tests — never real 300s).
func TestOutboxWaitImmediateAndTimeout(t *testing.T) {
	mux, db, _ := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")
	addOutboxDevice(t, db, "dev-B")

	old := outboxWaitTimeout
	outboxWaitTimeout = 80 * time.Millisecond
	t.Cleanup(func() { outboxWaitTimeout = old; resetOutboxWaiters() })

	// Nothing pending: blocks to the timeout, then [].
	start := time.Now()
	if events := waitAs(t, mux, tokA); len(events) != 0 {
		t.Fatalf("empty wait: events %v, want []", events)
	}
	if elapsed := time.Since(start); elapsed > 10*time.Second {
		t.Fatalf("empty wait took %v (timeout not honored?)", elapsed)
	}

	// Pending row: immediate ["outbox"].
	w := postAppPush(t, db, notify.NoopSender{}, validAppPushBody("dev-A"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("push: status %d (%s)", w.Code, w.Body.String())
	}
	start = time.Now()
	if events := waitAs(t, mux, tokA); len(events) != 1 || events[0] != "outbox" {
		t.Fatalf("pending wait: events %v, want [outbox]", events)
	}
	if elapsed := time.Since(start); elapsed >= outboxWaitTimeout {
		t.Fatalf("pending wait took %v, must return before the timeout", elapsed)
	}
}

// waitRaw issues a raw wait request (goroutine-safe: no t assertions).
func waitRaw(mux http.Handler, bearer string) (int, []string) {
	req := httptest.NewRequest(http.MethodGet, "/api/v1/events/wait", nil)
	req.Header.Set("Authorization", "Bearer "+bearer)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	var got struct {
		Events []string `json:"events"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &got)
	return rec.Code, got.Events
}

// Wait wakes mid-hold when an enqueue lands for the waiting device: the
// wake path (signal fed ONLY by the enqueue, no DB connection held while
// blocked) round-trips through real HTTP + SQL.
func TestOutboxWaitWake(t *testing.T) {
	mux, db, store := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")
	cookie := loginCookie(t, store)

	old := outboxWaitTimeout
	outboxWaitTimeout = 5 * time.Second
	t.Cleanup(func() { outboxWaitTimeout = old; resetOutboxWaiters() })

	type result struct {
		events []string
		code   int
	}
	done := make(chan result, 1)
	go func() {
		code, events := waitRaw(mux, tokA)
		done <- result{events, code}
	}()

	// Let the waiter register + block, then enqueue for it via the real
	// operator endpoint (session path exercises the shared chain).
	time.Sleep(150 * time.Millisecond)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(validAppPushBody("dev-A")))
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("push: status %d (%s)", rec.Code, rec.Body.String())
	}

	select {
	case r := <-done:
		if r.code != http.StatusOK || len(r.events) != 1 || r.events[0] != "outbox" {
			t.Fatalf("wake: code=%d events=%v, want 200 [outbox]", r.code, r.events)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("waiter never woke after enqueue")
	}
}

// A waiter for device A is NOT woken by a push targeting only device B
// (per-device signals), and a push to a revoked-only target queues
// nothing at all.
func TestOutboxWaitIsolationAndRevoked(t *testing.T) {
	mux, db, store := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")
	tokB := addOutboxDevice(t, db, "dev-B")
	addOutboxDevice(t, db, "dev-R")
	if _, err := RevokeDevice(db, "dev-R"); err != nil {
		t.Fatal(err)
	}
	cookie := loginCookie(t, store)

	old := outboxWaitTimeout
	outboxWaitTimeout = 150 * time.Millisecond
	t.Cleanup(func() { outboxWaitTimeout = old; resetOutboxWaiters() })

	pushVia := func(body string) (int, string) {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(body))
		req.Header.Set("X-Requested-With", "XMLHttpRequest")
		req.AddCookie(cookie)
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		if rec.Code != http.StatusAccepted {
			t.Fatalf("push: status %d (%s)", rec.Code, rec.Body.String())
		}
		id, _ := pushAcceptedOf(t, rec)
		return rec.Code, id
	}

	// Push to B while A waits: A times out empty.
	done := make(chan []string, 1)
	go func() {
		_, events := waitRaw(mux, tokA)
		done <- events
	}()
	time.Sleep(50 * time.Millisecond)
	pushVia(validAppPushBody("dev-B"))
	select {
	case events := <-done:
		if len(events) != 0 {
			t.Fatalf("A woken by B's push: %v", events)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("waiter never returned")
	}

	// Revoked-only target: accepted 0, nothing queued anywhere.
	req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(validAppPushBody("dev-R")))
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusAccepted {
		t.Fatalf("revoked push: status %d (%s)", rec.Code, rec.Body.String())
	}
	if _, accepted := pushAcceptedOf(t, rec); accepted != 0 {
		t.Fatalf("revoked push: accepted=%v, want 0", accepted)
	}
	for _, tc := range []struct {
		tok  string
		want int
	}{{tokA, 0}, {tokB, 1}} {
		if cmds := pollAs(t, mux, tc.tok); len(cmds) != tc.want {
			t.Fatalf("poll after B-push: %d commands, want %d", len(cmds), tc.want)
		}
	}
}

// Ack semantics: malformed bodies are 400; empty ids 200 acked:0; ack
// counts owned pending rows only; expired ids are ignored like unknown
// ones; the ack endpoint never leaks other devices' rows.
func TestOutboxAckSemantics(t *testing.T) {
	mux, db, _ := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")

	for _, body := range []string{`{`, `{"ids":"x"}`, `{"ids":[` + strings.Repeat(`"x",`, 1001) + `"x"]}`} {
		w := deviceReq(mux, http.MethodPost, "/api/v1/commands/ack", body, tokA)
		if w.Code != http.StatusBadRequest {
			t.Fatalf("ack %q: status %d, want 400 (%s)", body, w.Code, w.Body.String())
		}
	}
	if n := ackAs(t, mux, tokA, nil); n != 0 {
		t.Fatalf("ack empty: acked=%d, want 0", n)
	}
	if n := ackAs(t, mux, tokA, []string{"nope"}); n != 0 {
		t.Fatalf("ack unknown: acked=%d, want 0", n)
	}
	// null ids decode to empty: 200 acked:0, never 400.
	w := deviceReq(mux, http.MethodPost, "/api/v1/commands/ack", `{"ids":null}`, tokA)
	if w.Code != http.StatusOK {
		t.Fatalf("ack null ids: status %d, want 200 (%s)", w.Code, w.Body.String())
	}
	// Expired-but-unacked rows are ignored like unknown ids (the poll
	// would have pruned them; ack must not resurrect them as received).
	if _, err := db.Exec(
		`INSERT INTO outbox (command_id, push_id, device_id, type, payload_json, created_at, expires_at, status)
			VALUES ('expired-ack-cmd', 'expired-ack-push', 'dev-A', 'app_install', '{}', ?, ?, 'pending')`,
		formatTime(utcNow().Add(-8*24*time.Hour)), formatTime(utcNow().Add(-time.Hour))); err != nil {
		t.Fatal(err)
	}
	if n := ackAs(t, mux, tokA, []string{"expired-ack-cmd"}); n != 0 {
		t.Fatalf("ack expired: acked=%d, want 0", n)
	}
}

// Notify-path outbox lifecycle (second half of "both POST endpoints"):
// targeted notify queues per-device notify rows with the stored data map,
// waiters wake, and the pushes audit row carries the operator identity.
func TestNotifyOutboxLifecycle(t *testing.T) {
	mux, db, store := openOutboxMux(t, notify.NoopSender{})
	tokA := addOutboxDevice(t, db, "dev-A")
	tokB := addOutboxDevice(t, db, "dev-B")
	cookie := loginCookie(t, store)
	const opToken = "notify-lifecycle-op-token-0123456789abcdef"
	useOperatorToken(t, opToken)

	notifyVia := func(body, bearer string, xrw bool) (string, float64) {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/notify", strings.NewReader(body))
		if xrw {
			req.Header.Set("X-Requested-With", "XMLHttpRequest")
		}
		if bearer != "" {
			if bearer == "op" {
				req.Header.Set("Authorization", "Bearer "+opToken)
			} else {
				req.Header.Set("Authorization", "Bearer "+bearer)
			}
		} else {
			req.AddCookie(cookie)
		}
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != http.StatusAccepted {
			t.Fatalf("notify: status %d (%s)", w.Code, w.Body.String())
		}
		accepted, nid := acceptedOf(t, w)
		return nid, accepted
	}

	// Operator-token path labels the pushes row "operator-token".
	nid, accepted := notifyVia(`{"target":{"device_ids":["dev-A","dev-B"]},"title":"Hi","body":"Yo"}`, "op", false)
	if accepted != 2 || nid == "" {
		t.Fatalf("notify: accepted=%v id=%q, want 2 + id", accepted, nid)
	}
	if _, operator, _, n := pushesAuditRow(t, db, nid); operator != "operator-token" || n != 2 {
		t.Fatalf("pushes row operator=%q accepted=%d, want operator-token/2", operator, n)
	}
	cmds := pollAs(t, mux, tokA)
	if len(cmds) != 1 || cmds[0]["type"] != "notify" || cmds[0]["title"] != "Hi" {
		t.Fatalf("dev-A poll: %v, want 1 notify command with title", cmds)
	}
	if cmds[0]["notification_id"] != nid {
		t.Fatalf("notify row must carry notification_id %q, got %v", nid, cmds)
	}
	if cmds := pollAs(t, mux, tokB); len(cmds) != 1 {
		t.Fatalf("dev-B poll: %d commands, want 1", len(cmds))
	}

	// Session path wakes a waiter: start waiting on a drained device.
	drained := pollAs(t, mux, tokB)
	if len(drained) != 1 {
		t.Fatalf("dev-B poll: %d commands, want 1", len(drained))
	}
	if n := ackAs(t, mux, tokB, []string{drained[0]["command_id"].(string)}); n != 1 {
		t.Fatalf("drain dev-B: acked=%d, want 1", n)
	}
	old := outboxWaitTimeout
	outboxWaitTimeout = 5 * time.Second
	t.Cleanup(func() { outboxWaitTimeout = old; resetOutboxWaiters() })
	done := make(chan []string, 1)
	go func() {
		_, events := waitRaw(mux, tokB)
		done <- events
	}()
	time.Sleep(150 * time.Millisecond)
	if _, accepted := notifyVia(`{"target":{"device_ids":["dev-B"]},"title":"Wake","body":"up"}`, "", true); accepted != 1 {
		t.Fatalf("wake notify: accepted=%v, want 1", accepted)
	}
	select {
	case events := <-done:
		if len(events) != 1 || events[0] != "outbox" {
			t.Fatalf("waiter events %v, want [outbox]", events)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("waiter never woke on notify enqueue")
	}
}

// FCM-unconfigured servers still 202: LogSender/empty-driver notify and
// app-push need no FCM_PROJECT_ID, no credentials file, no network.
func TestFCMUnconfiguredStill202(t *testing.T) {
	for _, sender := range []notify.Sender{notify.LogSender{}, notify.NoopSender{}} {
		mux, db, store := openOutboxMux(t, sender)
		addOutboxDevice(t, db, "dev-A")
		cookie := loginCookie(t, store)

		// openNotifyTestDB seeds dev-1 alongside our dev-A: target
		// explicitly so the count is exact.
		req := httptest.NewRequest(http.MethodPost, "/api/v1/notify",
			strings.NewReader(`{"target":{"device_ids":["dev-A"]},"title":"Hi","body":"Yo"}`))
		req.Header.Set("X-Requested-With", "XMLHttpRequest")
		req.AddCookie(cookie)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != http.StatusAccepted {
			t.Fatalf("%T notify: status %d, want 202 (%s)", sender, w.Code, w.Body.String())
		}
		if accepted, _ := acceptedOf(t, w); accepted != 1 {
			t.Fatalf("%T notify: accepted=%v, want 1", sender, accepted)
		}

		req = httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(validAppPushBody("dev-A")))
		req.Header.Set("X-Requested-With", "XMLHttpRequest")
		req.AddCookie(cookie)
		w = httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != http.StatusAccepted {
			t.Fatalf("%T app-push: status %d, want 202 (%s)", sender, w.Code, w.Body.String())
		}
	}
}

// No Google services in the tree: the poll-based delivery must not depend
// on (or accidentally vendor) a google-services.json anywhere. Scoped to
// git-tracked files on purpose: a developer's own gitignored local config
// (needed to build FCM flavors locally) must not fail this test — only
// a vendored copy would.
func TestNoGoogleServicesJSON(t *testing.T) {
	root, err := filepath.Abs("..")
	if err != nil {
		t.Fatal(err)
	}
	found := trackedGoogleServicesJSON(t, root)
	if len(found) != 0 {
		t.Fatalf("google-services.json must not be vendored in the tree: %v", found)
	}
}

// trackedGoogleServicesJSON lists repo-tracked files named
// google-services.json (any directory). Untracked or gitignored local
// copies are the developer's own and are not the test's business.
func trackedGoogleServicesJSON(t *testing.T, root string) []string {
	t.Helper()
	cmd := gitLsFiles(root)
	out, err := cmd.Output()
	if err != nil {
		t.Fatalf("git ls-files failed: %v", err)
	}
	var found []string
	for _, f := range strings.Split(string(out), "\x00") {
		if f == "" {
			continue
		}
		if filepath.Base(f) == "google-services.json" {
			found = append(found, f)
		}
	}
	return found
}

// deviceLogID exposes at most an 8-hex-char prefix: stable, lowercase hex,
// distinct per device, never the full id.
func TestDeviceLogIDPrefix(t *testing.T) {
	a, b := deviceLogID("dev-A"), deviceLogID("dev-B")
	for _, s := range []string{a, b} {
		if len(s) != 8 {
			t.Fatalf("deviceLogID length = %d, want 8", len(s))
		}
		for _, c := range s {
			if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
				t.Fatalf("deviceLogID %q not lowercase hex", s)
			}
		}
	}
	if a == b {
		t.Fatal("deviceLogID collides for distinct devices")
	}
	if strings.Contains(a, "dev-A") {
		t.Fatal("deviceLogID must not contain the raw id")
	}
}

// canonicalPayloadJSON is deterministic: key order never depends on map
// iteration, so stored bytes are stable for equal maps.
func TestCanonicalPayloadDeterministic(t *testing.T) {
	m := map[string]string{"z": "1", "a": "2", "m": "3"}
	if got := canonicalPayloadJSON(m); got != `{"a":"2","m":"3","z":"1"}` {
		t.Fatalf("canonical JSON = %q, want sorted keys", got)
	}
	if got := canonicalTargetJSON(true, []string{"x"}); got != `{"all":true}` {
		t.Fatalf("canonical target all = %q", got)
	}
	if got := canonicalTargetJSON(false, nil); got != `{"device_ids":[]}` {
		t.Fatalf("canonical target empty = %q", got)
	}
}
