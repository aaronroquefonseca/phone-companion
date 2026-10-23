// Package main — notify handler and stub-gate tests (P4 review fixes).
package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
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

// openTestDB returns an in-memory DB with base + notify schemas applied.
func openTestDB(t *testing.T) *sql.DB {
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
	db := openTestDB(t)
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
	db := openTestDB(t)
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

func TestRequireAdminNotifyStubGate(t *testing.T) {
	next := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusTeapot) })

	t.Run("closed by default", func(t *testing.T) {
		t.Setenv("NOTIFY_UNAUTHENTICATED", "")
		w := httptest.NewRecorder()
		requireAdminNotifyStub(next)(w, httptest.NewRequest(http.MethodPost, "/api/v1/notify", nil))
		if w.Code != http.StatusForbidden {
			t.Fatalf("status %d, want 403", w.Code)
		}
	})

	t.Run("flag opens gate", func(t *testing.T) {
		t.Setenv("NOTIFY_UNAUTHENTICATED", "1")
		w := httptest.NewRecorder()
		requireAdminNotifyStub(next)(w, httptest.NewRequest(http.MethodPost, "/api/v1/notify", nil))
		if w.Code != http.StatusTeapot {
			t.Fatalf("status %d, want passthrough", w.Code)
		}
	})
}
