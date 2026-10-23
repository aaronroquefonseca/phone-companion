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

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
	_ "modernc.org/sqlite"
)

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

func postNotify(t *testing.T, db *sql.DB, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/notify", strings.NewReader(body))
	w := httptest.NewRecorder()
	handleNotify(db, notify.NoopSender{})(w, req)
	return w
}

func TestHandleNotifyHappyPaths(t *testing.T) {
	db := openNotifyTestDB(t)
	for _, body := range []string{
		`{"target":{"all":true},"title":"Hi","body":"Yo"}`,
		`{"target":{"device_ids":["dev-1"]},"kind":"alert","title":"Hi","body":"Yo"}`,
		`{"target":{"device_ids":["dev-1","nope"]},"title":"Hi","body":"Yo"}`,
	} {
		w := postNotify(t, db, body)
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
}

func TestHandleNotifyRejects(t *testing.T) {
	db := openNotifyTestDB(t)
	many, _ := json.Marshal(map[string]any{
		"target": map[string]any{"device_ids": manyIDs(notify.MaxDeviceIDs + 1)},
		"title":  "t", "body": "b",
	})
	big := `{"target":{"all":true},"title":"t","body":"` + strings.Repeat("b", notify.MaxRequestBodyBytes) + `"}`

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
			w := postNotify(t, db, tc.body)
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

func openAuthedMux(t *testing.T, db *sql.DB) (*http.ServeMux, *SessionStore) {
	t.Helper()
	store, err := NewSessionStore(db, strings.Repeat("s", 32))
	if err != nil {
		t.Fatal(err)
	}
	return NewMux(db, notify.NoopSender{}, store, webui.NewCSRFProtection(strings.Repeat("s", 32))), store
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
	// Forward-compatible with the operator API write guard (parallel auth
	// batch): legitimate operator clients send X-Requested-With. The
	// current handler ignores it; the hardened handler requires it (or a
	// session-bound CSRF token).
	req.Header.Set("X-Requested-With", "XMLHttpRequest")
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
	mux, _ := openAuthedMux(t, db)
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
	mux, store := openAuthedMux(t, db)
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
	mux, store := openAuthedMux(t, db)
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
	if n != 1 {
		t.Fatalf("want 1 row after prune, got %d", n)
	}
	var kept string
	if err := db.QueryRow(`SELECT notification_id FROM notification_history`).Scan(&kept); err != nil {
		t.Fatal(err)
	}
	if kept != "n-new" {
		t.Fatalf("prune kept %q, want n-new", kept)
	}
}

// addNotifyDevice inserts an active device row for notify fan-out tests.
func addNotifyDevice(t *testing.T, db *sql.DB, id string) {
	t.Helper()
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash) VALUES (?, ?, 'h')`, id, id); err != nil {
		t.Fatal(err)
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

	w := postNotify(t, db, `{"target":{"all":true},"title":"Hi","body":"Yo"}`)
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
	addNotifyDevice(t, db, "dev-2")
	if _, err := RevokeDevice(db, "dev-1"); err != nil {
		t.Fatal(err)
	}

	w := postNotify(t, db, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
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

	w = postNotify(t, db, `{"target":{"device_ids":["dev-1","dev-2"]},"title":"Hi","body":"Yo"}`)
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

	w := postNotify(t, db, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("before revoke: status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if accepted, _ := acceptedOf(t, w); accepted != 1 {
		t.Fatalf("before revoke: accepted %v, want 1: %s", accepted, w.Body.String())
	}

	if _, err := RevokeDevice(db, "dev-1"); err != nil {
		t.Fatal(err)
	}

	w = postNotify(t, db, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
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
