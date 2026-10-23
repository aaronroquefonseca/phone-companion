// Widget loading: scanWidgetDir + observed-set + mtime recheck + SIGHUP.
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"syscall"
	"time"
)

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
