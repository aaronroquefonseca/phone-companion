// Package main — notify handler and stub-gate tests (P4 review fixes).
package main

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
	_ "modernc.org/sqlite"
)

// captureSender is a test-only real driver (deliberately NOT NoopSender or
// LogSender, which fail closed in the handler): it records per-device
// payloads and either succeeds or returns err on every send.
type captureSender struct {
	err      error
	devices  []notify.Device
	payloads []map[string]string
}

func (s *captureSender) Send(_ context.Context, d notify.Device, payload map[string]string) error {
	s.devices = append(s.devices, d)
	s.payloads = append(s.payloads, payload)
	return s.err
}

func TestRequestToMessageDefaults(t *testing.T) {
	msg, err := requestToMessage(notifyRequestJSON{
		Target: notifyTargetJSON{All: boolPtr(true)},
		Title:  "  Hi  ",
		Body:   "Yo",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if msg.Kind != notify.DefaultKind || msg.Priority != notify.DefaultPriority || msg.Channel != notify.DefaultChannel {
		t.Fatalf("defaults not applied: %+v", msg)
	}
	if msg.Title != "Hi" {
		t.Fatalf("title not trimmed: %q", msg.Title)
	}
}

func TestRequestToMessageTargetCodes(t *testing.T) {
	cases := []struct {
		name   string
		target notifyTargetJSON
		code   string
	}{
		{"empty", notifyTargetJSON{}, "empty_target"},
		{"ambiguous", notifyTargetJSON{All: boolPtr(true), DeviceIDs: []string{"a"}}, "ambiguous_target"},
		{"too many", notifyTargetJSON{DeviceIDs: manyIDs(notify.MaxDeviceIDs + 1)}, "too_many_targets"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			_, err := requestToMessage(notifyRequestJSON{Target: tc.target, Title: "t", Body: "b"})
			verr, ok := err.(*notify.ValidationError)
			if !ok || verr.Code != tc.code {
				t.Fatalf("expected code %q, got %v", tc.code, err)
			}
		})
	}
}

func TestRequestToMessageDedupes(t *testing.T) {
	msg, err := requestToMessage(notifyRequestJSON{
		Target: notifyTargetJSON{DeviceIDs: []string{"b", "a", "b", " ", "a"}},
		Title:  "t",
		Body:   "b",
	})
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(msg.Target.DeviceIDs) != 2 {
		t.Fatalf("expected 2 deduped ids, got %v", msg.Target.DeviceIDs)
	}
}

func boolPtr(b bool) *bool { return &b }

func manyIDs(n int) []string {
	out := make([]string, n)
	for i := range out {
		out[i] = fmt.Sprintf("dev-target-%d", i)
	}
	return out
}

// openNotifyTestDB returns an in-memory DB with base + notify schemas applied.
func openNotifyTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", ":memory:")
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	if err := notify.Migrate(db); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash) VALUES ('dev-1','Test','h')`); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func postNotify(t *testing.T, db *sql.DB, sender notify.Sender, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/notify", strings.NewReader(body))
	w := httptest.NewRecorder()
	handleNotify(db, sender)(w, req)
	return w
}

func TestHandleNotifyHappyPaths(t *testing.T) {
	db := openNotifyTestDB(t)
	sender := &captureSender{}
	for _, body := range []string{
		`{"target":{"all":true},"title":"Hi","body":"Yo"}`,
		`{"target":{"device_ids":["dev-1"]},"kind":"alert","title":"Hi","body":"Yo"}`,
		`{"target":{"device_ids":["dev-1","nope"]},"title":"Hi","body":"Yo"}`,
	} {
		w := postNotify(t, db, sender, body)
		if w.Code != http.StatusAccepted {
			t.Fatalf("body %s: status %d, want 202 (%s)", body, w.Code, w.Body.String())
		}
		var got map[string]any
		if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
			t.Fatal(err)
		}
		if got["accepted"] != float64(1) || got["notification_id"] == "" {
			t.Fatalf("unexpected 202 payload: %s", w.Body.String())
		}
	}
	// The non-secret path ships plaintext exactly as before.
	if len(sender.payloads) != 3 {
		t.Fatalf("want 3 captured payloads, got %d", len(sender.payloads))
	}
	if sender.payloads[0]["title"] != "Hi" || sender.payloads[0]["body"] != "Yo" {
		t.Fatalf("non-secret payload changed: %v", sender.payloads[0])
	}
}

// Stub drivers fail closed when there are real targets: a stub "accept"
// would lie about delivery, so the handler answers 500 driver_unavailable.
// Genuinely zero targets stay 202 accepted:0 behind any driver.
func TestHandleNotifyStubDriverUnavailable(t *testing.T) {
	for _, sender := range []notify.Sender{notify.NoopSender{}, notify.LogSender{}} {
		db := openNotifyTestDB(t)
		w := postNotify(t, db, sender, `{"target":{"all":true},"title":"Hi","body":"Yo"}`)
		if w.Code != http.StatusInternalServerError {
			t.Fatalf("%T with targets: status %d, want 500 (%s)", sender, w.Code, w.Body.String())
		}
		if code := errorCode(t, w); code != "driver_unavailable" {
			t.Fatalf("%T with targets: error %q, want driver_unavailable", sender, code)
		}
		// Zero targets are still 202 accepted:0 behind the same stub.
		w = postNotify(t, db, sender, `{"target":{"device_ids":["nope"]},"title":"Hi","body":"Yo"}`)
		if w.Code != http.StatusAccepted {
			t.Fatalf("%T zero targets: status %d, want 202 (%s)", sender, w.Code, w.Body.String())
		}
		accepted, nid := acceptedOf(t, w)
		if accepted != 0 || nid == "" {
			t.Fatalf("%T zero targets: want accepted:0 with id, got %s", sender, w.Body.String())
		}
	}
}

// A real driver that fails every send (e.g. misconfigured FCM) also yields
// 500 driver_unavailable, with failed history rows carrying sanitized error
// text — never raw driver output.
func TestHandleNotifyAllSendsFail(t *testing.T) {
	db := openNotifyTestDB(t)
	sender := &captureSender{err: fmt.Errorf("FCM driver unconfigured (need \"FCM_PROJECT_ID\" + token\x00\nsecret)")}

	w := postNotify(t, db, sender, `{"target":{"all":true},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "driver_unavailable" {
		t.Fatalf("error %q, want driver_unavailable", code)
	}
	var stored string
	if err := db.QueryRow(`SELECT error FROM notification_history WHERE device_id = 'dev-1'`).Scan(&stored); err != nil {
		t.Fatalf("history row missing: %v", err)
	}
	if stored == "" {
		t.Fatal("failed delivery must record sanitized error text")
	}
	for _, r := range stored {
		if r < 0x20 || r == 0x7f {
			t.Fatalf("control byte stored in history error: %q", stored)
		}
	}
	if strings.ContainsAny(stored, "\"\x00") {
		t.Fatalf("unsanitized error stored: %q", stored)
	}
}

func TestHandleNotifyRejects(t *testing.T) {
	db := openNotifyTestDB(t)
	many, _ := json.Marshal(map[string]any{
		"target": map[string]any{"device_ids": manyIDs(notify.MaxDeviceIDs + 1)},
		"title":  "t", "body": "b",
	})
	big := `{"target":{"all":true},"title":"t","body":"` + strings.Repeat("b", notify.MaxRequestBodyBytes) + `"}`

	sender := &captureSender{}
	cases := []struct {
		name   string
		body   string
		status int
		code   string
	}{
		{"bad json", `{`, http.StatusBadRequest, "invalid_json"},
		{"empty target", `{"target":{},"title":"t","body":"b"}`, http.StatusUnprocessableEntity, "empty_target"},
		{"too many targets", string(many), http.StatusUnprocessableEntity, "too_many_targets"},
		{"oversize body", big, http.StatusRequestEntityTooLarge, "body_too_large"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := postNotify(t, db, sender, tc.body)
			if w.Code != tc.status {
				t.Fatalf("status %d, want %d (%s)", w.Code, tc.status, w.Body.String())
			}
			var got map[string]string
			if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
				t.Fatal(err)
			}
			if got["error"] != tc.code {
				t.Fatalf("error %q, want %q", got["error"], tc.code)
			}
		})
	}
}

func openAuthedMux(t *testing.T, db *sql.DB, sender notify.Sender) (*http.ServeMux, *SessionStore) {
	t.Helper()
	store := NewSessionStore(db, strings.Repeat("s", 32))
	return NewMux(db, sender, store, webui.NewCSRFProtection(strings.Repeat("s", 32))), store
}

func loginCookie(t *testing.T, store *SessionStore) *http.Cookie {
	t.Helper()
	w := httptest.NewRecorder()
	if err := store.CreateSession(w, httptest.NewRequest("POST", "/login", nil)); err != nil {
		t.Fatal(err)
	}
	for _, c := range w.Result().Cookies() {
		if c.Name == SessionCookieName {
			return c
		}
	}
	t.Fatal("no session cookie issued")
	return nil
}

func postNotifyMux(mux *http.ServeMux, body string, cookie *http.Cookie, authHeader string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/notify", strings.NewReader(body))
	if cookie != nil {
		req.AddCookie(cookie)
	}
	if authHeader != "" {
		req.Header.Set("Authorization", authHeader)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func errorCode(t *testing.T, w *httptest.ResponseRecorder) string {
	t.Helper()
	var got map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatalf("non-JSON error body: %q", w.Body.String())
	}
	return got["error"]
}

// P9: the NOTIFY_UNAUTHENTICATED bypass is gone. Without a WebUI session the
// endpoint answers 401; device Bearer tokens never authorize it (403).
func TestNotifyRequiresWebUISession(t *testing.T) {
	db := openNotifyTestDB(t)
	mux, _ := openAuthedMux(t, db, notify.NoopSender{})
	w := postNotifyMux(mux, `{"target":{"all":true},"title":"Hi","body":"Yo"}`, nil, "")
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("status %d, want 401 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "unauthorized" {
		t.Fatalf("error %q, want unauthorized", code)
	}
}

func TestNotifyRejectsDeviceBearer(t *testing.T) {
	db := openNotifyTestDB(t)
	mux, store := openAuthedMux(t, db, notify.NoopSender{})
	body := `{"target":{"all":true},"title":"Hi","body":"Yo"}`

	// Device credential alone: 403, not 401 — a valid device token on an
	// operator endpoint is "valid credential, not allowed".
	w := postNotifyMux(mux, body, nil, "Bearer device-token-x")
	if w.Code != http.StatusForbidden {
		t.Fatalf("bearer alone: status %d, want 403 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "forbidden" {
		t.Fatalf("bearer alone: error %q, want forbidden", code)
	}

	// A device credential never upgrades into operator auth, even next to
	// a live session.
	w = postNotifyMux(mux, body, loginCookie(t, store), "Bearer device-token-x")
	if w.Code != http.StatusForbidden {
		t.Fatalf("bearer+session: status %d, want 403 (%s)", w.Code, w.Body.String())
	}
}

// P9: authed operator send keeps the P4 contract — 202 with accepted counts,
// including the accepted:0 case (no devices matched is not an error).
func TestNotifyAuthedSend(t *testing.T) {
	db := openNotifyTestDB(t)
	mux, store := openAuthedMux(t, db, &captureSender{})
	cookie := loginCookie(t, store)

	w := postNotifyMux(mux, `{"target":{"all":true},"title":"Hi","body":"Yo"}`, cookie, "")
	if w.Code != http.StatusAccepted {
		t.Fatalf("all: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["accepted"] != float64(1) || got["notification_id"] == "" {
		t.Fatalf("unexpected 202 payload: %s", w.Body.String())
	}

	// Unknown device_ids match nothing: still 202, accepted 0.
	w = postNotifyMux(mux, `{"target":{"device_ids":["nope"]},"title":"Hi","body":"Yo"}`, cookie, "")
	if w.Code != http.StatusAccepted {
		t.Fatalf("unknown ids: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	got = nil
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	if got["accepted"] != float64(0) || got["notification_id"] == "" {
		t.Fatalf("accepted:0 must still be 202 with an id: %s", w.Body.String())
	}

	// Validation still applies behind auth.
	w = postNotifyMux(mux, `{"target":{},"title":"Hi","body":"Yo"}`, cookie, "")
	if w.Code != http.StatusUnprocessableEntity {
		t.Fatalf("empty target: status %d, want 422 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "empty_target" {
		t.Fatalf("empty target: error %q, want empty_target", code)
	}
}

func TestPruneHistoryDropsOldRows(t *testing.T) {
	db := openNotifyTestDB(t)
	ctx := context.Background()
	// The COUNT-first gate skips small tables: seed past PruneMinRows so
	// the trim path actually runs.
	for i := 0; i < notify.PruneMinRows; i++ {
		if err := notify.RecordDelivery(ctx, db, "n-fill", "dev-1", "note", notify.StatusQueued, "", time.Now()); err != nil {
			t.Fatal(err)
		}
	}
	old := time.Now().AddDate(0, 0, -(notify.HistoryRetentionDays + 1))
	if err := notify.RecordDelivery(ctx, db, "n-old", "dev-1", "note", notify.StatusQueued, "", old); err != nil {
		t.Fatal(err)
	}
	if err := notify.RecordDelivery(ctx, db, "n-new", "dev-1", "note", notify.StatusQueued, "", time.Now()); err != nil {
		t.Fatal(err)
	}
	if err := notify.PruneHistory(ctx, db, time.Now()); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM notification_history`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != notify.PruneMinRows+1 {
		t.Fatalf("want %d rows after prune, got %d", notify.PruneMinRows+1, n)
	}
	var kept int
	if err := db.QueryRow(`SELECT COUNT(*) FROM notification_history WHERE notification_id = 'n-old'`).Scan(&kept); err != nil {
		t.Fatal(err)
	}
	if kept != 0 {
		t.Fatalf("prune kept %d n-old rows, want 0", kept)
	}
}

// Below the COUNT-first gate, pruning is a no-op: a quiet server keeps its
// few diagnostic rows (including old ones) until traffic crosses the gate.
func TestPruneHistoryGateSkipsSmallTables(t *testing.T) {
	db := openNotifyTestDB(t)
	ctx := context.Background()
	old := time.Now().AddDate(0, 0, -(notify.HistoryRetentionDays + 1))
	if err := notify.RecordDelivery(ctx, db, "n-old", "dev-1", "note", notify.StatusQueued, "", old); err != nil {
		t.Fatal(err)
	}
	if err := notify.RecordDelivery(ctx, db, "n-new", "dev-1", "note", notify.StatusQueued, "", time.Now()); err != nil {
		t.Fatal(err)
	}
	if err := notify.PruneHistory(ctx, db, time.Now()); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM notification_history`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 2 {
		t.Fatalf("gated prune must keep both rows, got %d", n)
	}
}

// addNotifyDevice inserts an active device row for notify fan-out tests.
func addNotifyDevice(t *testing.T, db *sql.DB, id string) {
	t.Helper()
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash) VALUES (?, ?, 'h')`, id, id); err != nil {
		t.Fatal(err)
	}
}

// setNotifyDeviceKey stores a 32-byte secret_key on a device row so secret
// fan-out can encrypt for it. Returns the key for decrypt assertions.
func setNotifyDeviceKey(t *testing.T, db *sql.DB, id string) [32]byte {
	t.Helper()
	key, err := crypto.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`UPDATE devices SET secret_key = ? WHERE id = ?`, key[:], id); err != nil {
		t.Fatal(err)
	}
	return key
}

// Secret fan-out round-trip: the handler per-device encrypts title+body,
// the captured wire payload carries envelopes (no plaintext), and each
// envelope decrypts under the device key with the notify binding.
func TestHandleNotifySecretRoundTrip(t *testing.T) {
	db := openNotifyTestDB(t)
	key := setNotifyDeviceKey(t, db, "dev-1")
	sender := &captureSender{}

	w := postNotify(t, db, sender,
		`{"target":{"device_ids":["dev-1"]},"title":"Dinner","body":"Ready in 10","secret":true}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	accepted, nid := acceptedOf(t, w)
	if accepted != 1 || nid == "" {
		t.Fatalf("unexpected 202 payload: %s", w.Body.String())
	}
	if len(sender.payloads) != 1 {
		t.Fatalf("want 1 captured payload, got %d", len(sender.payloads))
	}
	payload := sender.payloads[0]
	if payload[notify.SecretFlagKey] != "true" {
		t.Fatalf("missing secret flag: %v", payload)
	}
	for _, v := range payload {
		_ = v
	}
	if _, ok := payload["title"]; ok {
		t.Fatal("secret payload carries plaintext title")
	}
	if _, ok := payload["body"]; ok {
		t.Fatal("secret payload carries plaintext body")
	}
	for _, tc := range []struct {
		envKey, field, want string
	}{
		{notify.TitleEnvKey, "title", "Dinner"},
		{notify.BodyEnvKey, "body", "Ready in 10"},
	} {
		var env crypto.Envelope
		if err := json.Unmarshal([]byte(payload[tc.envKey]), &env); err != nil {
			t.Fatalf("%s: not a P5 envelope: %v", tc.envKey, err)
		}
		if env.Kid != "dev-1" {
			t.Fatalf("%s kid = %q, want dev-1", tc.envKey, env.Kid)
		}
		binding, err := crypto.NotifyBinding(nid, tc.field)
		if err != nil {
			t.Fatal(err)
		}
		plain, err := crypto.Decrypt(key, "dev-1", binding, env)
		if err != nil {
			t.Fatalf("%s decrypt: %v", tc.envKey, err)
		}
		if string(plain) != tc.want {
			t.Fatalf("%s = %q, want %q", tc.envKey, plain, tc.want)
		}
	}
}

// A secret request for a device with no usable key fails that device
// closed: with no other targets the handler answers 500
// driver_unavailable and records a failed (sanitized) history row.
func TestHandleNotifySecretWithoutKey(t *testing.T) {
	db := openNotifyTestDB(t)
	sender := &captureSender{}

	w := postNotify(t, db, sender,
		`{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo","secret":true}`)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "driver_unavailable" {
		t.Fatalf("error %q, want driver_unavailable", code)
	}
	if len(sender.payloads) != 0 {
		t.Fatalf("failed secret build must send nothing, sent %d", len(sender.payloads))
	}
	var status string
	if err := db.QueryRow(`SELECT status FROM notification_history WHERE device_id = 'dev-1'`).Scan(&status); err != nil {
		t.Fatalf("failed history row missing: %v", err)
	}
	if status != notify.StatusFailed {
		t.Fatalf("history status = %q, want failed", status)
	}
}

// acceptedOf decodes the 202 {"accepted"} count from a notify response.
func acceptedOf(t *testing.T, w *httptest.ResponseRecorder) (float64, string) {
	t.Helper()
	var got map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil {
		t.Fatal(err)
	}
	id, _ := got["notification_id"].(string)
	return got["accepted"].(float64), id
}

// historyDevices returns the device_ids recorded for one notification.
func historyDevices(t *testing.T, db *sql.DB, notificationID string) []string {
	t.Helper()
	rows, err := db.Query(`SELECT device_id FROM notification_history WHERE notification_id = ? ORDER BY device_id`, notificationID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			t.Fatal(err)
		}
		out = append(out, id)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return out
}

// Revoked devices must be excluded from {"all":true} fan-out: accepted
// counts only active devices and history rows cover only active devices.
func TestHandleNotifySkipsRevokedInAllFanOut(t *testing.T) {
	db := openNotifyTestDB(t)
	addNotifyDevice(t, db, "dev-2")
	if _, err := RevokeDevice(db, "dev-1"); err != nil {
		t.Fatal(err)
	}

	sender := &captureSender{}
	w := postNotify(t, db, sender, `{"target":{"all":true},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	accepted, nid := acceptedOf(t, w)
	if accepted != 1 {
		t.Fatalf("accepted %v, want 1 (revoked dev-1 excluded): %s", accepted, w.Body.String())
	}
	if got := historyDevices(t, db, nid); len(got) != 1 || got[0] != "dev-2" {
		t.Fatalf("history devices %v, want [dev-2]", got)
	}
}

// Revoked devices must be excluded from explicit device_ids fan-out: a
// revoked-only target is a normal 202 with accepted:0 and no history rows,
// and a mixed target fans out to active devices only.
func TestHandleNotifySkipsRevokedInDeviceIDsFanOut(t *testing.T) {
	db := openNotifyTestDB(t)
	sender := &captureSender{}
	addNotifyDevice(t, db, "dev-2")
	if _, err := RevokeDevice(db, "dev-1"); err != nil {
		t.Fatal(err)
	}

	w := postNotify(t, db, sender, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("revoked-only: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	accepted, nid := acceptedOf(t, w)
	if accepted != 0 {
		t.Fatalf("revoked-only: accepted %v, want 0: %s", accepted, w.Body.String())
	}
	if got := historyDevices(t, db, nid); len(got) != 0 {
		t.Fatalf("revoked-only: history devices %v, want none", got)
	}

	w = postNotify(t, db, sender, `{"target":{"device_ids":["dev-1","dev-2"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("mixed: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	accepted, nid = acceptedOf(t, w)
	if accepted != 1 {
		t.Fatalf("mixed: accepted %v, want 1: %s", accepted, w.Body.String())
	}
	if got := historyDevices(t, db, nid); len(got) != 1 || got[0] != "dev-2" {
		t.Fatalf("mixed: history devices %v, want [dev-2]", got)
	}
}

// Revoke-then-target: a device that accepted before revocation accepts
// nothing after — revocation takes effect on the notify path immediately.
func TestHandleNotifyRevokedThenTargeted(t *testing.T) {
	db := openNotifyTestDB(t)
	sender := &captureSender{}

	w := postNotify(t, db, sender, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("before revoke: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if accepted, _ := acceptedOf(t, w); accepted != 1 {
		t.Fatalf("before revoke: accepted %v, want 1: %s", accepted, w.Body.String())
	}

	if _, err := RevokeDevice(db, "dev-1"); err != nil {
		t.Fatal(err)
	}

	w = postNotify(t, db, sender, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("after revoke: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	accepted, nid := acceptedOf(t, w)
	if accepted != 0 {
		t.Fatalf("after revoke: accepted %v, want 0: %s", accepted, w.Body.String())
	}
	if got := historyDevices(t, db, nid); len(got) != 0 {
		t.Fatalf("after revoke: history devices %v, want none", got)
	}

	// resolveNotifyTargets agrees at the unit level for both target forms.
	if devs, err := resolveNotifyTargets(context.Background(), db, notify.Target{All: true}); err != nil {
		t.Fatal(err)
	} else if len(devs) != 0 {
		t.Fatalf("all-form after revoke: %d devices, want 0", len(devs))
	}
	if devs, err := resolveNotifyTargets(context.Background(), db, notify.Target{DeviceIDs: []string{"dev-1"}}); err != nil {
		t.Fatal(err)
	} else if len(devs) != 0 {
		t.Fatalf("device_ids-form after revoke: %d devices, want 0", len(devs))
	}
}
