// Package main — widget definitions loader (P3 WIDGET DEFINITIONS).
//
// On-disk contract: $WIDGET_DIR holds one directory per widget, named by
// widget id. Each widget directory contains:
//
//	widget.toml  — metadata: id, type, title (+ optional secret_ref for
//	               secret-type widgets; unknown keys are ignored).
//	body.md      — content for text-type widgets; served as payload
//	               {"body": "<raw file content>"}.
//	payload.json — content for list/status/secret widgets (see below).
//
// Validation: directory name must match ^[A-Za-z0-9][A-Za-z0-9_-]*$ and must
// equal widget.toml's id exactly (contract: "Stable id = widget directory
// name"). type must be one of text|secret|list|status. title must be
// non-empty. Widgets that fail validation are skipped with a log line; the
// registry keeps serving the last good snapshot for everything else.
//
// Payload shapes follow docs/api-contract.md "Widget types + envelope":
//
//	text:   body.md → {"body": "..."}.
//	list:   payload.json → {"items": [{"id","text","done"}]}, validated then
//	         served verbatim.
//	status: payload.json → {"state": "ok|warn|alert|unknown", "detail": "..."},
//	         validated then served verbatim.
//	secret: payload.json is the opaque P5 encrypted envelope, passed through
//	         byte-for-byte WITHOUT parsing or decryption. No crypto is
//	         implemented here by design (P5 owns it). secret_ref, when set in
//	         widget.toml, is served as-is; P5 will populate it per device
//	         (contract: kid of the per-device key). Secret payload bytes are
//	         never logged.
//
// updated_at is the last backend-observed change: the newest mtime among
// widget.toml, the payload file(s), and the widget directory itself,
// rendered RFC 3339 UTC. It drives client refresh.
//
// Reload strategy (documented choice): explicit SIGHUP reload
// (StartWidgetSIGHUPReloader, works via `docker kill -s HUP`, no inotify /
// third-party watcher so the static distroless binary stays stdlib-only)
// PLUS a best-effort mtime recheck on every GET: if any directory entry or
// known file is newer than the last load (or widgets were added/removed),
// the registry rescans before serving. No background ticker goroutine —
// hourly client polling makes per-request stat checks negligible, and there
// is no polling loop to leak in tests. SIGHUP is the reliable trigger;
// the mtime path is best-effort (filesystem timestamp granularity applies).
//
// HTTP surface (additive v1 extension per the contract's versioning rule):
//
//	GET /api/v1/widgets      → {"widgets": [<envelope>, ...]} (id-sorted,
//	                          empty array when no widgets load)
//	GET /api/v1/widgets/{id} → single <envelope>; 404 {"error":
//	                          "widget_not_found"} for unknown ids
//
// Both require device Bearer auth and support conditional polling via ETag /
// If-None-Match (304). Cache-Control is intentionally short
// (private, max-age=60, must-revalidate): the app polls manually + ~hourly,
// so a minute of client caching is safe and saves rescans.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

// widgetTypes is the closed v1 set from docs/api-contract.md. Unknown types
// fail validation here; the app must additionally ignore unknown types it
// receives so the backend can add types later.
var widgetTypes = map[string]bool{
	"text":   true,
	"secret": true,
	"list":   true,
	"status": true,
}

// widgetIDPattern constrains directory names to safe, URL-friendly ids.
var widgetIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9_-]*$`)

// widgetEnvelope is the wire shape from docs/api-contract.md: every widget
// served by GET /api/v1/widgets uses this envelope. secret_ref is omitted
// for non-secret widgets (omitempty).
type widgetEnvelope struct {
	ID        string          `json:"id"`
	Type      string          `json:"type"`
	Title     string          `json:"title"`
	UpdatedAt string          `json:"updated_at"`
	Payload   json.RawMessage `json:"payload"`
	SecretRef string          `json:"secret_ref,omitempty"`
}

// widgetRecord pairs an envelope with its ETag validator.
type widgetRecord struct {
	envelope widgetEnvelope
	etag     string
}

// widgetMeta is the parsed subset of widget.toml P3 understands.
type widgetMeta struct {
	ID        string
	Type      string
	Title     string
	SecretRef string
}

// ---------------------------------------------------------------------------
// widget.toml parsing (stdlib-only, minimal subset)
// ---------------------------------------------------------------------------

// parseWidgetTOML parses the small string-only subset of TOML used for widget
// metadata: top-level `key = "value"` lines with double- or single-quoted
// strings. Blank lines, `#` comments (outside quotes), [section] headers,
// and unknown keys are tolerated/ignored so future options stay compatible.
// Numbers, booleans, and multi-line strings are rejected with a clear error.
func parseWidgetTOML(data []byte) (widgetMeta, error) {
	var m widgetMeta
	seen := map[string]bool{}
	for i, rawLine := range strings.Split(string(data), "\n") {
		line := strings.TrimSpace(stripTOMLComment(rawLine))
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			continue // tolerate section headers for future options
		}
		eq := strings.Index(line, "=")
		if eq < 0 {
			return m, fmt.Errorf("widget.toml line %d: expected key = \"value\"", i+1)
		}
		key := strings.TrimSpace(line[:eq])
		if !validTOMLKey(key) {
			return m, fmt.Errorf("widget.toml line %d: invalid key %q", i+1, key)
		}
		if seen[key] {
			return m, fmt.Errorf("widget.toml: duplicate key %q", key)
		}
		seen[key] = true
		val, err := unquoteTOMLString(strings.TrimSpace(line[eq+1:]))
		if err != nil {
			return m, fmt.Errorf("widget.toml line %d: %v", i+1, err)
		}
		switch key {
		case "id":
			m.ID = val
		case "type":
			m.Type = val
		case "title":
			m.Title = val
		case "secret_ref":
			m.SecretRef = val
		default:
			// Unknown keys ignored (forward compatibility).
		}
	}
	if m.ID == "" {
		return m, fmt.Errorf("widget.toml: missing required key \"id\"")
	}
	if m.Type == "" {
		return m, fmt.Errorf("widget.toml: missing required key \"type\"")
	}
	if m.Title == "" {
		return m, fmt.Errorf("widget.toml: missing required key \"title\"")
	}
	return m, nil
}

// stripTOMLComment removes a trailing `#` comment, respecting quoted strings.
func stripTOMLComment(line string) string {
	var inSingle, inDouble, escaped bool
	for i := 0; i < len(line); i++ {
		c := line[i]
		switch {
		case escaped:
			escaped = false
		case inSingle && c == '\'':
			inSingle = false
		case inDouble && c == '\\':
			escaped = true
		case inDouble && c == '"':
			inDouble = false
		case !inSingle && !inDouble && c == '\'':
			inSingle = true
		case !inSingle && !inDouble && c == '"':
			inDouble = true
		case !inSingle && !inDouble && c == '#':
			return line[:i]
		}
	}
	return line
}

func validTOMLKey(key string) bool {
	if key == "" {
		return false
	}
	for _, c := range key {
		if c != '_' && c != '-' && (c < '0' || c > '9') && (c < 'a' || c > 'z') && (c < 'A' || c > 'Z') {
			return false
		}
	}
	return true
}

// unquoteTOMLString accepts "basic" (double-quoted, Go-compatible escapes
// for the common cases) and 'literal' (single-quoted, no escapes) strings.
func unquoteTOMLString(raw string) (string, error) {
	if len(raw) >= 2 && strings.HasPrefix(raw, `"`) && strings.HasSuffix(raw, `"`) {
		s, err := strconv.Unquote(raw)
		if err != nil {
			return "", fmt.Errorf("invalid string %q", raw)
		}
		return s, nil
	}
	if len(raw) >= 2 && strings.HasPrefix(raw, `'`) && strings.HasSuffix(raw, `'`) {
		return raw[1 : len(raw)-1], nil
	}
	return "", fmt.Errorf("string value must be quoted, got %q", raw)
}

// ---------------------------------------------------------------------------
// Widget loading + validation
// ---------------------------------------------------------------------------

// widgetSnapshot is one fully-loaded view of $WIDGET_DIR.
type widgetSnapshot struct {
	order    []string // widget ids, sorted for deterministic responses
	widgets  map[string]widgetRecord
	etag     string // registry-wide validator for GET /api/v1/widgets
	loadedAt time.Time
	maxMtime time.Time // newest observed file mtime; drives mtime rechecks
}

func emptySnapshot() *widgetSnapshot {
	return &widgetSnapshot{
		widgets:  map[string]widgetRecord{},
		etag:     `"` + strings.Repeat("0", 64) + `"`,
		loadedAt: time.Now(),
	}
}

// scanWidgetDir loads every valid widget under dir. Invalid widgets are
// skipped (returned as warnings, also logged by WidgetRegistry.Load); a
// missing directory yields an empty snapshot plus a warning so the server
// still boots with an empty volume mount. Only a hard read failure (e.g.
// permission denied on dir itself) is returned as an error.
func scanWidgetDir(dir string) (*widgetSnapshot, []string, error) {
	snap := &widgetSnapshot{
		widgets:  map[string]widgetRecord{},
		loadedAt: time.Now(),
	}
	var warnings []string

	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			warnings = append(warnings, fmt.Sprintf("widget dir %s does not exist; serving empty widget list", dir))
			snap.etag = computeWidgetETag(nil)
			return snap, warnings, nil
		}
		return nil, nil, fmt.Errorf("read widget dir %s: %w", dir, err)
	}

	for _, e := range entries {
		name := e.Name()
		if !e.IsDir() || strings.HasPrefix(name, ".") {
			continue // ignore stray files and dotfiles
		}
		rec, updatedAt, err := loadOneWidget(dir, name)
		if err != nil {
			warnings = append(warnings, fmt.Sprintf("widget %q skipped: %v", name, err))
			continue
		}
		snap.widgets[name] = rec
		snap.order = append(snap.order, name)
		if updatedAt.After(snap.maxMtime) {
			snap.maxMtime = updatedAt
		}
	}
	// Deterministic order for responses and stable ETags.
	for i := 1; i < len(snap.order); i++ {
		for j := i; j > 0 && snap.order[j] < snap.order[j-1]; j-- {
			snap.order[j], snap.order[j-1] = snap.order[j-1], snap.order[j]
		}
	}
	envs := make([]widgetEnvelope, 0, len(snap.order))
	for _, id := range snap.order {
		envs = append(envs, snap.widgets[id].envelope)
	}
	snap.etag = computeWidgetETag(envs)
	return snap, warnings, nil
}

// loadOneWidget validates and loads a single $WIDGET_DIR/<id> directory.
func loadOneWidget(parentDir, dirname string) (widgetRecord, time.Time, error) {
	var zero widgetRecord
	if !widgetIDPattern.MatchString(dirname) {
		return zero, time.Time{}, fmt.Errorf("invalid widget id %q (must match %s)", dirname, widgetIDPattern)
	}
	wdir := filepath.Join(parentDir, dirname)

	tomlBytes, err := os.ReadFile(filepath.Join(wdir, "widget.toml"))
	if err != nil {
		return zero, time.Time{}, fmt.Errorf("read widget.toml: %w", err)
	}
	meta, err := parseWidgetTOML(tomlBytes)
	if err != nil {
		return zero, time.Time{}, err
	}
	if meta.ID != dirname {
		return zero, time.Time{}, fmt.Errorf("widget.toml id %q != directory name %q", meta.ID, dirname)
	}
	if !widgetTypes[meta.Type] {
		return zero, time.Time{}, fmt.Errorf("unknown type %q (want text|secret|list|status)", meta.Type)
	}
	if strings.TrimSpace(meta.Title) == "" {
		return zero, time.Time{}, fmt.Errorf("empty title")
	}
	if meta.SecretRef != "" && meta.Type != "secret" {
		return zero, time.Time{}, fmt.Errorf("secret_ref is only valid for secret-type widgets")
	}

	var payload json.RawMessage
	var payloadFiles []string
	switch meta.Type {
	case "text":
		body, err := os.ReadFile(filepath.Join(wdir, "body.md"))
		if err != nil {
			return zero, time.Time{}, fmt.Errorf("read body.md: %w", err)
		}
		encoded, err := json.Marshal(map[string]string{"body": string(body)})
		if err != nil {
			return zero, time.Time{}, fmt.Errorf("encode text payload: %w", err)
		}
		payload = encoded
		payloadFiles = []string{"body.md"}
	case "list", "status", "secret":
		raw, err := os.ReadFile(filepath.Join(wdir, "payload.json"))
		if err != nil {
			return zero, time.Time{}, fmt.Errorf("read payload.json: %w", err)
		}
		if err := validateOpaquePayload(meta.Type, raw); err != nil {
			return zero, time.Time{}, err
		}
		payload = bytes.TrimSpace(raw)
		payloadFiles = []string{"payload.json"}
	}

	updatedAt := newestMtime(wdir, append([]string{"widget.toml"}, payloadFiles...))
	env := widgetEnvelope{
		ID:        meta.ID,
		Type:      meta.Type,
		Title:     meta.Title,
		UpdatedAt: updatedAt.UTC().Format(time.RFC3339),
		Payload:   payload,
		SecretRef: meta.SecretRef,
	}
	rec := widgetRecord{envelope: env}
	rec.etag = computeWidgetETag([]widgetEnvelope{env})
	return rec, updatedAt, nil
}

// validateOpaquePayload checks the structural shape of list/status payloads
// and the JSON-object well-formedness of secret payloads. Secret bytes are
// NEVER decrypted, inspected, or logged here — P5 owns the envelope.
func validateOpaquePayload(typ string, raw []byte) error {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return fmt.Errorf("payload.json is empty")
	}
	switch typ {
	case "list":
		var v struct {
			Items []struct {
				ID   string `json:"id"`
				Text string `json:"text"`
				Done bool   `json:"done"`
			} `json:"items"`
		}
		dec := json.NewDecoder(bytes.NewReader(trimmed))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&v); err != nil {
			return fmt.Errorf("invalid list payload.json: %w", err)
		}
		if v.Items == nil {
			return fmt.Errorf("invalid list payload.json: missing \"items\" array")
		}
		for i, it := range v.Items {
			if it.ID == "" || it.Text == "" {
				return fmt.Errorf("invalid list payload.json: item %d needs id and text", i)
			}
		}
	case "status":
		var v struct {
			State  string `json:"state"`
			Detail string `json:"detail"`
		}
		dec := json.NewDecoder(bytes.NewReader(trimmed))
		dec.DisallowUnknownFields()
		if err := dec.Decode(&v); err != nil {
			return fmt.Errorf("invalid status payload.json: %w", err)
		}
		switch v.State {
		case "ok", "warn", "alert", "unknown":
		default:
			return fmt.Errorf("invalid status payload.json: state %q (want ok|warn|alert|unknown)", v.State)
		}
	case "secret":
		// Opaque pass-through: confirm only that it is a JSON object so a
		// truncated file fails fast at load instead of serving corrupt
		// bytes. Contents are not interpreted.
		if trimmed[0] != '{' {
			return fmt.Errorf("invalid secret payload.json: must be a JSON object (P5 envelope)")
		}
		var v map[string]json.RawMessage
		if err := json.Unmarshal(trimmed, &v); err != nil {
			return fmt.Errorf("invalid secret payload.json: %w", err)
		}
	}
	return nil
}

// newestMtime returns the newest mtime among dir itself and the named files
// inside it (missing files are skipped), truncated to whole seconds.
func newestMtime(dir string, files []string) time.Time {
	var newest time.Time
	if fi, err := os.Stat(dir); err == nil && fi.ModTime().After(newest) {
		newest = fi.ModTime()
	}
	for _, f := range files {
		if fi, err := os.Stat(filepath.Join(dir, f)); err == nil && fi.ModTime().After(newest) {
			newest = fi.ModTime()
		}
	}
	return newest.Truncate(time.Second)
}

func computeWidgetETag(envs []widgetEnvelope) string {
	h := sha256.New()
	for _, e := range envs {
		h.Write([]byte(e.ID))
		h.Write([]byte{0})
		h.Write([]byte(e.Type))
		h.Write([]byte{0})
		h.Write([]byte(e.Title))
		h.Write([]byte{0})
		h.Write([]byte(e.UpdatedAt))
		h.Write([]byte{0})
		h.Write(e.Payload)
		h.Write([]byte{0})
		h.Write([]byte(e.SecretRef))
		h.Write([]byte{0})
	}
	return `"` + hex.EncodeToString(h.Sum(nil)) + `"`
}

// ---------------------------------------------------------------------------
// In-memory registry with SIGHUP + mtime reload
// ---------------------------------------------------------------------------

// WidgetRegistry is the concurrency-safe in-memory view of $WIDGET_DIR.
type WidgetRegistry struct {
	mu   sync.RWMutex
	dir  string
	snap *widgetSnapshot
}

// NewWidgetRegistry builds an (initially empty) registry for dir. Call Load
// (or use DefaultWidgetRegistry) before serving.
func NewWidgetRegistry(dir string) *WidgetRegistry {
	return &WidgetRegistry{dir: dir, snap: emptySnapshot()}
}

// Load rescans the widget directory, swapping in the new snapshot. Invalid
// widgets are skipped with log lines; a failed rescan keeps serving the
// previous snapshot and returns the error.
func (r *WidgetRegistry) Load() error {
	snap, warnings, err := scanWidgetDir(r.dir)
	if err != nil {
		return err
	}
	for _, w := range warnings {
		log.Printf("widgets: %s", w)
	}
	r.mu.Lock()
	r.snap = snap
	r.mu.Unlock()
	log.Printf("widgets: loaded %d widget(s) from %s", len(snap.order), r.dir)
	return nil
}

// maybeReload performs the best-effort mtime recheck: if widgets were
// added/removed or any known file is newer than the last load, rescan.
// Stat failures resolve to "keep serving" — SIGHUP remains the reliable
// explicit trigger (see StartWidgetSIGHUPReloader).
func (r *WidgetRegistry) maybeReload() {
	r.mu.RLock()
	dir := r.dir
	prev := r.snap
	r.mu.RUnlock()
	if widgetTreeChanged(dir, prev) {
		if err := r.Load(); err != nil {
			log.Printf("widgets: reload failed, keeping previous snapshot: %v", err)
		}
	}
}

// widgetTreeChanged reports whether dir's widget set or any tracked mtime is
// newer than the snapshot. Dotfiles and plain files are ignored.
func widgetTreeChanged(dir string, prev *widgetSnapshot) bool {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	var names []string
	for _, e := range entries {
		if e.IsDir() && !strings.HasPrefix(e.Name(), ".") {
			names = append(names, e.Name())
		}
	}
	if len(names) != len(prev.order) {
		return true
	}
	known := make(map[string]bool, len(prev.order))
	for _, id := range prev.order {
		known[id] = true
	}
	for _, n := range names {
		if !known[n] {
			return true
		}
	}
	for _, n := range names {
		wdir := filepath.Join(dir, n)
		files := []string{"", "widget.toml"}
		// Only type-relevant files participate: a text widget never has
		// payload.json and vice versa, so a missing counterpart must NOT
		// count as a change (otherwise every request would rescan).
		// (New/removed widgets are already caught by the set comparison
		// above, so prev.widgets[n] always exists here.)
		switch prev.widgets[n].envelope.Type {
		case "text":
			files = append(files, "body.md")
		case "list", "status", "secret":
			files = append(files, "payload.json")
		default:
			files = append(files, "body.md", "payload.json")
		}
		for _, f := range files {
			p := wdir
			if f != "" {
				p = filepath.Join(wdir, f)
			}
			fi, err := os.Stat(p)
			if err != nil {
				return true // vanished/unreadable → rescan to converge
			}
			if fi.ModTime().Truncate(time.Second).After(prev.maxMtime) {
				return true
			}
		}
	}
	return false
}

// list returns the sorted envelopes plus the registry ETag.
func (r *WidgetRegistry) list() ([]widgetEnvelope, string) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]widgetEnvelope, 0, len(r.snap.order))
	for _, id := range r.snap.order {
		out = append(out, r.snap.widgets[id].envelope)
	}
	return out, r.snap.etag
}

// get returns one widget by id.
func (r *WidgetRegistry) get(id string) (widgetRecord, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	rec, ok := r.snap.widgets[id]
	return rec, ok
}

// StartWidgetSIGHUPReloader reloads reg on every SIGHUP until the process
// exits. Operators trigger it with `kill -HUP <pid>` /
// `docker kill -s HUP <container>` after editing the mounted widget files.
func StartWidgetSIGHUPReloader(reg *WidgetRegistry) {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGHUP)
	go func() {
		for range ch {
			log.Printf("widgets: SIGHUP received, reloading %s", reg.dir)
			if err := reg.Load(); err != nil {
				log.Printf("widgets: reload failed: %v", err)
			}
		}
	}()
}

var (
	defaultWidgetsOnce sync.Once
	defaultWidgetsReg  *WidgetRegistry
)

// DefaultWidgetRegistry is the process-wide registry backed by $WIDGET_DIR
// (via LoadConfig). All production handlers share it so SIGHUP reloads and
// mtime rechecks converge on one snapshot.
func DefaultWidgetRegistry() *WidgetRegistry {
	defaultWidgetsOnce.Do(func() {
		cfg := LoadConfig()
		reg := NewWidgetRegistry(cfg.WidgetDir)
		if err := reg.Load(); err != nil {
			log.Printf("widgets: initial load failed: %v", err)
		}
		defaultWidgetsReg = reg
	})
	return defaultWidgetsReg
}

// ---------------------------------------------------------------------------
// Device auth (stub) + HTTP handlers
// ---------------------------------------------------------------------------

// authenticateDeviceWidgetsStub is a P3 placeholder for device Bearer auth.
// It accepts any well-formed `Authorization: Bearer <token>` header so the
// endpoints are exercisable standalone on P0 (curl with any opaque token;
// 401 without one).
//
// TODO (Wave-2 P10): replace with the real AuthenticateDevice from
// backend/devices.go — verify the bearer token against the stored
// token_hash, enforce per-device revocation, and return the device_id.
func authenticateDeviceWidgetsStub(r *http.Request) (deviceID string, ok bool) {
	auth := r.Header.Get("Authorization")
	if auth == "" {
		return "", false
	}
	scheme, token, found := strings.Cut(auth, " ")
	if !found || !strings.EqualFold(scheme, "Bearer") || strings.TrimSpace(token) == "" {
		return "", false
	}
	// Intentionally a constant: never echo token material back or log it.
	return "stub-device", true
}

func writeWidgetError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code})
}

// handleWidgetsList serves GET /api/v1/widgets: {"widgets": [<envelope>]}.
func (r *WidgetRegistry) handleWidgetsList(w http.ResponseWriter, req *http.Request) {
	if _, ok := authenticateDeviceWidgetsStub(req); !ok {
		writeWidgetError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	r.maybeReload()
	widgets, etag := r.list()
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
	if req.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]any{"widgets": widgets})
}

// handleWidgetsGet serves GET /api/v1/widgets/{id}: a single envelope.
func (r *WidgetRegistry) handleWidgetsGet(w http.ResponseWriter, req *http.Request) {
	if _, ok := authenticateDeviceWidgetsStub(req); !ok {
		writeWidgetError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	r.maybeReload()
	rec, ok := r.get(req.PathValue("id"))
	if !ok {
		writeWidgetError(w, http.StatusNotFound, "widget_not_found")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("ETag", rec.etag)
	w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
	if req.Header.Get("If-None-Match") == rec.etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	_ = json.NewEncoder(w).Encode(rec.envelope)
}
