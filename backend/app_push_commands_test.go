// Package main — GET /api/v1/commands + POST /api/v1/commands/ack tests.
//
// Covers: the device auth matrix (401 missing/invalid/revoked, 403
// operator token), the enqueue → poll → ack lifecycle, targeted vs all,
// revoked exclusion, unknown/other-device ack 404, other-device command
// invisibility, caps/TTL pruning, re-ack idempotence, and the exact flat
// command shape shared with the FCM variant.
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// nowPlus renders formatTime(now+delta) for seeding rows.
func nowPlus(d time.Duration) time.Time {
	return time.Now().Add(d)
}

// seedAppPushPending inserts n pending rows for deviceID under one parent
// push, stamping created_at. Callers pass formatTime(nowPlus(...)) — a
// past stamp simulates aged rows for TTL tests.
func seedAppPushPending(t *testing.T, db *sql.DB, pushID, deviceID string, n int, createdAt string) {
	t.Helper()
	if _, err := db.Exec(
		`INSERT OR IGNORE INTO app_pushes
			(push_id, created_at, operator, target, apk_url, sha256, package, version, label, accepted)
			VALUES (?, ?, 'test', '{"all":true}', ?, ?, 'com.example.app', '1.0', 'Seed', ?)`,
		pushID, createdAt, appPushTestURL, appPushTestSHA, n); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < n; i++ {
		id, err := newNotificationID()
		if err != nil {
			t.Fatal(err)
		}
		if _, err := db.Exec(
			`INSERT INTO app_push_commands
				(command_id, push_id, device_id, apk_url, sha256, package, version, label, created_at, acked_at)
				VALUES (?, ?, ?, ?, ?, 'com.example.seed', '9.9', 'Seed', ?, NULL)`,
			id, pushID, deviceID, appPushTestURL, appPushTestSHA, createdAt); err != nil {
			t.Fatal(err)
		}
	}
}

func getCommands(t *testing.T, mux http.Handler, auth string, cookie *http.Cookie) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/commands", nil)
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	if cookie != nil {
		req.AddCookie(cookie)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func postCommandsAck(t *testing.T, mux http.Handler, body string, auth string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/commands/ack", strings.NewReader(body))
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

// decodeCommands returns the command list from a 200 poll body.
func decodeCommands(t *testing.T, w *httptest.ResponseRecorder) []map[string]any {
	t.Helper()
	if w.Code != http.StatusOK {
		t.Fatalf("poll: status %d, want 200 (%s)", w.Code, w.Body.String())
	}
	var got struct {
		Commands []map[string]any `json:"commands"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("poll: non-JSON body %q: %v", w.Body.String(), err)
	}
	if got.Commands == nil {
		t.Fatalf("poll: commands is null, want []")
	}
	return got.Commands
}

func TestCommandsAuthMatrix(t *testing.T) {
	db := openAppPushTestDB(t)
	mux, store := openAppPushMux(t, db, &captureSender{})
	cookie := loginCookie(t, store)
	idA, tokA := mustEnrollDevice(t, db, "a", "fcm-cmd-a")

	// Valid device Bearer → 200 (empty queue).
	w := getCommands(t, mux, "Bearer "+tokA, nil)
	cmds := decodeCommands(t, w)
	if len(cmds) != 0 {
		t.Fatalf("fresh device: %d commands, want 0", len(cmds))
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store, private" {
		t.Fatalf("Cache-Control = %q, want no-store, private", cc)
	}

	// Session cookie alongside a device Bearer is ignored (still 200).
	if w := getCommands(t, mux, "Bearer "+tokA, cookie); w.Code != http.StatusOK {
		t.Fatalf("device+session: status %d, want 200 (%s)", w.Code, w.Body.String())
	}

	cases := []struct {
		name string
		auth string
	}{
		{"no auth", ""},
		{"garbage bearer", "Bearer garbage-token"},
		{"unknown device bearer", "Bearer " + strings.Repeat("d", 43)},
		{"empty bearer", "Bearer "},
	}
	for _, tc := range cases {
		if w := getCommands(t, mux, tc.auth, nil); w.Code != http.StatusUnauthorized {
			t.Errorf("%s poll: status %d, want 401 (%s)", tc.name, w.Code, w.Body.String())
		}
		if w := postCommandsAck(t, mux, `{"command_id":"x"}`, tc.auth); w.Code != http.StatusUnauthorized {
			t.Errorf("%s ack: status %d, want 401 (%s)", tc.name, w.Code, w.Body.String())
		}
	}

	// Revoked device Bearer → 401 on both endpoints.
	if _, err := RevokeDevice(db, idA); err != nil {
		t.Fatal(err)
	}
	if w := getCommands(t, mux, "Bearer "+tokA, nil); w.Code != http.StatusUnauthorized {
		t.Errorf("revoked poll: status %d, want 401 (%s)", w.Code, w.Body.String())
	}
	if w := postCommandsAck(t, mux, `{"command_id":"x"}`, "Bearer "+tokA); w.Code != http.StatusUnauthorized {
		t.Errorf("revoked ack: status %d, want 401 (%s)", w.Code, w.Body.String())
	}

	// Operator token → 403 on both endpoints (valid credential, wrong surface).
	for _, tc := range []struct {
		name string
		run  func() *httptest.ResponseRecorder
	}{
		{"poll", func() *httptest.ResponseRecorder { return getCommands(t, mux, "Bearer "+appPushTestToken, nil) }},
		{"ack", func() *httptest.ResponseRecorder {
			return postCommandsAck(t, mux, `{"command_id":"x"}`, "Bearer "+appPushTestToken)
		}},
	} {
		if w := tc.run(); w.Code != http.StatusForbidden {
			t.Errorf("operator token %s: status %d, want 403 (%s)", tc.name, w.Code, w.Body.String())
		} else if code := errorCode(t, w); code != "forbidden" {
			t.Errorf("operator token %s: error %q, want forbidden", tc.name, code)
		}
	}

	// Session cookie alone (no Bearer) → 401, never operator access.
	if w := getCommands(t, mux, "", cookie); w.Code != http.StatusUnauthorized {
		t.Errorf("session-only poll: status %d, want 401 (%s)", w.Code, w.Body.String())
	}
}

// Full lifecycle: enqueue (targeted) → poll shows the exact flat command
// shape → ack → poll empty → re-ack idempotent.
func TestCommandsLifecycle(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, tokA := mustEnrollDevice(t, db, "a", "fcm-life-a")
	mustEnrollDevice(t, db, "b", "fcm-life-b")
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	w := postAppPush(t, mux, validAppPushBody(`{"device_ids":[`+jsonQuote(idA)+`]}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("enqueue: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := decodePush202(t, w)
	if pushID == "" || accepted != 1 {
		t.Fatalf("enqueue: push_id=%q accepted=%v, want id + 1", pushID, accepted)
	}

	cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil))
	if len(cmds) != 1 {
		t.Fatalf("poll: %d commands, want 1", len(cmds))
	}
	cmd := cmds[0]
	want := map[string]string{
		"type":    "app_install",
		"push_id": pushID,
		"apk_url": appPushTestURL,
		"sha256":  appPushTestSHA,
		"package": "com.example.app",
		"version": "1.2.3",
		"label":   "Test App",
	}
	if len(cmd) != len(want)+1 {
		t.Fatalf("command keys %v, want exactly %v + command_id", cmd, want)
	}
	cmdID, _ := cmd["command_id"].(string)
	if cmdID == "" {
		t.Fatalf("command_id missing in %v", cmd)
	}
	if !isLowerHex(cmdID) || len(cmdID) != 32 {
		t.Fatalf("command_id %q is not a 128-bit hex id", cmdID)
	}
	for k, v := range want {
		if cmd[k] != v {
			t.Fatalf("command[%q] = %v, want %q (full: %v)", k, cmd[k], v, cmd)
		}
	}

	// Ack → poll drains.
	aw := postCommandsAck(t, mux, `{"command_id":`+jsonQuote(cmdID)+`}`, "Bearer "+tokA)
	if aw.Code != http.StatusOK {
		t.Fatalf("ack: status %d, want 200 (%s)", aw.Code, aw.Body.String())
	}
	var ackGot map[string]bool
	if err := json.Unmarshal(aw.Body.Bytes(), &ackGot); err != nil || !ackGot["acked"] {
		t.Fatalf("ack body %q, want {\"acked\":true}", aw.Body.String())
	}
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 0 {
		t.Fatalf("post-ack poll: %d commands, want 0", len(cmds))
	}

	// Re-ack is idempotent (still 200, not 404).
	if w := postCommandsAck(t, mux, `{"command_id":`+jsonQuote(cmdID)+`}`, "Bearer "+tokA); w.Code != http.StatusOK {
		t.Fatalf("re-ack: status %d, want 200 (%s)", w.Code, w.Body.String())
	}
}

// Absent version rides as an empty flat string (shape stays stable).
func TestCommandsAbsentVersionEmptyString(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, tokA := mustEnrollDevice(t, db, "a", "fcm-ver-a")
	mux, _ := openAppPushMux(t, db, &captureSender{})

	body := `{"target":{"device_ids":[` + jsonQuote(idA) + `]},"apk_url":` + jsonQuote(appPushTestURL) +
		`,"sha256":` + jsonQuote(appPushTestSHA) + `,"package":"com.example.app","label":"No version"}`
	if w := postAppPush(t, mux, body, nil, "Bearer "+appPushTestToken, false); w.Code != http.StatusAccepted {
		t.Fatalf("enqueue: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil))
	if len(cmds) != 1 {
		t.Fatalf("poll: %d commands, want 1", len(cmds))
	}
	v, present := cmds[0]["version"]
	if !present || v != "" {
		t.Fatalf("version = %v (present=%v), want present empty string", v, present)
	}
}

// Targeted vs all: all queues per-device rows for every live device;
// device_ids queues only for the named devices; each device polls only
// its own commands.
func TestCommandsTargetedVsAll(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, tokA := mustEnrollDevice(t, db, "a", "fcm-tva-a")
	_, tokB := mustEnrollDevice(t, db, "b", "fcm-tva-b")
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	w := postAppPush(t, mux, validAppPushBody(`{"device_ids":[`+jsonQuote(idA)+`]}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("targeted enqueue: status %d (%s)", w.Code, w.Body.String())
	}
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 1 {
		t.Fatalf("targeted: device A polls %d, want 1", len(cmds))
	}
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokB, nil)); len(cmds) != 0 {
		t.Fatalf("targeted: device B polls %d, want 0", len(cmds))
	}

	w = postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, auth, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("all enqueue: status %d (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 2 {
		t.Fatalf("all: accepted=%v, want 2", accepted)
	}
	// A now holds targeted(acked? no — still pending) + all = 2; B holds all = 1.
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 2 {
		t.Fatalf("all: device A polls %d, want 2", len(cmds))
	}
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokB, nil)); len(cmds) != 1 {
		t.Fatalf("all: device B polls %d, want 1", len(cmds))
	}
}

// Revoked devices resolve to nothing: enqueue-all skips them, and their
// own poll 401s (AuthenticateDevice).
func TestCommandsSkipsRevoked(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, _ := mustEnrollDevice(t, db, "a", "fcm-rev-a")
	_, tokB := mustEnrollDevice(t, db, "b", "fcm-rev-b")
	if _, err := RevokeDevice(db, idA); err != nil {
		t.Fatal(err)
	}
	mux, _ := openAppPushMux(t, db, &captureSender{})

	w := postAppPush(t, mux, validAppPushBody(`{"all":true}`), nil, "Bearer "+appPushTestToken, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("enqueue: status %d (%s)", w.Code, w.Body.String())
	}
	if _, accepted := decodePush202(t, w); accepted != 1 {
		t.Fatalf("accepted=%v, want 1 (revoked excluded)", accepted)
	}
	if n := appPushPendingCount(t, db, idA); n != 0 {
		t.Fatalf("revoked device holds %d rows, want 0", n)
	}
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokB, nil)); len(cmds) != 1 {
		t.Fatalf("live device polls %d, want 1", len(cmds))
	}
}

// Ack of an unknown id, or of another device's id, is 404 with the same
// code (no oracle across devices).
func TestCommandsAckNotFound(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, tokA := mustEnrollDevice(t, db, "a", "fcm-ack-a")
	_, tokB := mustEnrollDevice(t, db, "b", "fcm-ack-b")
	mux, _ := openAppPushMux(t, db, &captureSender{})
	auth := "Bearer " + appPushTestToken

	if w := postAppPush(t, mux, validAppPushBody(`{"device_ids":[`+jsonQuote(idA)+`]}`), nil, auth, false); w.Code != http.StatusAccepted {
		t.Fatalf("enqueue: status %d (%s)", w.Code, w.Body.String())
	}
	cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil))
	if len(cmds) != 1 {
		t.Fatalf("poll: %d commands, want 1", len(cmds))
	}
	cmdID, _ := cmds[0]["command_id"].(string)

	// Other device's id → 404.
	if w := postCommandsAck(t, mux, `{"command_id":`+jsonQuote(cmdID)+`}`, "Bearer "+tokB); w.Code != http.StatusNotFound {
		t.Fatalf("other-device ack: status %d, want 404 (%s)", w.Code, w.Body.String())
	} else if code := errorCode(t, w); code != "command_not_found" {
		t.Fatalf("other-device ack: error %q, want command_not_found", code)
	}
	// The row is untouched: the owner still polls it.
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 1 {
		t.Fatalf("owner poll after foreign ack: %d commands, want 1", len(cmds))
	}

	// Unknown id → same 404, same code.
	if w := postCommandsAck(t, mux, `{"command_id":"00000000000000000000000000000000"}`, "Bearer "+tokA); w.Code != http.StatusNotFound {
		t.Fatalf("unknown ack: status %d, want 404 (%s)", w.Code, w.Body.String())
	} else if code := errorCode(t, w); code != "command_not_found" {
		t.Fatalf("unknown ack: error %q, want command_not_found", code)
	}

	// Malformed bodies are 400.
	for _, body := range []string{`{`, ``, `{}`, `{"command_id":"  "}`, `{"command_id":42}`} {
		if w := postCommandsAck(t, mux, body, "Bearer "+tokA); w.Code != http.StatusBadRequest {
			t.Errorf("body %q: status %d, want 400 (%s)", body, w.Code, w.Body.String())
		}
	}
}

// Expired rows are never served and are pruned on poll; the enqueue-side
// prune drops them before the cap check.
func TestCommandsTTLPrune(t *testing.T) {
	db := openAppPushTestDB(t)
	idA, tokA := mustEnrollDevice(t, db, "a", "fcm-ttl-a")
	mux, _ := openAppPushMux(t, db, &captureSender{})

	old := formatTime(nowPlus(-8 * 24 * time.Hour))
	seedAppPushPending(t, db, "ttl-push", idA, 2, old)
	if n := appPushPendingCount(t, db, idA); n != 2 {
		t.Fatalf("seeded %d rows, want 2", n)
	}

	// Poll serves nothing and prunes the expired rows.
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 0 {
		t.Fatalf("poll served %d expired commands, want 0", len(cmds))
	}
	if n := appPushPendingCount(t, db, idA); n != 0 {
		t.Fatalf("poll left %d expired rows, want 0", n)
	}

	// Enqueue-side prune likewise collects expired rows (and they never
	// count toward the cap): seed a full budget of expired rows, then
	// enqueue — must be 202, not 429.
	seedAppPushPending(t, db, "ttl-push-2", idA, maxAppPushPendingPerDevice, old)
	w := postAppPush(t, mux, validAppPushBody(`{"device_ids":[`+jsonQuote(idA)+`]}`), nil, "Bearer "+appPushTestToken, false)
	if w.Code != http.StatusAccepted {
		t.Fatalf("enqueue past expired budget: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if n := appPushPendingCount(t, db, idA); n != 1 {
		t.Fatalf("device holds %d rows after prune+enqueue, want 1", n)
	}
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 1 {
		t.Fatalf("poll after prune+enqueue: %d commands, want 1", len(cmds))
	}
}

// TTL boundary: rows newer than 7 days are still served.
func TestCommandsTTLFreshRowsServed(t *testing.T) {
	db := openAppPushTestDB(t)
	_, tokA := mustEnrollDevice(t, db, "a", "fcm-ttl-fresh")
	idA := ""
	if err := db.QueryRow(`SELECT id FROM devices WHERE fcm_token = 'fcm-ttl-fresh'`).Scan(&idA); err != nil {
		t.Fatal(err)
	}
	mux, _ := openAppPushMux(t, db, &captureSender{})

	seedAppPushPending(t, db, "ttl-fresh", idA, 1, formatTime(nowPlus(-6*24*time.Hour)))
	if cmds := decodeCommands(t, getCommands(t, mux, "Bearer "+tokA, nil)); len(cmds) != 1 {
		t.Fatalf("6-day-old row: %d commands, want 1", len(cmds))
	}
}
