// Package main — POST /api/v1/app-push tests: validation matrix (every
// 422 + boundary values), operator auth, and the two-device lifecycle.
package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

// validAppPushBody renders a minimal valid app-push body retargeted at ids.
func validAppPushBody(ids ...string) string {
	target := `{"all":true}`
	if ids != nil {
		quoted := make([]string, 0, len(ids))
		for _, id := range ids {
			quoted = append(quoted, fmt.Sprintf("%q", id))
		}
		target = `{"device_ids":[` + strings.Join(quoted, ",") + `]}`
	}
	return `{"target":` + target +
		`,"apk_url":"https://example.com/app.apk"` +
		`,"sha256":"` + strings.Repeat("ab", 32) + `"` +
		`,"package":"com.example.app","version":"1.0","label":"Example"}`
}

// postAppPush calls handleAppPush directly (no auth gate): unit-level
// status + code assertions. Mux-level auth lives in TestAppPushAuthMatrix.
func postAppPush(t *testing.T, db *sql.DB, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(body))
	w := httptest.NewRecorder()
	handleAppPush(db)(w, req)
	return w
}

// pushAcceptedOf decodes the 202 {"push_id","accepted"} body.
func pushAcceptedOf(t *testing.T, w *httptest.ResponseRecorder) (string, float64) {
	t.Helper()
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("non-JSON 202 body: %q", w.Body.String())
	}
	id, _ := got["push_id"].(string)
	accepted, _ := got["accepted"].(float64)
	return id, accepted
}

// outboxStatuses returns the statuses of one (push, device) fan-out, in
// row order. The delivery leg is asserted here, not on sends.
func idsJSON(ids []string) string {
	quoted := make([]string, 0, len(ids))
	for _, id := range ids {
		quoted = append(quoted, fmt.Sprintf("%q", id))
	}
	return strings.Join(quoted, ",")
}

// outboxStatuses returns the statuses of one (push, device) fan-out, in
// row order. The delivery leg is asserted here, not on sends.
func outboxStatuses(t *testing.T, db *sql.DB, pushID, deviceID string) []string {
	t.Helper()
	rows, err := db.Query(
		`SELECT status FROM outbox WHERE push_id = ? AND device_id = ? ORDER BY id`,
		pushID, deviceID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatal(err)
		}
		out = append(out, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return out
}

// outboxPayload returns the stored flat map for one (push, device) row.
func outboxPayload(t *testing.T, db *sql.DB, pushID, deviceID string) map[string]string {
	t.Helper()
	var raw string
	if err := db.QueryRow(
		`SELECT payload_json FROM outbox WHERE push_id = ? AND device_id = ?`,
		pushID, deviceID).Scan(&raw); err != nil {
		t.Fatalf("outbox row missing for push/device: %v", err)
	}
	var m map[string]string
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		t.Fatalf("payload_json not a flat map: %v", err)
	}
	return m
}

// outboxPendingCountFor is the test-visible pollable depth for a device.
func outboxPendingCountFor(t *testing.T, db *sql.DB, deviceID string) int {
	t.Helper()
	var n int
	if err := db.QueryRow(
		`SELECT COUNT(*) FROM outbox WHERE device_id = ? AND status = 'pending'`,
		deviceID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// pushesAuditRow returns (type, operator, target, accepted) for a push.
func pushesAuditRow(t *testing.T, db *sql.DB, pushID string) (string, string, string, int) {
	t.Helper()
	var typ, operator, target string
	var accepted int
	if err := db.QueryRow(
		`SELECT type, operator, target, accepted FROM pushes WHERE push_id = ?`,
		pushID).Scan(&typ, &operator, &target, &accepted); err != nil {
		t.Fatalf("pushes row missing for %s: %v", pushID, err)
	}
	return typ, operator, target, accepted
}

// addOutboxDevice inserts an active device row and returns its raw Bearer
// token (the server stores only sha256Hex(raw), like enroll).
func addOutboxDevice(t *testing.T, db *sql.DB, id string) string {
	t.Helper()
	raw := "outbox-test-token-" + id
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash) VALUES (?, ?, ?)`,
		id, id, sha256Hex(raw)); err != nil {
		t.Fatal(err)
	}
	return raw
}

// useOperatorToken wires OPERATOR_API_TOKEN for one test (cleanup restores
// the unwired fail-closed default).
func useOperatorToken(t *testing.T, tok string) {
	t.Helper()
	old := operatorAPIToken
	SetOperatorAPIToken(tok)
	t.Cleanup(func() { SetOperatorAPIToken(old) })
}

func TestValidateAppPushTargetCodes(t *testing.T) {
	db := openNotifyTestDB(t)
	cases := []struct {
		name   string
		body   string
		status int
		code   string
	}{
		{"bad json", `{`, http.StatusBadRequest, "invalid_json"},
		{"empty target", strings.Replace(validAppPushBody("dev-1"), `"target":{"device_ids":["dev-1"]}`, `"target":{}`, 1), http.StatusUnprocessableEntity, "empty_target"},
		{"all false, no ids", strings.Replace(validAppPushBody("dev-1"), `"target":{"device_ids":["dev-1"]}`, `"target":{"all":false}`, 1), http.StatusUnprocessableEntity, "empty_target"},
		{"ambiguous all+ids", strings.Replace(validAppPushBody("dev-1"), `"target":{"device_ids":["dev-1"]}`, `"target":{"all":true,"device_ids":["dev-1"]}`, 1), http.StatusUnprocessableEntity, "ambiguous_target"},
		{"too many targets", `{"target":{"device_ids":[` + idsJSON(manyIDs(notify.MaxDeviceIDs+1)) + `]},"apk_url":"https://example.com/a.apk","sha256":"` + strings.Repeat("ab", 32) + `","package":"com.example.app","label":"L"}`, http.StatusUnprocessableEntity, "too_many_targets"},
		{"missing apk_url", `{"target":{"all":true},"sha256":"` + strings.Repeat("ab", 32) + `","package":"com.example.app","label":"L"}`, http.StatusUnprocessableEntity, "missing_apk_url"},
		{"http apk_url", strings.Replace(validAppPushBody(), "https://example.com/app.apk", "http://example.com/app.apk", 1), http.StatusUnprocessableEntity, "invalid_apk_url"},
		{"userinfo apk_url", strings.Replace(validAppPushBody(), "https://example.com/app.apk", "https://user@example.com/app.apk", 1), http.StatusUnprocessableEntity, "invalid_apk_url"},
		{"oversize apk_url", strings.Replace(validAppPushBody(), "https://example.com/app.apk", "https://example.com/"+strings.Repeat("a", 2040), 1), http.StatusUnprocessableEntity, "invalid_apk_url"},
		{"missing sha256", `{"target":{"all":true},"apk_url":"https://example.com/a.apk","package":"com.example.app","label":"L"}`, http.StatusUnprocessableEntity, "missing_sha256"},
		{"short sha256", strings.Replace(validAppPushBody(), strings.Repeat("ab", 32), strings.Repeat("ab", 31), 1), http.StatusUnprocessableEntity, "invalid_sha256"},
		{"uppercase sha256", strings.Replace(validAppPushBody(), strings.Repeat("ab", 32), strings.Repeat("AB", 32), 1), http.StatusUnprocessableEntity, "invalid_sha256"},
		{"nonhex sha256", strings.Replace(validAppPushBody(), strings.Repeat("ab", 32), strings.Repeat("zz", 32), 1), http.StatusUnprocessableEntity, "invalid_sha256"},
		{"missing package", `{"target":{"all":true},"apk_url":"https://example.com/a.apk","sha256":"` + strings.Repeat("ab", 32) + `","label":"L"}`, http.StatusUnprocessableEntity, "missing_package"},
		{"package no dot", strings.Replace(validAppPushBody(), `"package":"com.example.app"`, `"package":"nodot"`, 1), http.StatusUnprocessableEntity, "invalid_package"},
		{"package bad char", strings.Replace(validAppPushBody(), `"package":"com.example.app"`, `"package":"com.ex ample.app"`, 1), http.StatusUnprocessableEntity, "invalid_package"},
		{"package too long", strings.Replace(validAppPushBody(), `"package":"com.example.app"`, `"package":"com.example.`+strings.Repeat("a", 250)+`"`, 1), http.StatusUnprocessableEntity, "invalid_package"},
		{"version too long", strings.Replace(validAppPushBody(), `"version":"1.0"`, `"version":"`+strings.Repeat("v", 65)+`"`, 1), http.StatusUnprocessableEntity, "invalid_version"},
		{"missing label", `{"target":{"all":true},"apk_url":"https://example.com/a.apk","sha256":"` + strings.Repeat("ab", 32) + `","package":"com.example.app"}`, http.StatusUnprocessableEntity, "missing_label"},
		{"label too long", strings.Replace(validAppPushBody(), `"label":"Example"`, `"label":"`+strings.Repeat("l", 101)+`"`, 1), http.StatusUnprocessableEntity, "invalid_label"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := postAppPush(t, db, tc.body)
			if w.Code != tc.status {
				t.Fatalf("status %d, want %d (%s)", w.Code, tc.status, w.Body.String())
			}
			if code := errorCode(t, w); code != tc.code {
				t.Fatalf("error %q, want %q", code, tc.code)
			}
		})
	}
}

// Boundary values that must ACCEPT: all:true absorbs empty device_ids,
// hyphen packages, max-length fields.
func TestValidateAppPushBoundariesAccept(t *testing.T) {
	db := openNotifyTestDB(t)
	// openNotifyTestDB already seeds dev-1; reuse it as the target.
	// all:true with explicitly empty device_ids means all.
	w := postAppPush(t, db, strings.Replace(validAppPushBody("dev-1"),
		`"target":{"device_ids":["dev-1"]}`, `"target":{"all":true,"device_ids":[]}`, 1))
	if w.Code != http.StatusAccepted {
		t.Fatalf("all+empty ids: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	// Hyphen is in the reverse-DNS-ish charset.
	w = postAppPush(t, db, strings.Replace(validAppPushBody("dev-1"),
		`"package":"com.example.app"`, `"package":"com.ex-ample.app"`, 1))
	if w.Code != http.StatusAccepted {
		t.Fatalf("hyphen package: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	// Max lengths: url 2048, package 256, version 64, label 100.
	longURL := "https://example.com/" + strings.Repeat("a", 2048-len("https://example.com/"))
	body := `{"target":{"device_ids":["dev-1"]},"apk_url":` + fmt.Sprintf("%q", longURL) +
		`,"sha256":"` + strings.Repeat("ab", 32) + `","package":"com.example.` + strings.Repeat("a", 244) +
		`","version":"` + strings.Repeat("v", 64) + `","label":"` + strings.Repeat("l", 100) + `"}`
	if got := len("com.example." + strings.Repeat("a", 244)); got != 256 {
		t.Fatalf("package boundary misbuilt: %d", got)
	}
	w = postAppPush(t, db, body)
	if w.Code != http.StatusAccepted {
		t.Fatalf("max lengths: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
}

// The marshaled flat map is capped at notify.MaxDataPayloadBytes (422
// payload_too_large). Field caps are runes while the budget is bytes, so
// a rune-legal but byte-huge URL (multi-byte runes) trips it.
func TestValidateAppPushPayloadCap(t *testing.T) {
	db := openNotifyTestDB(t)
	bigURL := "https://example.com/" + strings.Repeat("é", 2000)
	body := `{"target":{"all":true},"apk_url":` + fmt.Sprintf("%q", bigURL) +
		`,"sha256":"` + strings.Repeat("ab", 32) + `","package":"com.example.app","label":"L"}`
	w := postAppPush(t, db, body)
	if w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status %d, want 422 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "payload_too_large" {
		t.Fatalf("error %q, want payload_too_large", code)
	}
}

// Oversize bodies are 413 before decode runs.
func TestAppPushBodyTooLarge(t *testing.T) {
	db := openNotifyTestDB(t)
	big := `{"target":{"all":true},"title":"t","body":"` + strings.Repeat("b", 1<<20) + `"}`
	w := postAppPush(t, db, big)
	if w.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status %d, want 413 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "body_too_large" {
		t.Fatalf("error %q, want body_too_large", code)
	}
}

// Operator auth matrix for POST /api/v1/app-push: session+XRW or operator
// token wins; device Bearers (even valid ones, even beside a session) and
// garbage Bearers are blanket 403; missing credentials are 401.
func TestAppPushAuthMatrix(t *testing.T) {
	db := openNotifyTestDB(t)
	tokA := addOutboxDevice(t, db, "dev-A")
	mux, store := openAuthedMux(t, db, notify.NoopSender{})
	cookie := loginCookie(t, store)
	const opToken = "operator-test-token-0123456789abcdef"
	useOperatorToken(t, opToken)
	body := validAppPushBody("dev-A")

	post := func(cookie *http.Cookie, auth string, xrw bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(body))
		if xrw {
			req.Header.Set("X-Requested-With", "XMLHttpRequest")
		}
		if cookie != nil {
			req.AddCookie(cookie)
		}
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		return w
	}

	if w := post(nil, "", true); w.Code != http.StatusUnauthorized {
		t.Fatalf("no creds: status %d, want 401 (%s)", w.Code, w.Body.String())
	}
	if w := post(nil, "Bearer ", true); w.Code != http.StatusUnauthorized {
		t.Fatalf("empty bearer: status %d, want 401 (%s)", w.Code, w.Body.String())
	}
	if w := post(nil, "Basic b3A6cGFzcw==", true); w.Code != http.StatusUnauthorized {
		t.Fatalf("non-bearer scheme: status %d, want 401 (%s)", w.Code, w.Body.String())
	}
	if w := post(nil, "Bearer "+tokA, true); w.Code != http.StatusForbidden {
		t.Fatalf("device bearer: status %d, want 403 (%s)", w.Code, w.Body.String())
	}
	if w := post(cookie, "Bearer "+tokA, true); w.Code != http.StatusForbidden {
		t.Fatalf("device bearer + session: status %d, want 403 (%s)", w.Code, w.Body.String())
	}
	if w := post(nil, "Bearer garbage-token", true); w.Code != http.StatusForbidden {
		t.Fatalf("garbage bearer: status %d, want 403, never a session fallthrough (%s)", w.Code, w.Body.String())
	}
	if w := post(cookie, "", false); w.Code != http.StatusForbidden {
		t.Fatalf("session without write proof: status %d, want 403 (%s)", w.Code, w.Body.String())
	}
	if w := post(cookie, "", true); w.Code != http.StatusAccepted {
		t.Fatalf("session + XRW: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if w := post(nil, "Bearer "+opToken, false); w.Code != http.StatusAccepted {
		t.Fatalf("operator token (no session, no XRW): status %d, want 202 (%s)", w.Code, w.Body.String())
	}
}

// Lifecycle: targeted two-device push via the app endpoint, then poll as
// each device (own items only), ack, re-ack idempotency, unknown and
// other-device ack ids ignored, pushes audit row.
func TestAppPushLifecycle(t *testing.T) {
	db := openNotifyTestDB(t)
	tokA := addOutboxDevice(t, db, "dev-A")
	tokB := addOutboxDevice(t, db, "dev-B")
	mux, store := openAuthedMux(t, db, notify.NoopSender{})
	cookie := loginCookie(t, store)

	push := func(body string) (string, float64) {
		t.Helper()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/app-push", strings.NewReader(body))
		req.Header.Set("X-Requested-With", "XMLHttpRequest")
		req.AddCookie(cookie)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
		if w.Code != http.StatusAccepted {
			t.Fatalf("push: status %d, want 202 (%s)", w.Code, w.Body.String())
		}
		return pushAcceptedOf(t, w)
	}
	poll := func(raw string) []map[string]any {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet, "/api/v1/outbox", nil)
		req.Header.Set("Authorization", "Bearer "+raw)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
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
	ack := func(raw string, ids []string) int {
		t.Helper()
		body, _ := json.Marshal(map[string]any{"ids": ids})
		req := httptest.NewRequest(http.MethodPost, "/api/v1/commands/ack", strings.NewReader(string(body)))
		req.Header.Set("Authorization", "Bearer "+raw)
		w := httptest.NewRecorder()
		mux.ServeHTTP(w, req)
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

	pushID, accepted := push(validAppPushBody("dev-A", "dev-B"))
	if accepted != 2 || pushID == "" {
		t.Fatalf("push: accepted=%v id=%q, want 2 + id", accepted, pushID)
	}
	if got := outboxStatuses(t, db, pushID, "dev-A"); len(got) != 1 || got[0] != outboxStatusPending {
		t.Fatalf("dev-A rows %v, want 1 pending", got)
	}
	if got := outboxStatuses(t, db, pushID, "dev-B"); len(got) != 1 {
		t.Fatalf("dev-B rows %v, want 1 pending", got)
	}
	// Pushes audit row: operator label (session path here), canonical
	// target, final accepted count.
	if typ, operator, target, n := pushesAuditRow(t, db, pushID); typ != "app_install" || !strings.HasPrefix(operator, "session") || target != `{"device_ids":["dev-A","dev-B"]}` || n != 2 {
		t.Fatalf("pushes row = (%q,%q,%q,%d), want app_install/session/target/2", typ, operator, target, n)
	}
	// Stored payload is the canonical flat map.
	stored := outboxPayload(t, db, pushID, "dev-A")
	for k, want := range map[string]string{
		"type": "app_install", "push_id": pushID,
		"apk_url": "https://example.com/app.apk", "sha256": strings.Repeat("ab", 32),
		"package": "com.example.app", "version": "1.0", "label": "Example",
	} {
		if stored[k] != want {
			t.Fatalf("payload[%q] = %q, want %q (full: %v)", k, stored[k], want, stored)
		}
	}

	cmdsA := poll(tokA)
	if len(cmdsA) != 1 {
		t.Fatalf("dev-A poll: %d commands, want 1", len(cmdsA))
	}
	for k, want := range map[string]string{
		"type": "app_install", "push_id": pushID,
		"apk_url": "https://example.com/app.apk", "sha256": strings.Repeat("ab", 32),
		"package": "com.example.app", "version": "1.0", "label": "Example",
	} {
		if cmdsA[0][k] != want {
			t.Fatalf("poll command[%q] = %v, want %q", k, cmdsA[0][k], want)
		}
	}
	cmdIDA, _ := cmdsA[0]["command_id"].(string)
	if cmdIDA == "" {
		t.Fatal("poll command must carry command_id")
	}
	cmdsB := poll(tokB)
	if len(cmdsB) != 1 {
		t.Fatalf("dev-B poll: %d commands, want 1", len(cmdsB))
	}
	if cmdsB[0]["command_id"] == cmdIDA {
		t.Fatal("per-device command ids must differ")
	}
	if cmdsB[0]["push_id"] != pushID {
		t.Fatal("same fan-out must share push_id")
	}

	// Unknown ids and the other device's id are silently ignored.
	if n := ack(tokA, []string{"no-such-command", cmdsB[0]["command_id"].(string)}); n != 0 {
		t.Fatalf("ack foreign/unknown: acked=%d, want 0", n)
	}
	if n := outboxPendingCountFor(t, db, "dev-B"); n != 1 {
		t.Fatalf("dev-B pending = %d after foreign ack, want 1", n)
	}
	// Own ack works, then is idempotent.
	if n := ack(tokA, []string{cmdIDA}); n != 1 {
		t.Fatalf("ack own: acked=%d, want 1", n)
	}
	if n := ack(tokA, []string{cmdIDA}); n != 0 {
		t.Fatalf("re-ack: acked=%d, want 0", n)
	}
	if cmds := poll(tokA); len(cmds) != 0 {
		t.Fatalf("dev-A poll after ack: %d commands, want 0", len(cmds))
	}
	if cmds := poll(tokB); len(cmds) != 1 {
		t.Fatalf("dev-B poll: %d commands, want 1 (untouched)", len(cmds))
	}
}

// Zero targets are a normal 202 accepted:0 with a pushes row, never an
// error — unknown ids and empty registries alike.
func TestAppPushZeroTargets(t *testing.T) {
	db := openNotifyTestDB(t)
	w := postAppPush(t, db, validAppPushBody("nope"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := pushAcceptedOf(t, w)
	if accepted != 0 || pushID == "" {
		t.Fatalf("want accepted:0 with id, got %s", w.Body.String())
	}
	if _, _, _, n := pushesAuditRow(t, db, pushID); n != 0 {
		t.Fatalf("pushes accepted = %d, want 0", n)
	}
	if got := outboxStatuses(t, db, pushID, "nope"); len(got) != 0 {
		t.Fatalf("unknown device rows %v, want none", got)
	}
}

// Per-device cap: a device at 20 pending fails the WHOLE request closed
// (429 too_many_pending, nothing queued — pushes row included); draining
// via ack reopens it.
func TestAppPushTooManyPending(t *testing.T) {
	db := openNotifyTestDB(t)
	addOutboxDevice(t, db, "dev-A")
	now := utcNow()
	for i := 0; i < maxOutboxPendingPerDevice; i++ {
		cmdID, err := newNotificationID()
		if err != nil {
			t.Fatal(err)
		}
		if err := insertOutboxRow(db, cmdID, fmt.Sprintf("fill-%d", i), "dev-A",
			outboxTypeAppInstall, `{"type":"app_install"}`, now, now.Add(outboxTTL)); err != nil {
			t.Fatal(err)
		}
	}
	var pushesBefore int
	if err := db.QueryRow(`SELECT COUNT(*) FROM pushes`).Scan(&pushesBefore); err != nil {
		t.Fatal(err)
	}
	w := postAppPush(t, db, validAppPushBody("dev-A"))
	if w.Code != http.StatusTooManyRequests {
		t.Fatalf("status %d, want 429 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "too_many_pending" {
		t.Fatalf("error %q, want too_many_pending", code)
	}
	if n := outboxPendingCountFor(t, db, "dev-A"); n != maxOutboxPendingPerDevice {
		t.Fatalf("pending = %d after 429, want exactly %d (nothing queued)", n, maxOutboxPendingPerDevice)
	}
	var pushesAfter int
	if err := db.QueryRow(`SELECT COUNT(*) FROM pushes`).Scan(&pushesAfter); err != nil {
		t.Fatal(err)
	}
	if pushesAfter != pushesBefore {
		t.Fatal("429 must not write a pushes row (fail closed, whole request atomic)")
	}
}

// Expired rows prune on enqueue (so they never count toward the cap) and
// on outbox fetch; TTL is 7 days.
func TestAppPushPruneAndTTL(t *testing.T) {
	db := openNotifyTestDB(t)
	tokA := addOutboxDevice(t, db, "dev-A")
	mux, _ := openAuthedMux(t, db, notify.NoopSender{})

	// Seed one long-expired pending row directly.
	old := utcNow().Add(-8 * 24 * time.Hour)
	if _, err := db.Exec(
		`INSERT INTO outbox (command_id, push_id, device_id, type, payload_json, created_at, expires_at, status)
			VALUES ('expired-cmd', 'expired-push', 'dev-A', 'app_install', '{}', ?, ?, 'pending')`,
		formatTime(old), formatTime(old.Add(outboxTTL))); err != nil {
		t.Fatal(err)
	}
	// Enqueue prunes it: a fresh push still fits the cap and succeeds.
	w := postAppPush(t, db, validAppPushBody("dev-A"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("push after prune: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM outbox WHERE command_id = 'expired-cmd'`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("enqueue must prune expired rows")
	}
	// Expire the fresh row by hand, then poll: it is pruned on fetch and
	// never served.
	pushID, _ := pushAcceptedOf(t, w)
	if _, err := db.Exec(`UPDATE outbox SET expires_at = ? WHERE push_id = ?`,
		formatTime(utcNow().Add(-time.Hour)), pushID); err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodGet, "/api/v1/outbox", nil)
	req.Header.Set("Authorization", "Bearer "+tokA)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("poll: status %d, want 200 (%s)", rec.Code, w.Body.String())
	}
	var got struct {
		Commands []map[string]any `json:"commands"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Commands) != 0 {
		t.Fatalf("poll served %d expired commands, want 0", len(got.Commands))
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM outbox WHERE push_id = ?`, pushID).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatal("fetch must prune expired rows")
	}
}

// resolveNotifyTargets is shared verbatim with notify: revoked devices are
// excluded from both target forms (unit-level pin, alongside the HTTP
// revoked test in outbox_test.go).
func TestAppPushSkipsRevokedTargets(t *testing.T) {
	db := openNotifyTestDB(t)
	addOutboxDevice(t, db, "dev-A")
	addOutboxDevice(t, db, "dev-B")
	if _, err := RevokeDevice(db, "dev-A"); err != nil {
		t.Fatal(err)
	}
	w := postAppPush(t, db, validAppPushBody("dev-A", "dev-B"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := pushAcceptedOf(t, w)
	if accepted != 1 {
		t.Fatalf("accepted = %v, want 1 (revoked dev-A excluded)", accepted)
	}
	if got := outboxStatuses(t, db, pushID, "dev-A"); len(got) != 0 {
		t.Fatalf("revoked dev-A rows %v, want none", got)
	}
	if got := outboxStatuses(t, db, pushID, "dev-B"); len(got) != 1 {
		t.Fatalf("dev-B rows %v, want 1", got)
	}
}
