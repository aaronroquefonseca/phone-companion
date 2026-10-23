// Package notify — validation and driver-selection tests (P4 review fixes).
package notify

import (
	"context"
	"encoding/json"
	"strconv"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
)

func validMessage() Message {
	m := Message{
		Target:  Target{All: true},
		Title:   "Dinner",
		Body:    "Ready in 10 minutes",
		Actions: []Action{{ID: "ack", Title: "ACK"}},
	}
	m.ApplyDefaults()
	return m
}

func codeOf(err error) string {
	if verr, ok := err.(*ValidationError); ok {
		return verr.Code
	}
	return ""
}

func TestValidate(t *testing.T) {
	ids := func(n int) []string {
		out := make([]string, n)
		for i := range out {
			out[i] = "dev-" + strconv.Itoa(i)
		}
		return out
	}
	cases := []struct {
		name     string
		mutate   func(*Message)
		wantOK   bool
		wantCode string
	}{
		{name: "valid minimal", mutate: func(*Message) {}, wantOK: true},
		{name: "unknown kind accepted", mutate: func(m *Message) { m.Kind = "future-kind" }, wantOK: true},
		{name: "explicit device_ids", mutate: func(m *Message) {
			m.Target = Target{DeviceIDs: []string{"a", "b"}}
		}, wantOK: true},
		{name: "empty target", mutate: func(m *Message) { m.Target = Target{} }, wantCode: "empty_target"},
		{name: "ambiguous target", mutate: func(m *Message) {
			m.Target = Target{All: true, DeviceIDs: []string{"a"}}
		}, wantCode: "ambiguous_target"},
		{name: "too many targets", mutate: func(m *Message) {
			m.Target = Target{DeviceIDs: ids(MaxDeviceIDs + 1)}
		}, wantCode: "too_many_targets"},
		{name: "exactly max targets ok", mutate: func(m *Message) {
			m.Target = Target{DeviceIDs: ids(MaxDeviceIDs)}
		}, wantOK: true},
		{name: "missing title", mutate: func(m *Message) { m.Title = "" }, wantCode: "missing_title"},
		{name: "missing body", mutate: func(m *Message) { m.Body = "" }, wantCode: "missing_body"},
		{name: "title too long", mutate: func(m *Message) {
			m.Title = strings.Repeat("t", MaxTitleLen+1)
		}, wantCode: "title_too_long"},
		{name: "body too long", mutate: func(m *Message) {
			m.Body = strings.Repeat("b", MaxBodyLen+1)
		}, wantCode: "body_too_long"},
		{name: "kind too long", mutate: func(m *Message) {
			m.Kind = strings.Repeat("k", MaxKindLen+1)
		}, wantCode: "kind_too_long"},
		{name: "channel too long", mutate: func(m *Message) {
			m.Channel = strings.Repeat("c", MaxChannelLen+1)
		}, wantCode: "channel_too_long"},
		{name: "bad priority", mutate: func(m *Message) { m.Priority = "urgent" }, wantCode: "invalid_priority"},
		{name: "too many actions", mutate: func(m *Message) {
			m.Actions = []Action{{ID: "1", Title: "a"}, {ID: "2", Title: "b"}, {ID: "3", Title: "c"}, {ID: "4", Title: "d"}}
		}, wantCode: "too_many_actions"},
		{name: "empty action", mutate: func(m *Message) {
			m.Actions = []Action{{ID: "", Title: "x"}}
		}, wantCode: "invalid_action"},
		{name: "action too long", mutate: func(m *Message) {
			m.Actions = []Action{{ID: strings.Repeat("i", MaxActionIDLen+1), Title: "x"}}
		}, wantCode: "action_too_long"},
		{name: "http image rejected", mutate: func(m *Message) {
			m.ImageURL = "http://example.com/x.jpg"
		}, wantCode: "invalid_image_url"},
		{name: "hostless image rejected", mutate: func(m *Message) { m.ImageURL = "https://" }, wantCode: "invalid_image_url"},
		{name: "https image ok", mutate: func(m *Message) {
			m.ImageURL = "https://example.com/dinner.jpg"
		}, wantOK: true},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			m := validMessage()
			tc.mutate(&m)
			err := m.Validate()
			if tc.wantOK {
				if err != nil {
					t.Fatalf("expected valid, got %v", err)
				}
				return
			}
			if codeOf(err) != tc.wantCode {
				t.Fatalf("expected code %q, got err %v", tc.wantCode, err)
			}
		})
	}
}

func TestSenderFromEnv(t *testing.T) {
	cases := []struct {
		driver      string
		projectID   string
		creds       string
		wantType    string
		wantErr     bool
		wantProject string
	}{
		{driver: "", wantType: "notify.LogSender"},
		{driver: "log", wantType: "notify.LogSender"},
		{driver: " LOG ", wantType: "notify.LogSender"},
		{driver: "noop", wantType: "notify.NoopSender"},
		{driver: "fcm", projectID: "p", creds: "/x.json", wantType: "*notify.FCMSender", wantProject: "p"},
		{driver: "bogus", wantErr: true},
	}
	for _, tc := range cases {
		s, err := SenderFromEnv(tc.driver, tc.projectID, tc.creds)
		if tc.wantErr {
			if err == nil {
				t.Fatalf("driver %q: expected fail-fast error", tc.driver)
			}
			continue
		}
		if err != nil {
			t.Fatalf("driver %q: unexpected error %v", tc.driver, err)
		}
		if got := senderType(s); got != tc.wantType {
			t.Fatalf("driver %q: got %s, want %s", tc.driver, got, tc.wantType)
		}
		if f, ok := s.(*FCMSender); ok && f.ProjectID != tc.wantProject {
			t.Fatalf("fcm project = %q, want %q", f.ProjectID, tc.wantProject)
		}
	}
}

func senderType(s Sender) string {
	switch s.(type) {
	case LogSender:
		return "notify.LogSender"
	case NoopSender:
		return "notify.NoopSender"
	case *FCMSender:
		return "*notify.FCMSender"
	default:
		return "unknown"
	}
}

func TestDriversSend(t *testing.T) {
	ctx := context.Background()
	dev := Device{ID: "dev-1", Name: "Test"}
	msg := validMessage()
	plain := msg.DataPayload("nid-plain")
	if err := (NoopSender{}).Send(ctx, dev, plain); err != nil {
		t.Fatalf("noop: %v", err)
	}
	if err := (LogSender{}).Send(ctx, dev, plain); err != nil {
		t.Fatalf("log: %v", err)
	}
	// FCM skeleton fails closed both unconfigured and configured.
	if err := NewFCMSender("", "").Send(ctx, dev, plain); err == nil {
		t.Fatal("fcm unconfigured: expected error")
	}
	if err := NewFCMSender("p", "/x.json").Send(ctx, dev, plain); err == nil {
		t.Fatal("fcm skeleton: expected not-implemented error")
	}
}

func TestDataPayloadActions(t *testing.T) {
	p := validMessage().DataPayload("nid-1")
	if p["v"] != PayloadVersion || p["notification_id"] != "nid-1" || p["kind"] == "" {
		t.Fatalf("missing versioned keys: %v", p)
	}
	if p["action_0_id"] != "ack" || p["action_0_title"] != "ACK" {
		t.Fatalf("action pair missing: %v", p)
	}

	m := validMessage()
	m.Actions = nil
	p = m.DataPayload("nid-2")
	for k := range p {
		if len(k) > 7 && k[:7] == "action_" {
			t.Fatalf("empty actions must emit no action_* keys, got %q", k)
		}
	}
}

func TestSecretDataPayloadRoundTrip(t *testing.T) {
	key, err := crypto.GenerateKey()
	if err != nil {
		t.Fatal(err)
	}
	m := validMessage()
	m.Secret = true
	payload, err := m.SecretDataPayload("nid-secret-1", "dev-1", key)
	if err != nil {
		t.Fatalf("secret payload: %v", err)
	}
	if payload[SecretFlagKey] != "true" {
		t.Fatalf("missing secret flag: %v", payload)
	}
	if _, ok := payload["title"]; ok {
		t.Fatalf("secret payload must not carry plaintext title")
	}
	if _, ok := payload["body"]; ok {
		t.Fatalf("secret payload must not carry plaintext body")
	}
	m2 := validMessage()
	for _, tc := range []struct {
		envKey, field, want string
	}{
		{TitleEnvKey, "title", m2.Title},
		{BodyEnvKey, "body", m2.Body},
	} {
		var env crypto.Envelope
		if err := json.Unmarshal([]byte(payload[tc.envKey]), &env); err != nil {
			t.Fatalf("%s envelope is not P5 JSON: %v", tc.envKey, err)
		}
		if env.Kid != "dev-1" {
			t.Fatalf("%s envelope kid = %q, want dev-1", tc.envKey, env.Kid)
		}
		binding, err := crypto.NotifyBinding("nid-secret-1", tc.field)
		if err != nil {
			t.Fatal(err)
		}
		plain, err := crypto.Decrypt(key, "dev-1", binding, env)
		if err != nil {
			t.Fatalf("%s decrypt: %v", tc.envKey, err)
		}
		if string(plain) != tc.want {
			t.Fatalf("%s = %q, want %q", tc.envKey, plain, tc.want)
		}
		// Transplant across devices fails closed.
		if _, err := crypto.Decrypt(key, "dev-2", binding, env); err == nil {
			t.Fatalf("%s decrypts under the wrong kid", tc.envKey)
		}
		// Transplant across fields fails closed.
		other := "body"
		if tc.field == "body" {
			other = "title"
		}
		otherBinding, _ := crypto.NotifyBinding("nid-secret-1", other)
		if _, err := crypto.Decrypt(key, "dev-1", otherBinding, env); err == nil {
			t.Fatalf("%s decrypts under the wrong field binding", tc.envKey)
		}
	}
	// Non-secret fields ride in the clear alongside the envelopes.
	if payload["kind"] == "" || payload["v"] != PayloadVersion || payload["notification_id"] != "nid-secret-1" {
		t.Fatalf("secret payload lost clear fields: %v", payload)
	}
}

func TestValidatePayloadCap(t *testing.T) {
	m := validMessage()
	// A max-length ASCII body blows the FCM budget: worst-case marshal cap.
	m.Body = strings.Repeat("b", MaxBodyLen)
	if err := m.Validate(); codeOf(err) != "payload_too_large" {
		t.Fatalf("max body: got %v, want payload_too_large", err)
	}
	// Secret expansion trips the cap even sooner.
	m.Secret = true
	m.Body = strings.Repeat("b", 2500)
	if err := m.Validate(); codeOf(err) != "payload_too_large" {
		t.Fatalf("secret 2k body: got %v, want payload_too_large", err)
	}
	// Small secret messages pass.
	m.Body = "Ready in 10 minutes"
	m.Title = "Dinner"
	if err := m.Validate(); err != nil {
		t.Fatalf("small secret message should validate: %v", err)
	}
	// Ordinary small messages still pass.
	m.Secret = false
	if err := m.Validate(); err != nil {
		t.Fatalf("small plain message should validate: %v", err)
	}
}

func TestSanitizeError(t *testing.T) {
	if got := SanitizeError(""); got != "" {
		t.Fatalf("empty must stay empty, got %q", got)
	}
	if got := SanitizeError("notify: FCM driver unconfigured (need FCM_PROJECT_ID)"); got == "" {
		t.Fatal("ordinary driver text must survive")
	}
	// Control bytes, quote tricks, and escapes are stripped.
	got := SanitizeError("x\x00y\nz\t\"'`\\${PATH}$(id)\x1b[31m")
	for _, r := range got {
		if r < 0x20 || r == 0x7f {
			t.Fatalf("control byte survived: %q", got)
		}
	}
	if strings.ContainsAny(got, "\"'`\\$") {
		t.Fatalf("quotable metachars survived: %q", got)
	}
	// Truncation to MaxErrLen runes.
	long := strings.Repeat("e", MaxErrLen+100)
	if got := SanitizeError(long); len([]rune(got)) != MaxErrLen {
		t.Fatalf("truncated length = %d, want %d", len([]rune(got)), MaxErrLen)
	}
}
