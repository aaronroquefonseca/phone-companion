// Widget HTTP surface: device auth + per-device secret encryption + handlers.
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
// → 401) and support conditional polling via ETag / If-None-Match (304) for
// non-secret responses. Secret single-GET responses carry a fresh random
// nonce per request, so a matching 304 is impossible by design: no ETag is
// emitted or honored there (rotation-safe — the next poll after a re-pair
// always fetches a newly-encrypted envelope). Cache-Control is intentionally
// short (private, max-age=60, must-revalidate).
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"net/http"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
)

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
	// No ETag is emitted or honored: the fresh nonce never repeats, so a
	// client If-None-Match could never match by design.
	env, err := encryptSecretRecord(rec, deviceID)
	if err != nil {
		log.Printf("widgets: encrypt secret %q failed (no key material logged)", rec.envelope.ID)
		writeWidgetError(w, http.StatusInternalServerError, "needs_refresh")
		return
	}
	w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(env)
}
