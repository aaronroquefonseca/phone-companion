// Package notify — validation and driver-selection tests (P4 review fixes).
package notify

import (
	"context"
	"strconv"
	"strings"
	"testing"
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
	if err := (NoopSender{}).Send(ctx, dev, msg); err != nil {
		t.Fatalf("noop: %v", err)
	}
	if err := (LogSender{}).Send(ctx, dev, msg); err != nil {
		t.Fatalf("log: %v", err)
	}
	// FCM skeleton fails closed both unconfigured and configured.
	if err := NewFCMSender("", "").Send(ctx, dev, msg); err == nil {
		t.Fatal("fcm unconfigured: expected error")
	}
	if err := NewFCMSender("p", "/x.json").Send(ctx, dev, msg); err == nil {
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
