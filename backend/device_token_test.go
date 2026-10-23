// Package main — device FCM-token backfill endpoint tests (Wave-2 push).
package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// postFCMToken drives POST /api/v1/device/fcm-token on a test mux.
func postFCMToken(t *testing.T, mux http.Handler, bearer, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/device/fcm-token", strings.NewReader(body))
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	return w
}

func TestUpdateFCMTokenUnit(t *testing.T) {
	db := openDevicesTestDB(t)
	id, _, _, err := EnrollDevice(db, "Phone", "fake-fcm-old")
	if err != nil {
		t.Fatal(err)
	}
	// Same token: no change.
	if changed, found, err := UpdateDeviceFCMToken(db, id, "fake-fcm-old"); err != nil || changed || !found {
		t.Fatalf("same token: changed=%v found=%v err=%v", changed, found, err)
	}
	// New token: changed.
	if changed, found, err := UpdateDeviceFCMToken(db, id, "fake-fcm-new"); err != nil || !changed || !found {
		t.Fatalf("new token: changed=%v found=%v err=%v", changed, found, err)
	}
	var stored string
	if err := db.QueryRow(`SELECT fcm_token FROM devices WHERE id = ?`, id).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if stored != "fake-fcm-new" {
		t.Fatalf("stored = %q, want fake-fcm-new", stored)
	}
	// Whitespace normalizes to the same value: no change.
	if changed, found, err := UpdateDeviceFCMToken(db, id, "  fake-fcm-new  "); err != nil || changed || !found {
		t.Fatalf("padded same token: changed=%v found=%v err=%v", changed, found, err)
	}
	// Unknown device: not found.
	if _, found, err := UpdateDeviceFCMToken(db, "nope", "fake-fcm-x"); err != nil || found {
		t.Fatalf("unknown: found=%v err=%v", found, err)
	}
	// Revoked device: not found, never resurrected.
	if _, err := RevokeDevice(db, id); err != nil {
		t.Fatal(err)
	}
	if _, found, err := UpdateDeviceFCMToken(db, id, "fake-fcm-after-revoke"); err != nil || found {
		t.Fatalf("revoked: found=%v err=%v", found, err)
	}
}

func TestHandleUpdateFCMToken(t *testing.T) {
	mux, _, _, _ := setupPairingMux(t)
	db := deviceDB

	id, rawToken, _, err := EnrollDevice(db, "Backfill Phone", "")
	if err != nil {
		t.Fatal(err)
	}
	_ = id

	// No credential: 401.
	if w := postFCMToken(t, mux, "", `{"fcm_token":"fake-fcm-1"}`); w.Code != http.StatusUnauthorized {
		t.Fatalf("anon: got %d, want 401 (%s)", w.Code, w.Body.String())
	}
	// Unknown credential: 401 (no oracle).
	if w := postFCMToken(t, mux, "bogus-token", `{"fcm_token":"fake-fcm-1"}`); w.Code != http.StatusUnauthorized {
		t.Fatalf("bogus: got %d, want 401 (%s)", w.Code, w.Body.String())
	}
	// Empty token: 400.
	if w := postFCMToken(t, mux, rawToken, `{"fcm_token":"  "}`); w.Code != http.StatusBadRequest {
		t.Fatalf("empty: got %d, want 400 (%s)", w.Code, w.Body.String())
	}
	// Bad JSON: 400.
	if w := postFCMToken(t, mux, rawToken, `{`); w.Code != http.StatusBadRequest {
		t.Fatalf("bad json: got %d, want 400 (%s)", w.Code, w.Body.String())
	}
	// First upload from an empty-token row: updated=true (the backfill).
	w := postFCMToken(t, mux, rawToken, `{"fcm_token":"fake-fcm-1"}`)
	if w.Code != http.StatusOK {
		t.Fatalf("backfill: got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	var got map[string]bool
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || !got["updated"] {
		t.Fatalf("backfill: want {updated:true}, got %s", w.Body.String())
	}
	// Same token again: updated=false.
	w = postFCMToken(t, mux, rawToken, `{"fcm_token":"fake-fcm-1"}`)
	if w.Code != http.StatusOK {
		t.Fatalf("same: got %d, want 200 (%s)", w.Code, w.Body.String())
	}
	got = nil
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || got["updated"] {
		t.Fatalf("same: want {updated:false}, got %s", w.Body.String())
	}
	// Changed token: updated=true.
	w = postFCMToken(t, mux, rawToken, `{"fcm_token":"fake-fcm-2"}`)
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || !got["updated"] {
		t.Fatalf("rotated: want {updated:true}, got %s", w.Body.String())
	}
	var stored string
	if err := db.QueryRow(`SELECT fcm_token FROM devices WHERE id = ?`, id).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if stored != "fake-fcm-2" {
		t.Fatalf("stored = %q, want fake-fcm-2", stored)
	}
	// Credential-bearing: uncacheable.
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", cc)
	}
}
