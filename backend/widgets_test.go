// Widget loader tests (P3). Temp-dir fixtures only; no network, no secrets.
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func writeWidget(t *testing.T, dir, id, toml, body, payload string) {
	t.Helper()
	wdir := filepath.Join(dir, id)
	if err := os.MkdirAll(wdir, 0o755); err != nil {
		t.Fatal(err)
	}
	if toml != "" {
		if err := os.WriteFile(filepath.Join(wdir, "widget.toml"), []byte(toml), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if body != "" {
		if err := os.WriteFile(filepath.Join(wdir, "body.md"), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if payload != "" {
		if err := os.WriteFile(filepath.Join(wdir, "payload.json"), []byte(payload), 0o644); err != nil {
			t.Fatal(err)
		}
	}
}

func TestParseWidgetTOML(t *testing.T) {
	m, err := parseWidgetTOML([]byte("id = \"hello\"\ntype = \"text\" # trailing comment\ntitle = 'Hello'\nunknown_future_key = \"ignored\"\n"))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if m.ID != "hello" || m.Type != "text" || m.Title != "Hello" {
		t.Fatalf("unexpected meta: %+v", m)
	}
	if _, err := parseWidgetTOML([]byte("id = \"a\"\ntype = \"text\"\n")); err == nil {
		t.Fatal("expected error for missing title")
	}
	if _, err := parseWidgetTOML([]byte("id = \"a\"\ntype = \"text\"\ntitle = \"T\"\ntitle = \"T2\"\n")); err == nil {
		t.Fatal("expected error for duplicate key")
	}
}

func TestWidgetEndpoints(t *testing.T) {
	dir := t.TempDir()
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello widget\n", "")
	writeWidget(t, dir, "broken",
		"id = \"other\"\ntype = \"text\"\ntitle = \"Broken\"\n",
		"body\n", "")
	writeWidget(t, dir, "mystery",
		"id = \"mystery\"\ntype = \"teleport\"\ntitle = \"Mystery\"\n",
		"body\n", "")
	writeWidget(t, dir, "tasks",
		"id = \"tasks\"\ntype = \"list\"\ntitle = \"Tasks\"\n",
		"", `{"items": [{"id": "a", "text": "Buy milk", "done": false}]}`)

	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatalf("load: %v", err)
	}
	mux := NewMuxWithWidgets(reg)

	authed := func(method, target string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, target, nil)
		req.Header.Set("Authorization", "Bearer test-token")
		rec := httptest.NewRecorder()
		mux.ServeHTTP(rec, req)
		return rec
	}

	// Unauthenticated → 401 with error code body.
	req := httptest.NewRequest("GET", "/api/v1/widgets", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("no-auth status = %d, want 401", rec.Code)
	}
	var errBody map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &errBody); err != nil || errBody["error"] == "" {
		t.Fatalf("no-auth body missing error code: %q", rec.Body.String())
	}

	// List: invalid widgets skipped, valid ones served as envelope array.
	rec = authed("GET", "/api/v1/widgets")
	if rec.Code != http.StatusOK {
		t.Fatalf("list status = %d, body %s", rec.Code, rec.Body.String())
	}
	var list struct {
		Widgets []widgetEnvelope `json:"widgets"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatalf("list decode: %v", err)
	}
	if len(list.Widgets) != 2 || list.Widgets[0].ID != "hello" || list.Widgets[1].ID != "tasks" {
		t.Fatalf("unexpected widget list: %s", rec.Body.String())
	}
	if list.Widgets[0].Type != "text" || list.Widgets[0].Title != "Hello" || list.Widgets[0].UpdatedAt == "" {
		t.Fatalf("bad hello envelope: %+v", list.Widgets[0])
	}
	var textPayload map[string]string
	if err := json.Unmarshal(list.Widgets[0].Payload, &textPayload); err != nil || textPayload["body"] != "# Hello widget\n" {
		t.Fatalf("bad text payload: %s", list.Widgets[0].Payload)
	}
	if list.Widgets[0].SecretRef != "" {
		t.Fatalf("text widget must omit secret_ref")
	}
	etag := rec.Header().Get("ETag")
	if etag == "" {
		t.Fatal("list missing ETag")
	}

	// Conditional GET → 304.
	req = httptest.NewRequest("GET", "/api/v1/widgets", nil)
	req.Header.Set("Authorization", "Bearer test-token")
	req.Header.Set("If-None-Match", etag)
	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	if rec.Code != http.StatusNotModified {
		t.Fatalf("conditional list status = %d, want 304", rec.Code)
	}

	// Single widget + 404.
	rec = authed("GET", "/api/v1/widgets/hello")
	if rec.Code != http.StatusOK {
		t.Fatalf("get status = %d", rec.Code)
	}
	var one widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &one); err != nil || one.ID != "hello" {
		t.Fatalf("bad single envelope: %s", rec.Body.String())
	}
	rec = authed("GET", "/api/v1/widgets/nope")
	if rec.Code != http.StatusNotFound {
		t.Fatalf("unknown id status = %d, want 404", rec.Code)
	}

	// mtime recheck: editing body.md is picked up without SIGHUP.
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello widget v2\n", "")
	rec = authed("GET", "/api/v1/widgets/hello")
	var updated widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &updated); err != nil {
		t.Fatalf("reload decode: %v", err)
	}
	var updatedPayload map[string]string
	if err := json.Unmarshal(updated.Payload, &updatedPayload); err != nil || updatedPayload["body"] != "# Hello widget v2\n" {
		t.Fatalf("mtime reload did not pick up edit: %s", updated.Payload)
	}
}
