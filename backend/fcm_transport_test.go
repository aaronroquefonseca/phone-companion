// Package main — FCM fan-out integration tests (Wave-2 push).
//
// The real FCMSender runs against a stub HTTP transport and a stubbed
// TokenSource: no network, no key material. These tests pin the handler
// contract for typed FCM failures — per-device failed history rows with
// sanitized error text, 500 driver_unavailable, and NO auto-revoke.
package main

import (
	"context"
	"database/sql"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

func fcmStubClient(t *testing.T, handle func(*http.Request) *http.Response) *http.Client {
	t.Helper()
	return &http.Client{Transport: roundTripperFunc(func(r *http.Request) (*http.Response, error) {
		return handle(r), nil
	})}
}

type roundTripperFunc func(*http.Request) (*http.Response, error)

func (f roundTripperFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func fcmJSONResponse(status int, body string) *http.Response {
	h := make(http.Header)
	h.Set("Content-Type", "application/json")
	return &http.Response{StatusCode: status, Header: h, Body: io.NopCloser(strings.NewReader(body))}
}

// fcmFanoutSender builds a real FCMSender whose OAuth2 and FCM legs are both
// stubbed (fake access token + canned FCM response).
func fcmFanoutSender(t *testing.T, handle func(*http.Request) *http.Response) *notify.FCMSender {
	t.Helper()
	s := notify.NewFCMSender("test-project", "/nonexistent/fcm.json")
	s.TokenSource = func(context.Context) (string, error) { return "fake-access-token", nil }
	s.HTTPClient = fcmStubClient(t, handle)
	return s
}

// notifyDeviceWithToken inserts a live device row carrying fakeToken.
// Fake tokens only — never real registration tokens in tests.
func notifyDeviceWithToken(t *testing.T, db *sql.DB, id, fakeToken string) {
	t.Helper()
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash, fcm_token) VALUES (?, ?, 'h', ?)`,
		id, id, fakeToken); err != nil {
		t.Fatal(err)
	}
}

func historyStatus(t *testing.T, db *sql.DB, deviceID string) (status, errText string) {
	t.Helper()
	if err := db.QueryRow(`SELECT status, error FROM notification_history WHERE device_id = ?`, deviceID).Scan(&status, &errText); err != nil {
		t.Fatalf("history row missing for %s: %v", deviceID, err)
	}
	return status, errText
}

func deviceRevoked(t *testing.T, db *sql.DB, deviceID string) bool {
	t.Helper()
	var revoked sql.NullString
	if err := db.QueryRow(`SELECT revoked_at FROM devices WHERE id = ?`, deviceID).Scan(&revoked); err != nil {
		t.Fatal(err)
	}
	return revoked.Valid
}

// UNREGISTERED from FCM: the handler answers 500 driver_unavailable, the
// failed row carries the sanitized re-register signal, and the device row
// is NOT revoked (healing is via token backfill, never auto-revoke).
func TestFanoutFCMUnregisteredRecordsFailed(t *testing.T) {
	db := openNotifyTestDB(t)
	notifyDeviceWithToken(t, db, "dev-fcm", "fake-dead-token")
	sender := fcmFanoutSender(t, func(*http.Request) *http.Response {
		return fcmJSONResponse(http.StatusNotFound, `{"error":{"code":404,"status":"NOT_FOUND","message":"not found",
			"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`)
	})
	w := postNotify(t, db, sender, `{"target":{"device_ids":["dev-fcm"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if code := errorCode(t, w); code != "driver_unavailable" {
		t.Fatalf("error %q, want driver_unavailable", code)
	}
	status, errText := historyStatus(t, db, "dev-fcm")
	if status != notify.StatusFailed {
		t.Fatalf("history status = %q, want failed", status)
	}
	if !strings.Contains(errText, "fcm-unregistered") {
		t.Fatalf("history error %q must carry the re-register signal", errText)
	}
	if deviceRevoked(t, db, "dev-fcm") {
		t.Fatal("UNREGISTERED must not auto-revoke the device")
	}
}

// A device row with no stored token (paired before push was configured)
// fails closed with the missing-token re-register signal — the backfill
// endpoint heals it, not revocation.
func TestFanoutFCMMissingTokenFailsClosed(t *testing.T) {
	db := openNotifyTestDB(t)
	posts := 0
	sender := fcmFanoutSender(t, func(*http.Request) *http.Response {
		posts++
		return fcmJSONResponse(http.StatusOK, `{"name":"projects/test-project/messages/1"}`)
	})
	w := postNotify(t, db, sender, `{"target":{"device_ids":["dev-1"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("status %d, want 500 (%s)", w.Code, w.Body.String())
	}
	if posts != 0 {
		t.Fatalf("missing token must fail before any FCM post, got %d", posts)
	}
	status, errText := historyStatus(t, db, "dev-1")
	if status != notify.StatusFailed {
		t.Fatalf("history status = %q, want failed", status)
	}
	if !strings.Contains(errText, "fcm-missing-token") {
		t.Fatalf("history error %q must carry the re-register signal", errText)
	}
	if deviceRevoked(t, db, "dev-1") {
		t.Fatal("missing token must not auto-revoke the device")
	}
}

// Success through the real driver counts accepted and records queued.
func TestFanoutFCMSuccessAccepted(t *testing.T) {
	db := openNotifyTestDB(t)
	notifyDeviceWithToken(t, db, "dev-fcm", "fake-live-token")
	var sawToken string
	sender := fcmFanoutSender(t, func(r *http.Request) *http.Response {
		sawToken = r.Header.Get("Authorization")
		return fcmJSONResponse(http.StatusOK, `{"name":"projects/test-project/messages/1"}`)
	})
	w := postNotify(t, db, sender, `{"target":{"device_ids":["dev-fcm"]},"title":"Hi","body":"Yo"}`)
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	accepted, _ := acceptedOf(t, w)
	if accepted != 1 {
		t.Fatalf("accepted %v, want 1", accepted)
	}
	if sawToken != "Bearer fake-access-token" {
		t.Fatalf("auth = %q, want stubbed Bearer token", sawToken)
	}
	if status, _ := historyStatus(t, db, "dev-fcm"); status != notify.StatusQueued {
		t.Fatalf("history status = %q, want queued", status)
	}
}

// Target resolution carries the stored registration token to the driver.
func TestResolveTargetsCarriesFCMToken(t *testing.T) {
	db := openNotifyTestDB(t)
	notifyDeviceWithToken(t, db, "dev-fcm", "fake-reg-token")
	devs, err := resolveNotifyTargets(context.Background(), db, notify.Target{All: true})
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, d := range devs {
		if d.ID == "dev-fcm" {
			found = true
			if d.FCMToken != "fake-reg-token" {
				t.Fatalf("FCMToken = %q, want fake-reg-token", d.FCMToken)
			}
		}
	}
	if !found {
		t.Fatalf("dev-fcm missing from resolved targets: %+v", devs)
	}
}
