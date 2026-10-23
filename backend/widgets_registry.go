// Widget registry: concurrency-safe in-memory store + DefaultWidgetRegistry.
package main

import (
	"log"
	"sync"
)

// widgetRecord pairs an envelope with its ETag validator. For secret
// widgets the envelope Payload is never served directly: secretPlaintext
// holds the operator-authored plaintext bytes and the handler encrypts them
// per-device at serve time (fresh nonce per request, P10).
type widgetRecord struct {
	envelope        widgetEnvelope
	etag            string
	secretPlaintext []byte
}

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
