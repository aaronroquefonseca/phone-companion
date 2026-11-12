// Package main — app-push FCM wake-hint tests.
//
// POST /api/v1/app-push queues the outbox (the delivery leg) and then
// sends a best-effort silent wake ({"type":"app_push_wake","push_id":...})
// per target device — ONLY when the configured sender is the real
// *notify.FCMSender AND the device row carries a non-empty FCM token.
// Hint failures are logged, never fail the request (always 202), and
// write no history rows. Devices without a token (or behind a non-FCM
// driver) pick the command up on their next poll.
package main

import (
	"bytes"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
)

// wakeBodies records the FCM data maps POSTed by the stub transport.
func appPushWakeBodies(t *testing.T, posts *int, bodies *[]map[string]string) func(*http.Request) *http.Response {
	t.Helper()
	return func(r *http.Request) *http.Response {
		*posts++
		raw, err := io.ReadAll(io.LimitReader(r.Body, 64*1024))
		if err != nil {
			t.Fatalf("read stub FCM body: %v", err)
		}
		var wire struct {
			Message struct {
				Token string            `json:"token"`
				Data  map[string]string `json:"data"`
			} `json:"message"`
		}
		if err := json.Unmarshal(raw, &wire); err != nil {
			t.Fatalf("stub FCM body not JSON: %v (%q)", err, raw)
		}
		*bodies = append(*bodies, wire.Message.Data)
		return fcmJSONResponse(http.StatusOK, `{"name":"projects/test-project/messages/1"}`)
	}
}

// The wake goes out exactly for FCM-sender + token devices: dev-fcm (fake
// token) gets one silent wake carrying the 202 push_id, while dev-1 (no
// token row) is skipped. Both devices still queue their outbox rows.
func TestAppPushWakeHintOnlyTokenDevices(t *testing.T) {
	db := openNotifyTestDB(t)
	notifyDeviceWithToken(t, db, "dev-fcm", "fake-live-token")
	posts := 0
	var bodies []map[string]string
	sender := fcmFanoutSender(t, appPushWakeBodies(t, &posts, &bodies))

	w := postAppPush(t, db, sender, validAppPushBody("dev-1", "dev-fcm"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := pushAcceptedOf(t, w)
	if accepted != 2 || pushID == "" {
		t.Fatalf("accepted=%v id=%q, want 2 + id", accepted, pushID)
	}
	if posts != 1 {
		t.Fatalf("FCM posts = %d, want exactly 1 (token device only)", posts)
	}
	if len(bodies) != 1 || bodies[0]["type"] != appPushWakeType || bodies[0]["push_id"] != pushID {
		t.Fatalf("wake data = %v, want {type:%s push_id:%s}", bodies, appPushWakeType, pushID)
	}
	// Outbox rows are the delivery leg: both devices queued, unaffected
	// by which of them got a hint.
	for _, id := range []string{"dev-1", "dev-fcm"} {
		if got := outboxStatuses(t, db, pushID, id); len(got) != 1 || got[0] != outboxStatusPending {
			t.Fatalf("device %s rows %v, want 1 pending", id, got)
		}
	}
}

// Non-FCM drivers never send: Noop, Log, and test doubles all 202 with
// rows queued and zero sends.
func TestAppPushWakeNeverForNonFCMDrivers(t *testing.T) {
	for _, sender := range []notify.Sender{notify.NoopSender{}, notify.LogSender{}, &captureSender{}} {
		db := openNotifyTestDB(t)
		notifyDeviceWithToken(t, db, "dev-fcm", "fake-live-token")
		w := postAppPush(t, db, sender, validAppPushBody("dev-fcm"))
		if w.Code != http.StatusAccepted {
			t.Fatalf("%T: status %d, want 202 (%s)", sender, w.Code, w.Body.String())
		}
		pushID, accepted := pushAcceptedOf(t, w)
		if accepted != 1 || pushID == "" {
			t.Fatalf("%T: accepted=%v id=%q, want 1 + id", sender, accepted, pushID)
		}
		if c, ok := sender.(*captureSender); ok && len(c.payloads) != 0 {
			t.Fatalf("captureSender must see no sends, got %d", len(c.payloads))
		}
		if got := outboxStatuses(t, db, pushID, "dev-fcm"); len(got) != 1 || got[0] != outboxStatusPending {
			t.Fatalf("%T: outbox rows %v, want 1 pending", sender, got)
		}
	}
}

// A token-less device behind the real FCM driver skips the hint (no
// POST) and still queues: the backfill path heals the token, and the
// next poll delivers.
func TestAppPushWakeSkippedForEmptyToken(t *testing.T) {
	db := openNotifyTestDB(t)
	posts := 0
	var bodies []map[string]string
	sender := fcmFanoutSender(t, appPushWakeBodies(t, &posts, &bodies))
	w := postAppPush(t, db, sender, validAppPushBody("dev-1"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if posts != 0 {
		t.Fatalf("empty token must skip the wake, got %d posts", posts)
	}
	pushID, accepted := pushAcceptedOf(t, w)
	if accepted != 1 || pushID == "" {
		t.Fatalf("accepted=%v id=%q, want 1 + id", accepted, pushID)
	}
	if got := outboxStatuses(t, db, pushID, "dev-1"); len(got) != 1 || got[0] != outboxStatusPending {
		t.Fatalf("outbox rows %v, want 1 pending", got)
	}
}

// captureAppPushLogs diverts the std logger for one test (restored on
// cleanup). Backend tests never run parallel, so the global logger swap
// is hermetic per-test.
func captureAppPushLogs(t *testing.T) *bytes.Buffer {
	t.Helper()
	var buf bytes.Buffer
	prev := log.Writer()
	log.SetOutput(&buf)
	t.Cleanup(func() { log.SetOutput(prev) })
	return &buf
}

// Every wake ATTEMPT is logged exactly once per tokened FCM device,
// before the send: success is otherwise silent, which makes "sent but
// dropped" indistinguishable from "never attempted". The line carries
// metadata only (device hash + push_id) — never the FCM token.
func TestAppPushWakeAttemptLoggedOncePerTokenDevice(t *testing.T) {
	db := openNotifyTestDB(t)
	notifyDeviceWithToken(t, db, "dev-fcm", "fake-live-token")
	posts := 0
	var bodies []map[string]string
	sender := fcmFanoutSender(t, appPushWakeBodies(t, &posts, &bodies))

	buf := captureAppPushLogs(t)
	w := postAppPush(t, db, sender, validAppPushBody("dev-1", "dev-fcm"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := pushAcceptedOf(t, w)
	if accepted != 2 || pushID == "" {
		t.Fatalf("accepted=%v id=%q, want 2 + id", accepted, pushID)
	}
	want := "app-push: wake attempt device=" + deviceLogID("dev-fcm") + " push_id=" + pushID
	if got := strings.Count(buf.String(), want); got != 1 {
		t.Fatalf("wake attempt line %q logged %d times, want exactly 1 (logs: %q)", want, got, buf.String())
	}
	// Tokenless dev-1 is skipped: exactly one attempt line total.
	if got := strings.Count(buf.String(), "wake attempt"); got != 1 {
		t.Fatalf("wake attempt lines = %d, want exactly 1 (tokenless device must skip)", got)
	}
	if strings.Contains(buf.String(), "fake-live-token") {
		t.Fatalf("attempt log must never carry the FCM token (logs: %q)", buf.String())
	}
}

// Skipped wakes log no attempt: an empty-token device behind the real
// FCM driver never reaches the send.
func TestAppPushWakeNoAttemptForEmptyToken(t *testing.T) {
	db := openNotifyTestDB(t)
	posts := 0
	var bodies []map[string]string
	sender := fcmFanoutSender(t, appPushWakeBodies(t, &posts, &bodies))

	buf := captureAppPushLogs(t)
	w := postAppPush(t, db, sender, validAppPushBody("dev-1"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (%s)", w.Code, w.Body.String())
	}
	if got := strings.Count(buf.String(), "wake attempt"); got != 0 {
		t.Fatalf("wake attempt lines = %d, want 0 (empty token must skip before Send)", got)
	}
}

// Skipped wakes log no attempt: non-FCM drivers never enter the wake
// block at all.
func TestAppPushWakeNoAttemptForNonFCMDrivers(t *testing.T) {
	for _, sender := range []notify.Sender{notify.NoopSender{}, notify.LogSender{}, &captureSender{}} {
		db := openNotifyTestDB(t)
		notifyDeviceWithToken(t, db, "dev-fcm", "fake-live-token")

		buf := captureAppPushLogs(t)
		w := postAppPush(t, db, sender, validAppPushBody("dev-fcm"))
		if w.Code != http.StatusAccepted {
			t.Fatalf("%T: status %d, want 202 (%s)", sender, w.Code, w.Body.String())
		}
		if got := strings.Count(buf.String(), "wake attempt"); got != 0 {
			t.Fatalf("%T: wake attempt lines = %d, want 0 (non-FCM driver never wakes)", sender, got)
		}
	}
}

// A failing FCM leg never fails the request: 500 from FCM still 202s
// with the row queued, and writes no history rows (log lines only).
func TestAppPushWakeSendFailureStill202(t *testing.T) {
	db := openNotifyTestDB(t)
	notifyDeviceWithToken(t, db, "dev-fcm", "fake-live-token")
	sender := fcmFanoutSender(t, func(*http.Request) *http.Response {
		return fcmJSONResponse(http.StatusInternalServerError, `{"error":{"code":500,"status":"UNAVAILABLE","message":"back off"}}`)
	})
	w := postAppPush(t, db, sender, validAppPushBody("dev-fcm"))
	if w.Code != http.StatusAccepted {
		t.Fatalf("status %d, want 202 (hint failures never fail the request) (%s)", w.Code, w.Body.String())
	}
	pushID, accepted := pushAcceptedOf(t, w)
	if accepted != 1 || pushID == "" {
		t.Fatalf("accepted=%v id=%q, want 1 + id", accepted, pushID)
	}
	if got := outboxStatuses(t, db, pushID, "dev-fcm"); len(got) != 1 || got[0] != outboxStatusPending {
		t.Fatalf("outbox rows %v, want 1 pending (delivery leg unaffected by hint failure)", got)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM notification_history`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("history rows = %d, want 0 (app-push wake is log-only)", n)
	}
	if !strings.Contains(w.Body.String(), pushID) {
		t.Fatalf("202 body %s must carry the push_id", w.Body.String())
	}
}
