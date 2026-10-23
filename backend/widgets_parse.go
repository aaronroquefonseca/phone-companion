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
// This file covers TOML/payload parsing, capability limits, and the symlink
// refusal policy. Loading (scanWidgetDir + observed-set + mtime recheck +
// SIGHUP) lives in widgets_load.go, the registry store in
// widgets_registry.go, and device auth + handlers + ETag + the secret encrypt
// path in widgets_serve.go.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"regexp"
	"strconv"
	"strings"
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
// Payload validation
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Capability limits + symlink policy
// ---------------------------------------------------------------------------

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
