// Package main — widget definitions loader (P3 WIDGET DEFINITIONS + P10
// SECRET ROUND-TRIP).
//
// On-disk contract: $WIDGET_DIR holds one directory per widget, named by
// widget id. Each widget directory contains:
//
//	widget.toml  — metadata: id, type, title (+ secret_ref for secret-type
//	               widgets, the target device_id; unknown keys are ignored).
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
//	secret: payload.json is OPERATOR-AUTHORED PLAINTEXT
//	         {"secret": "<utf-8 string>"} (strict: exactly this field,
//	         nothing else), stored in memory as plaintext bytes and NEVER
//	         served verbatim. At SERVE time the handler encrypts the
//	         plaintext per-device with the requesting device's secret_key
//	         via backend/crypto, emitting the P5 wire envelope
//	         {alg,kid,nonce,ciphertext} as the widget payload with
//	         kid == secret_ref == authenticated device_id and binding ==
//	         widget id (AAD = UTF-8(kid)||0x00||UTF-8(binding)). Secret
//	         payload bytes at rest in the snapshot are plaintext operator
//	         content (mounted volume trust); secret bytes are never logged.
//	         Unknown fields in widget.toml and in list/status payloads are
//	         TOLERATED (additive v1 versioning); strictness applies to the
//	         secret PLAINTEXT shape on disk (fail fast on malformed operator
//	         input).
//
// Triple equality (P5, enforced across load + serve): the static
// secret_ref MUST equal the authenticated device_id at serve time, and the
// emitted envelope kid MUST equal that same device_id. Secrets keyed to
// another device are omitted from the list and 404 on single fetch (fail
// closed, no existence oracle). Encryption failures (unknown device, NULL
// or malformed secret_key, RNG/crypto error) fail closed: single fetch
// returns 500 {"error":"needs_refresh"} and list omits the widget — never
// plaintext, never a crash, never a key-oracle distinction for foreign ids
// (those stay 404).
//
// updated_at is the last backend-observed change: the newest mtime among
// widget.toml, the payload file(s), and the widget directory itself,
// rendered RFC 3339 UTC (display only — change detection below compares at
// full clock precision so same-second edits are never missed). It drives
// client refresh.
//
// Reload strategy (documented choice): explicit SIGHUP reload
// (StartWidgetSIGHUPReloader, works via `docker kill -s HUP`, no inotify /
// third-party watcher so the static distroless binary stays stdlib-only)
// PLUS a best-effort mtime recheck on every GET: the snapshot records the
// FULL observed directory-name set (valid AND skipped/invalid widgets) and
// the newest mtime at full precision, INCLUDING skipped widgets' files so a
// later repair is picked up. Rescan when the set differs or any tracked file
// (type-relevant files for known widgets; all candidates for unknown dirs)
// is newer. No background ticker goroutine — hourly client polling makes
// per-request stat checks negligible, and there is no polling loop to leak
// in tests. SIGHUP is the reliable trigger; the mtime path is best-effort
// (filesystem timestamp granularity applies).
//
// HTTP surface (additive v1 extension per the contract's versioning rule):
//
//	GET /api/v1/widgets      → {"widgets": [<envelope>, ...]} (id-sorted,
//	                          empty array when no widgets load; secret
//	                          widgets filtered to the caller's device and
//	                          encrypted per-device with a fresh nonce per
//	                          request, so the list ETag changes whenever
//	                          secrets are present — clients always refetch)
//	GET /api/v1/widgets/{id} → single <envelope>; 404 {"error":
//	                          "widget_not_found"} for unknown ids (or secrets
//	                          keyed to another device); 500
//	                          {"error":"needs_refresh"} when the caller's
//	                          own secret cannot be encrypted (missing key) —
//	                          never plaintext
//
// Both require device Bearer auth (real AuthenticateDevice; revoked/unknown
// → 401) and support conditional polling via ETag / If-None-Match (304).
// Non-secret ETags are stable; secret envelopes carry a fresh random nonce
// per request so a secret's ETag is fresh per response by design
// (rotation-safe: the next poll after a re-pair always fetches a
// newly-encrypted envelope). Cache-Control is intentionally short
// (private, max-age=60, must-revalidate).
//
// Hardening: widget file reads are capped (~1MB) and symlinked widget files
// are refused (fail closed with a skip warning); symlinked directories are
// never followed.
package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
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

// widgetRecord pairs an envelope with its ETag validator. For secret
// widgets the envelope Payload is never served directly: secretPlaintext
// holds the operator-authored plaintext bytes and the handler encrypts them
// per-device at serve time (fresh nonce per request, P10).
type widgetRecord struct {
	envelope        widgetEnvelope
	etag            string
	secretPlaintext []byte
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
	order    []string // valid widget ids, sorted for deterministic responses
	widgets  map[string]widgetRecord
	observed []string // ALL non-dot subdirectory names (valid + skipped),
	// sorted; drives add/remove detection without rescan flapping
	maxMtime time.Time // newest observed mtime at FULL precision (valid and
	// skipped widgets alike); drives the mtime recheck
}

func emptySnapshot() *widgetSnapshot {
	return &widgetSnapshot{widgets: map[string]widgetRecord{}}
}

// widgetMaxFileBytes caps widget file reads so a rogue file cannot exhaust
// memory (operator-controlled mount, but fail closed anyway).
const widgetMaxFileBytes = 1 << 20 // ~1MB

// widgetCandidateFiles lists every file a widget directory may contribute.
var widgetCandidateFiles = []string{"widget.toml", "body.md", "payload.json"}

// readCappedFile reads path up to widgetMaxFileBytes, refusing symlinks
// (fail closed: a swapped-in link must not redirect reads outside $WIDGET_DIR).
func readCappedFile(path string) ([]byte, error) {
	if fi, err := os.Lstat(path); err != nil {
		return nil, err
	} else if fi.Mode()&os.ModeSymlink != 0 {
		return nil, fmt.Errorf("%s is a symlink; symlinked widget files are refused", path)
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, widgetMaxFileBytes+1))
	if err != nil {
		return nil, err
	}
	if len(data) > widgetMaxFileBytes {
		return nil, fmt.Errorf("%s exceeds %d bytes", path, widgetMaxFileBytes)
	}
	return data, nil
}

// scanWidgetDir loads every valid widget under dir. Invalid widgets are
// skipped (returned as warnings, also logged by WidgetRegistry.Load); a
// missing directory yields an empty snapshot plus a warning so the server
// still boots with an empty volume mount. Only a hard read failure (e.g.
// permission denied on dir itself) is returned as an error.
//
// Skipped widgets still contribute their directory name to observed and
// their file mtimes to maxMtime, so repairing (or deleting) them is picked
// up by the mtime recheck instead of forcing a rescan on every request.
func scanWidgetDir(dir string) (*widgetSnapshot, []string, error) {
	snap := &widgetSnapshot{widgets: map[string]widgetRecord{}}
	var warnings []string

	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			warnings = append(warnings, fmt.Sprintf("widget dir %s does not exist; serving empty widget list", dir))
			return snap, warnings, nil
		}
		return nil, nil, fmt.Errorf("read widget dir %s: %w", dir, err)
	}

	for _, e := range entries {
		name := e.Name()
		if strings.HasPrefix(name, ".") {
			continue // ignore dotfiles
		}
		wdir := filepath.Join(dir, name)
		li, err := os.Lstat(wdir)
		if err != nil {
			continue // vanished mid-scan; next recheck converges
		}
		if li.Mode()&os.ModeSymlink != 0 {
			warnings = append(warnings, fmt.Sprintf("widget %q skipped: symlinked entries are not followed", name))
			continue
		}
		if !li.IsDir() {
			continue // ignore stray files
		}
		snap.observed = append(snap.observed, name)
		rec, updatedAt, err := loadOneWidget(dir, name)
		if err != nil {
			warnings = append(warnings, fmt.Sprintf("widget %q skipped: %v", name, err))
			// Fold skipped files' mtimes so a later repair reads as newer.
			if updatedAt.After(snap.maxMtime) {
				snap.maxMtime = updatedAt
			}
			continue
		}
		snap.widgets[name] = rec
		snap.order = append(snap.order, name)
		if updatedAt.After(snap.maxMtime) {
			snap.maxMtime = updatedAt
		}
	}
	// Deterministic order for responses and stable ETags.
	sort.Strings(snap.order)
	sort.Strings(snap.observed)
	return snap, warnings, nil
}

// loadOneWidget validates and loads a single $WIDGET_DIR/<id> directory.
// updatedAt is ALWAYS returned (even on validation failure) at full clock
// precision: skipped widgets fold it into the snapshot so a later repair
// reads as newer (B2). Callers truncate only for display (RFC 3339).
func loadOneWidget(parentDir, dirname string) (widgetRecord, time.Time, error) {
	var zero widgetRecord
	wdir := filepath.Join(parentDir, dirname)
	updatedAt := newestMtime(wdir, widgetCandidateFiles)
	fail := func(err error) (widgetRecord, time.Time, error) {
		return zero, updatedAt, err
	}
	if !widgetIDPattern.MatchString(dirname) {
		return fail(fmt.Errorf("invalid widget id %q (must match %s)", dirname, widgetIDPattern))
	}

	tomlBytes, err := readCappedFile(filepath.Join(wdir, "widget.toml"))
	if err != nil {
		return fail(fmt.Errorf("read widget.toml: %w", err))
	}
	meta, err := parseWidgetTOML(tomlBytes)
	if err != nil {
		return fail(err)
	}
	if meta.ID != dirname {
		return fail(fmt.Errorf("widget.toml id %q != directory name %q", meta.ID, dirname))
	}
	if !widgetTypes[meta.Type] {
		return fail(fmt.Errorf("unknown type %q (want text|secret|list|status)", meta.Type))
	}
	if strings.TrimSpace(meta.Title) == "" {
		return fail(fmt.Errorf("empty title"))
	}
	if meta.SecretRef != "" && meta.Type != "secret" {
		return fail(fmt.Errorf("secret_ref is only valid for secret-type widgets"))
	}

	var payload json.RawMessage
	var payloadFiles []string
	var secretPlaintext []byte
	switch meta.Type {
	case "text":
		body, err := readCappedFile(filepath.Join(wdir, "body.md"))
		if err != nil {
			return fail(fmt.Errorf("read body.md: %w", err))
		}
		encoded, err := json.Marshal(map[string]string{"body": string(body)})
		if err != nil {
			return fail(fmt.Errorf("encode text payload: %w", err))
		}
		payload = encoded
		payloadFiles = []string{"body.md"}
	case "list", "status":
		raw, err := readCappedFile(filepath.Join(wdir, "payload.json"))
		if err != nil {
			return fail(fmt.Errorf("read payload.json: %w", err))
		}
		if err := validateWidgetPayload(meta.Type, raw); err != nil {
			return fail(err)
		}
		payload = bytes.TrimSpace(raw)
		payloadFiles = []string{"payload.json"}
	case "secret":
		raw, err := readCappedFile(filepath.Join(wdir, "payload.json"))
		if err != nil {
			return fail(fmt.Errorf("read payload.json: %w", err))
		}
		plain, err := parseSecretPlaintextPayload(raw)
		if err != nil {
			return fail(err)
		}
		// P10 triple-equality, first leg (load time): a secret widget
		// MUST declare its target device. secret_ref IS the kid the
		// serve path encrypts for; no decryption happens here and no
		// secret bytes are logged.
		if meta.SecretRef == "" {
			return fail(fmt.Errorf("secret widget %q must declare secret_ref (target device_id)", dirname))
		}
		secretPlaintext = plain
		// Payload is intentionally left empty in the snapshot: the serve
		// path encrypts secretPlaintext per-device with a fresh nonce.
		// Storing no envelope bytes guarantees we never serve stale or
		// cross-device ciphertext.
		payload = nil
		payloadFiles = []string{"payload.json"}
	}

	// Refresh updatedAt to the type-relevant files only (a stray
	// payload.json inside a text widget must not move its updated_at).
	updatedAt = newestMtime(wdir, append([]string{"widget.toml"}, payloadFiles...))
	env := widgetEnvelope{
		ID:        meta.ID,
		Type:      meta.Type,
		Title:     meta.Title,
		UpdatedAt: updatedAt.UTC().Format(time.RFC3339),
		Payload:   payload,
		SecretRef: meta.SecretRef,
	}
	rec := widgetRecord{envelope: env, secretPlaintext: secretPlaintext}
	if meta.Type == "secret" {
		// Stable ETag over metadata only (never served): single GET
		// computes a fresh ETag over the fresh envelope per request.
		rec.etag = computeWidgetETag([]widgetEnvelope{{
			ID: env.ID, Type: env.Type, Title: env.Title,
			UpdatedAt: env.UpdatedAt, SecretRef: env.SecretRef,
		}})
	} else {
		rec.etag = computeWidgetETag([]widgetEnvelope{env})
	}
	return rec, updatedAt, nil
}

// validateWidgetPayload checks the structural shape of list/status payloads
// (unknown fields TOLERATED — additive v1 versioning). Secret payloads are
// validated separately by parseSecretPlaintextPayload (strict plaintext
// shape on disk, NOT the wire envelope).
func validateWidgetPayload(typ string, raw []byte) error {
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
		if err := json.Unmarshal(trimmed, &v); err != nil {
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
		return nil
	case "status":
		var v struct {
			State  string `json:"state"`
			Detail string `json:"detail"`
		}
		if err := json.Unmarshal(trimmed, &v); err != nil {
			return fmt.Errorf("invalid status payload.json: %w", err)
		}
		switch v.State {
		case "ok", "warn", "alert", "unknown":
		default:
			return fmt.Errorf("invalid status payload.json: state %q (want ok|warn|alert|unknown)", v.State)
		}
		return nil
	}
	return fmt.Errorf("unknown widget type %q", typ)
}

// secretPlaintextKeys is the closed on-disk shape for secret widgets:
// exactly {"secret": "<plaintext string>"}. Unknown fields are rejected at
// load (strict, fail fast on operator typos); the wire envelope shape
// (alg/kid/nonce/ciphertext) is produced at SERVE time by encrypting this
// plaintext per-device and is defined by docs/api-contract.md (frozen).
var secretPlaintextKeys = map[string]bool{
	"secret": true,
}

// parseSecretPlaintextPayload validates the operator-authored secret payload
// on disk and returns the plaintext bytes (UTF-8 of the secret string).
// The empty string is legal (zero-length plaintext per the crypto contract).
// Secret bytes are never logged by callers.
func parseSecretPlaintextPayload(raw []byte) ([]byte, error) {
	trimmed := bytes.TrimSpace(raw)
	if len(trimmed) == 0 {
		return nil, fmt.Errorf("invalid secret payload.json: empty")
	}
	if trimmed[0] != '{' {
		return nil, fmt.Errorf("invalid secret payload.json: must be a JSON object {\"secret\": \"...\"}")
	}
	var v map[string]json.RawMessage
	if err := json.Unmarshal(trimmed, &v); err != nil {
		return nil, fmt.Errorf("invalid secret payload.json: %w", err)
	}
	for k := range v {
		if !secretPlaintextKeys[k] {
			return nil, fmt.Errorf("invalid secret payload.json: unknown field %q (want only \"secret\")", k)
		}
	}
	rawSecret, ok := v["secret"]
	if !ok {
		return nil, fmt.Errorf("invalid secret payload.json: missing \"secret\"")
	}
	var secret string
	if err := json.Unmarshal(rawSecret, &secret); err != nil {
		return nil, fmt.Errorf("invalid secret payload.json: \"secret\" must be a string")
	}
	// Empty plaintext is legal; non-empty must already be valid UTF-8
	// (JSON strings are), but crypto.Encrypt re-checks anyway at serve.
	out := make([]byte, len(secret))
	copy(out, secret)
	return out, nil
}

// newestMtime returns the newest mtime among dir itself and the named files
// inside it (missing files are skipped) at FULL clock precision. Callers
// truncate/format only for display; change detection must use this value
// verbatim or same-second edits are missed forever (B1).
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
	return newest
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
	// loads counts successful Load() swaps. Test hook for no-rescan
	// assertions (B2/B3); not used in production paths.
	loads int
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
	r.loads++
	r.mu.Unlock()
	log.Printf("widgets: loaded %d widget(s) from %s", len(snap.order), r.dir)
	return nil
}

// loadCount reports successful Load() swaps (tests only).
func (r *WidgetRegistry) loadCount() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.loads
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

// widgetTreeChanged reports whether dir's observed set or any tracked mtime
// is newer than the snapshot. Dotfiles and stray files are ignored.
// Comparison is at FULL mtime precision (B1); the observed set covers valid
// AND skipped widgets so invalid dirs never force a rescan loop (B2).
func widgetTreeChanged(dir string, prev *widgetSnapshot) bool {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return false
	}
	var names []string
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".") {
			continue
		}
		li, err := os.Lstat(filepath.Join(dir, e.Name()))
		if err != nil || li.Mode()&os.ModeSymlink != 0 {
			// Vanished: rescan to converge. Symlink appeared/swapped in:
			// rescan so the loader's symlink refusal takes effect.
			return true
		}
		if li.IsDir() {
			names = append(names, e.Name())
		}
	}
	if !sameStrings(names, prev.observed) {
		return true
	}
	for _, n := range names {
		wdir := filepath.Join(dir, n)
		files := []string{"", "widget.toml"}
		if rec, ok := prev.widgets[n]; ok {
			// Known widget: only type-relevant files participate, so a
			// missing counterpart never counts as a change (otherwise
			// every request would rescan).
			switch rec.envelope.Type {
			case "text":
				files = append(files, "body.md")
			case "list", "status", "secret":
				files = append(files, "payload.json")
			default:
				files = append(files, "body.md", "payload.json")
			}
		} else {
			// Skipped/invalid dir: track all candidates so a repair
			// (which touches one of them) reads as newer. Absent files
			// are the norm here, not a change (creation/deletion still
			// moves the directory mtime, which is always tracked).
			files = append(files, "body.md", "payload.json")
		}
		_, isKnown := prev.widgets[n]
		for _, f := range files {
			p := wdir
			if f != "" {
				p = filepath.Join(wdir, f)
			}
			fi, err := os.Lstat(p)
			if err != nil {
				if os.IsNotExist(err) && f != "" && !isKnown {
					continue // invalid dir simply lacks this candidate
				}
				return true // lost file / unreadable → rescan to converge
			}
			if fi.Mode()&os.ModeSymlink != 0 {
				return true // symlinked → rescan so refusal takes effect
			}
			if fi.ModTime().After(prev.maxMtime) {
				return true
			}
		}
	}
	return false
}

// sameStrings reports set equality (order-insensitive; snapshots store
// sorted slices, live scans may not).
func sameStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	counts := make(map[string]int, len(a))
	for _, s := range a {
		counts[s]++
	}
	for _, s := range b {
		if counts[s] == 0 {
			return false
		}
		counts[s]--
	}
	return true
}

// visibleRecordsForDevice returns the sorted records visible to deviceID.
// Secret widgets keyed to another device are omitted (P10 triple-equality,
// second leg: secret_ref == authenticated device_id, fail closed).
func (r *WidgetRegistry) visibleRecordsForDevice(deviceID string) []widgetRecord {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]widgetRecord, 0, len(r.snap.order))
	for _, id := range r.snap.order {
		rec := r.snap.widgets[id]
		if rec.envelope.Type == "secret" && rec.envelope.SecretRef != deviceID {
			continue
		}
		out = append(out, rec)
	}
	return out
}

// listForDevice returns the sorted envelopes visible to deviceID, encrypting
// secrets with a stub that fails closed. Production handlers do NOT use this:
// they encrypt via encryptSecretRecord with the caller's real secret_key
// (see handleWidgetsList). This helper exists so non-HTTP callers get the
// same fail-closed shape (secrets omitted without a key) without logging
// secret bytes.
func (r *WidgetRegistry) listForDevice(deviceID string) []widgetEnvelope {
	recs := r.visibleRecordsForDevice(deviceID)
	out := make([]widgetEnvelope, 0, len(recs))
	for _, rec := range recs {
		if rec.envelope.Type == "secret" {
			continue
		}
		out = append(out, rec.envelope)
	}
	return out
}

// getForDevice returns one widget by id if visible to deviceID. Secrets
// keyed to another device report not-found (no existence oracle).
func (r *WidgetRegistry) getForDevice(id, deviceID string) (widgetRecord, bool) {
	r.mu.RLock()
	defer r.mu.RUnlock()
	rec, ok := r.snap.widgets[id]
	if !ok {
		return widgetRecord{}, false
	}
	if rec.envelope.Type == "secret" && rec.envelope.SecretRef != deviceID {
		return widgetRecord{}, false
	}
	return rec, true
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
// Device auth + per-device secret encryption (P10) + HTTP handlers
// ---------------------------------------------------------------------------

// authenticateDeviceWidgets verifies the device Bearer token against the
// stored token_hash (real P8 auth, revocation-enforcing). Unknown/missing
// tokens fail closed with ok=false (caller maps to 401); a nil deviceDB
// fails closed with ok=false as well (caller maps to 500 — see handlers).
func authenticateDeviceWidgets(r *http.Request) (deviceID string, ok bool) {
	if deviceDB == nil {
		return "", false
	}
	dev, err := AuthenticateDevice(deviceDB, r)
	if err != nil || dev == nil {
		return "", false
	}
	return dev.ID, true
}

// lookupDeviceSecretKey returns the 32-byte per-device key for deviceID, or
// an error when the row is missing/revoked or the key is absent/malformed.
// Secret material is never logged by callers.
func lookupDeviceSecretKey(deviceID string) ([crypto.KeySize]byte, error) {
	var zero [crypto.KeySize]byte
	if deviceDB == nil {
		return zero, fmt.Errorf("widgets: device DB not wired")
	}
	var keyBytes []byte
	err := deviceDB.QueryRow(
		`SELECT secret_key FROM devices WHERE id = ? AND revoked_at IS NULL`,
		deviceID).Scan(&keyBytes)
	if err != nil {
		return zero, fmt.Errorf("widgets: no key for device: %w", err)
	}
	if len(keyBytes) != crypto.KeySize {
		return zero, fmt.Errorf("widgets: bad key length %d", len(keyBytes))
	}
	var key [crypto.KeySize]byte
	copy(key[:], keyBytes)
	return key, nil
}

// encryptSecretRecord encrypts rec's plaintext for deviceID, emitting the P5
// wire envelope as the widget payload (kid == deviceID, binding == widget
// id, AAD = UTF-8(kid)||0x00||UTF-8(binding) via backend/crypto). The
// secret_ref == deviceID check is re-verified here (defense in depth);
// mismatches and key/encrypt failures are fail-closed errors — callers map
// them to needs_refresh (own device) and never serve plaintext.
func encryptSecretRecord(rec widgetRecord, deviceID string) (widgetEnvelope, error) {
	if rec.envelope.Type != "secret" {
		return rec.envelope, nil
	}
	if rec.envelope.SecretRef != deviceID {
		return widgetEnvelope{}, fmt.Errorf("widgets: secret not visible to device")
	}
	key, err := lookupDeviceSecretKey(deviceID)
	if err != nil {
		return widgetEnvelope{}, err
	}
	env, err := crypto.Encrypt(key, deviceID, rec.envelope.ID, rec.secretPlaintext)
	// Best-effort key hygiene: the stack copy dies with this call; the
	// package keeps no key state (see crypto package note).
	if err != nil {
		return widgetEnvelope{}, err
	}
	payloadBytes, err := json.Marshal(env)
	if err != nil {
		return widgetEnvelope{}, err
	}
	out := rec.envelope
	out.Payload = payloadBytes
	return out, nil
}

func writeWidgetError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code})
}

// handleWidgetsList serves GET /api/v1/widgets: {"widgets": [<envelope>]}.
func (r *WidgetRegistry) handleWidgetsList(w http.ResponseWriter, req *http.Request) {
	if deviceDB == nil {
		writeWidgetError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	deviceID, ok := authenticateDeviceWidgets(req)
	if !ok {
		writeWidgetError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	r.maybeReload()
	// Per-device serve: non-secrets verbatim, secrets encrypted with the
	// caller's secret_key (fresh nonce per request). Unencryptable own
	// secrets are omitted here (single GET reports needs_refresh); foreign
	// secrets were already filtered by visibleRecordsForDevice (no oracle).
	recs := r.visibleRecordsForDevice(deviceID)
	widgets := make([]widgetEnvelope, 0, len(recs))
	for _, rec := range recs {
		if rec.envelope.Type != "secret" {
			widgets = append(widgets, rec.envelope)
			continue
		}
		env, err := encryptSecretRecord(rec, deviceID)
		if err != nil {
			log.Printf("widgets: encrypt secret %q failed (no key material logged)", rec.envelope.ID)
			continue
		}
		widgets = append(widgets, env)
	}
	// ETag is computed over exactly the envelopes served (fresh ciphertext
	// included), so secrets naturally bust the cache per request.
	etag := computeWidgetETag(widgets)
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
	if req.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"widgets": widgets})
}

// handleWidgetsGet serves GET /api/v1/widgets/{id}: a single envelope.
func (r *WidgetRegistry) handleWidgetsGet(w http.ResponseWriter, req *http.Request) {
	if deviceDB == nil {
		writeWidgetError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	deviceID, ok := authenticateDeviceWidgets(req)
	if !ok {
		writeWidgetError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	r.maybeReload()
	rec, ok := r.getForDevice(req.PathValue("id"), deviceID)
	if !ok {
		writeWidgetError(w, http.StatusNotFound, "widget_not_found")
		return
	}
	if rec.envelope.Type != "secret" {
		w.Header().Set("ETag", rec.etag)
		w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
		if req.Header.Get("If-None-Match") == rec.etag {
			w.WriteHeader(http.StatusNotModified)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(rec.envelope)
		return
	}
	// Secret: encrypt per-device with a fresh nonce. Any failure (missing
	// key, crypto error) is fail-closed needs_refresh — never plaintext.
	// Foreign ids never reach here (getForDevice 404s them, no oracle).
	env, err := encryptSecretRecord(rec, deviceID)
	if err != nil {
		log.Printf("widgets: encrypt secret %q failed (no key material logged)", rec.envelope.ID)
		writeWidgetError(w, http.StatusInternalServerError, "needs_refresh")
		return
	}
	etag := computeWidgetETag([]widgetEnvelope{env})
	w.Header().Set("ETag", etag)
	w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
	if req.Header.Get("If-None-Match") == etag {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(env)
}
