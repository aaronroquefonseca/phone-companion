// Widget loader tests (P3). Temp-dir fixtures only; no network, no secrets.
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
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

// backdateFixture sets every file and directory under dir to a fixed past
// time (files first, then dirs bottom-up) so later edits are strictly newer
// at any clock precision — deterministic mtime assertions (B3).
func backdateFixture(t *testing.T, dir string, past time.Time) {
	t.Helper()
	var dirs []string
	err := filepath.Walk(dir, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		if info.IsDir() {
			dirs = append(dirs, p)
			return nil
		}
		return os.Chtimes(p, past, past)
	})
	if err != nil {
		t.Fatal(err)
	}
	for i := len(dirs) - 1; i >= 0; i-- {
		if err := os.Chtimes(dirs[i], past, past); err != nil {
			t.Fatal(err)
		}
	}
}

func snapshotOf(r *WidgetRegistry) *widgetSnapshot {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.snap
}

func authedGet(mux http.Handler, method, target string, headers map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, nil)
	req.Header.Set("Authorization", "Bearer test-token")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)
	return rec
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
	writeWidget(t, dir, "tasks",
		"id = \"tasks\"\ntype = \"list\"\ntitle = \"Tasks\"\n",
		"", `{"items": [{"id": "a", "text": "Buy milk", "done": false}], "future_field": "tolerated"}`)

	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatalf("load: %v", err)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)

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

	// List: envelopes served id-sorted, secret_ref omitted for non-secrets.
	rec = authedGet(mux, "GET", "/api/v1/widgets", nil)
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
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Fatalf("list Content-Type = %q", ct)
	}
	etag := rec.Header().Get("ETag")
	if etag == "" {
		t.Fatal("list missing ETag")
	}

	// Conditional GET → 304 with no body Content-Type.
	rec = authedGet(mux, "GET", "/api/v1/widgets", map[string]string{"If-None-Match": etag})
	if rec.Code != http.StatusNotModified {
		t.Fatalf("conditional list status = %d, want 304", rec.Code)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "" {
		t.Fatalf("304 must not carry Content-Type, got %q", ct)
	}

	// Single widget + 404.
	rec = authedGet(mux, "GET", "/api/v1/widgets/hello", nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("get status = %d", rec.Code)
	}
	var one widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &one); err != nil || one.ID != "hello" {
		t.Fatalf("bad single envelope: %s", rec.Body.String())
	}
	rec = authedGet(mux, "GET", "/api/v1/widgets/nope", nil)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("unknown id status = %d, want 404", rec.Code)
	}
}

// TestMtimeRecheck is the deterministic mtime test (B3): valid-only fixture,
// backdated well before Load, asserting NO change before the edit and change
// after — including same-second edits (B1: full-precision comparison).
func TestMtimeRecheck(t *testing.T) {
	dir := t.TempDir()
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello widget\n", "")
	writeWidget(t, dir, "tasks",
		"id = \"tasks\"\ntype = \"list\"\ntitle = \"Tasks\"\n",
		"", `{"items": []}`)
	backdateFixture(t, dir, time.Now().Add(-2*time.Hour))

	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatalf("load: %v", err)
	}
	if got := reg.loadCount(); got != 1 {
		t.Fatalf("loads after initial Load = %d, want 1", got)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)

	if widgetTreeChanged(dir, snapshotOf(reg)) {
		t.Fatal("widgetTreeChanged == true with no edits (would rescan every request)")
	}
	for _, target := range []string{"/api/v1/widgets", "/api/v1/widgets/hello", "/api/v1/widgets/tasks"} {
		if rec := authedGet(mux, "GET", target, nil); rec.Code != http.StatusOK {
			t.Fatalf("GET %s status = %d", target, rec.Code)
		}
	}
	if got := reg.loadCount(); got != 1 {
		t.Fatalf("loads after idle GETs = %d, want 1 (spurious rescans)", got)
	}

	// Same-second-capable edit: full-precision mtime must catch it.
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello widget v2\n", "")
	if !widgetTreeChanged(dir, snapshotOf(reg)) {
		t.Fatal("widgetTreeChanged == false after body.md edit (same-second edit missed)")
	}
	rec := authedGet(mux, "GET", "/api/v1/widgets/hello", nil)
	var updated widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &updated); err != nil {
		t.Fatalf("reload decode: %v", err)
	}
	var updatedPayload map[string]string
	if err := json.Unmarshal(updated.Payload, &updatedPayload); err != nil || updatedPayload["body"] != "# Hello widget v2\n" {
		t.Fatalf("edit not picked up: %s", updated.Payload)
	}
	if got := reg.loadCount(); got != 2 {
		t.Fatalf("loads after edit GET = %d, want 2", got)
	}
	if rec := authedGet(mux, "GET", "/api/v1/widgets", nil); rec.Code != http.StatusOK {
		t.Fatalf("post-edit list status = %d", rec.Code)
	}
	if got := reg.loadCount(); got != 2 {
		t.Fatalf("loads after steady GETs = %d, want 2", got)
	}
}

// TestInvalidDirsNoRescan (B2): skipped widgets must not force a rescan per
// request, and repairing one must be picked up via folded mtimes.
func TestInvalidDirsNoRescan(t *testing.T) {
	dir := t.TempDir()
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello\n", "")
	writeWidget(t, dir, "broken",
		"id = \"other\"\ntype = \"text\"\ntitle = \"Broken\"\n", // id != dir
		"body\n", "")
	writeWidget(t, dir, "mystery",
		"id = \"mystery\"\ntype = \"teleport\"\ntitle = \"Mystery\"\n",
		"body\n", "")
	backdateFixture(t, dir, time.Now().Add(-2*time.Hour))

	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatalf("load: %v", err)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)
	for i := 0; i < 3; i++ {
		if rec := authedGet(mux, "GET", "/api/v1/widgets", nil); rec.Code != http.StatusOK {
			t.Fatalf("list status = %d", rec.Code)
		}
		if rec := authedGet(mux, "GET", "/api/v1/widgets/hello", nil); rec.Code != http.StatusOK {
			t.Fatalf("get status = %d", rec.Code)
		}
	}
	if got := reg.loadCount(); got != 1 {
		t.Fatalf("loads with invalid dirs present = %d, want 1 (rescan loop)", got)
	}
	var list struct {
		Widgets []widgetEnvelope `json:"widgets"`
	}
	rec := authedGet(mux, "GET", "/api/v1/widgets", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil || len(list.Widgets) != 1 {
		t.Fatalf("invalid widgets must be skipped, got %s", rec.Body.String())
	}

	// Repair the broken widget: its (folded) old mtimes are older than the
	// fix, so the recheck must fire exactly once and serve it.
	writeWidget(t, dir, "broken",
		"id = \"broken\"\ntype = \"text\"\ntitle = \"Fixed\"\n",
		"fixed body\n", "")
	rec = authedGet(mux, "GET", "/api/v1/widgets", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil || len(list.Widgets) != 2 {
		t.Fatalf("repaired widget not picked up: %s", rec.Body.String())
	}
	if got := reg.loadCount(); got != 2 {
		t.Fatalf("loads after repair = %d, want 2", got)
	}
}

// TestSecretTripleEquality covers the P5 strictness reconciliation: unknown
// envelope fields rejected, secret_ref == kid enforced at load, kid ==
// device filtering at serve (stub device is "stub-device").
func TestSecretTripleEquality(t *testing.T) {
	const goodPayload = `{"alg":"AES-256-GCM","kid":"stub-device","nonce":"Ag==","ciphertext":"AA=="}`
	const otherPayload = `{"alg":"AES-256-GCM","kid":"dev-123","nonce":"Ag==","ciphertext":"AA=="}`
	dir := t.TempDir()
	writeWidget(t, dir, "vault",
		"id = \"vault\"\ntype = \"secret\"\ntitle = \"Vault\"\nsecret_ref = \"stub-device\"\n",
		"", goodPayload)
	writeWidget(t, dir, "other",
		"id = \"other\"\ntype = \"secret\"\ntitle = \"Other\"\nsecret_ref = \"dev-123\"\n",
		"", otherPayload) // loads, but filtered for the stub device
	writeWidget(t, dir, "mismatch",
		"id = \"mismatch\"\ntype = \"secret\"\ntitle = \"Mismatch\"\nsecret_ref = \"dev-123\"\n",
		"", goodPayload) // secret_ref != kid → skipped
	writeWidget(t, dir, "future",
		"id = \"future\"\ntype = \"secret\"\ntitle = \"Future\"\nsecret_ref = \"stub-device\"\n",
		"", `{"alg":"AES-256-GCM","kid":"stub-device","nonce":"Ag==","ciphertext":"AA==","v2only":1}`) // unknown field → skipped
	writeWidget(t, dir, "noref",
		"id = \"noref\"\ntype = \"secret\"\ntitle = \"NoRef\"\n",
		"", goodPayload) // missing secret_ref → skipped

	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatalf("load: %v", err)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)

	var list struct {
		Widgets []widgetEnvelope `json:"widgets"`
	}
	rec := authedGet(mux, "GET", "/api/v1/widgets", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatalf("list decode: %v", err)
	}
	if len(list.Widgets) != 1 || list.Widgets[0].ID != "vault" {
		t.Fatalf("want only [vault], got %s", rec.Body.String())
	}
	if list.Widgets[0].SecretRef != "stub-device" {
		t.Fatalf("secret must carry secret_ref, got %+v", list.Widgets[0])
	}

	if rec := authedGet(mux, "GET", "/api/v1/widgets/vault", nil); rec.Code != http.StatusOK {
		t.Fatalf("own secret status = %d, want 200", rec.Code)
	}
	// Another device's secret: no existence oracle.
	if rec := authedGet(mux, "GET", "/api/v1/widgets/other", nil); rec.Code != http.StatusNotFound {
		t.Fatalf("foreign secret status = %d, want 404", rec.Code)
	}
	for _, id := range []string{"mismatch", "future", "noref"} {
		if rec := authedGet(mux, "GET", "/api/v1/widgets/"+id, nil); rec.Code != http.StatusNotFound {
			t.Fatalf("invalid secret %s status = %d, want 404", id, rec.Code)
		}
	}
}

// TestWidgetFileLimits covers the hardening: oversized files and symlinked
// files fail closed (widget skipped, rest still serve).
func TestWidgetFileLimits(t *testing.T) {
	dir := t.TempDir()
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello\n", "")
	writeWidget(t, dir, "huge",
		"id = \"huge\"\ntype = \"text\"\ntitle = \"Huge\"\n",
		strings.Repeat("x", (1<<20)+1), "")
	writeWidget(t, dir, "linky",
		"id = \"linky\"\ntype = \"text\"\ntitle = \"Linky\"\n",
		"# Linky\n", "")
	// Swap linky's body.md for a symlink: refused at read.
	if err := os.Remove(filepath.Join(dir, "linky", "body.md")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.Join(dir, "hello", "body.md"), filepath.Join(dir, "linky", "body.md")); err != nil {
		t.Fatal(err)
	}

	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatalf("load: %v", err)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)
	var list struct {
		Widgets []widgetEnvelope `json:"widgets"`
	}
	rec := authedGet(mux, "GET", "/api/v1/widgets", nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatalf("list decode: %v", err)
	}
	if len(list.Widgets) != 1 || list.Widgets[0].ID != "hello" {
		t.Fatalf("want only [hello], got %s", rec.Body.String())
	}
}
