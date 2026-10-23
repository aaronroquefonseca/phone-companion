// Package notify implements notification fan-out for P4 NOTIFICATIONS.
//
// Message schema follows docs/api-contract.md ("POST /api/v1/notify"):
// target all|device_ids, kind, title, body, priority, channel, actions[],
// optional image_url. Kinds are an open vocabulary — unknown kinds are
// accepted and rendered generically so new kinds never require app updates;
// the versioned data payload below carries that forward-compat contract.
//
// Server-side only: this package never touches google-services.json or any
// client-side FCM artifact. No secrets or service-account keys live in the
// repo (see AGENTS.md); FCM credentials arrive via environment at runtime.
package notify

import (
	"context"
	"errors"
	"log"
	"os"
	"strings"
)

// PayloadVersion is stamped into every data payload so the app can evolve
// rendering without backend coordination. Bump only with a documented
// migration; v1 readers ignore unknown keys.
const PayloadVersion = "1"

// Defaults and limits from the API contract.
const (
	DefaultKind     = "note"
	DefaultPriority = "default"
	DefaultChannel  = "general"
	// MaxActions caps the actions array (contract: max 3).
	MaxActions = 3
)

// ValidPriorities is the closed priority vocabulary (contract:
// min | low | default | high | max).
var ValidPriorities = map[string]bool{
	"min":     true,
	"low":     true,
	"default": true,
	"high":    true,
	"max":     true,
}

// Device is the addressing unit for fan-out. Only columns guaranteed by the
// P0 devices schema (id, name) are used here; registration-token storage
// lands in the pairing PR, and the FCM driver documents that dependency.
type Device struct {
	ID   string
	Name string
}

// Action is one tappable notification action: stable id plus label.
type Action struct {
	ID    string
	Title string
}

// Target selects fan-out recipients: exactly one of All or DeviceIDs.
type Target struct {
	All       bool
	DeviceIDs []string
}

// Message is a validated enqueue request: one logical notification fanned
// out to every resolved Device.
type Message struct {
	Target   Target
	Kind     string
	Title    string
	Body     string
	Priority string
	Channel  string
	Actions  []Action
	ImageURL string
}

// ValidationError carries a stable machine-readable Code for the
// {"error": "<code>"} response body plus the human detail.
type ValidationError struct {
	Code string
	Err  error
}

func (e *ValidationError) Error() string { return "notify: " + e.Code + ": " + e.Err.Error() }
func (e *ValidationError) Unwrap() error { return e.Err }

func validationError(code, msg string) *ValidationError {
	return &ValidationError{Code: code, Err: errors.New(msg)}
}

// ApplyDefaults fills contract defaults for unset optional fields.
func (m *Message) ApplyDefaults() {
	if m.Kind == "" {
		m.Kind = DefaultKind
	}
	if m.Priority == "" {
		m.Priority = DefaultPriority
	}
	if m.Channel == "" {
		m.Channel = DefaultChannel
	}
}

// Validate enforces the contract. Kind stays open-vocabulary by design:
// any non-empty kind passes so new kinds render generically on the app.
func (m Message) Validate() error {
	if m.Target.All && len(m.Target.DeviceIDs) > 0 {
		return validationError("ambiguous_target", `target must be exactly one of {"all":true} or {"device_ids":[...]}`)
	}
	if !m.Target.All && len(m.Target.DeviceIDs) == 0 {
		return validationError("empty_target", `target must be exactly one of {"all":true} or non-empty {"device_ids":[...]}`)
	}
	if m.Title == "" {
		return validationError("missing_title", "title must be non-empty")
	}
	if m.Body == "" {
		return validationError("missing_body", "body must be non-empty")
	}
	if !ValidPriorities[m.Priority] {
		return validationError("invalid_priority", "priority must be one of min|low|default|high|max")
	}
	if m.Channel == "" {
		return validationError("missing_channel", "channel must be non-empty")
	}
	if len(m.Actions) > MaxActions {
		return validationError("too_many_actions", "actions supports at most 3 entries")
	}
	for i, a := range m.Actions {
		if a.ID == "" || a.Title == "" {
			return validationError("invalid_action", "actions["+itoa(i)+"] needs non-empty id and title")
		}
	}
	if m.ImageURL != "" {
		if !strings.HasPrefix(strings.ToLower(m.ImageURL), "https://") {
			return validationError("invalid_image_url", "image_url must be an https URL")
		}
	}
	return nil
}

// DataPayload renders the versioned data payload shipped alongside the
// visible notification. String-only values keep it FCM-data compatible.
// Unknown kinds flow through untouched: the app keys generic rendering off
// "kind" with "v" gating future shape changes.
func (m Message) DataPayload(notificationID string) map[string]string {
	return map[string]string{
		"v":               PayloadVersion,
		"notification_id": notificationID,
		"kind":            m.Kind,
		"title":           m.Title,
		"body":            m.Body,
		"priority":        m.Priority,
		"channel":         m.Channel,
		"image_url":       m.ImageURL,
	}
}

// Sender delivers one Message to one resolved Device.
type Sender interface {
	Send(ctx context.Context, device Device, msg Message) error
}

// NoopSender drops every delivery successfully. Used for local dev, CI,
// and contract verification (curl expects queued/ok without side effects).
type NoopSender struct{}

// Send implements Sender.
func (NoopSender) Send(_ context.Context, _ Device, _ Message) error { return nil }

// LogSender records deliveries to the process log and succeeds. It is the
// default driver: visible behavior with no credentials or network.
type LogSender struct{}

// Send implements Sender.
func (LogSender) Send(_ context.Context, device Device, msg Message) error {
	log.Printf("notify: kind=%s priority=%s channel=%s device=%s title=%q body=%q actions=%d",
		msg.Kind, msg.Priority, msg.Channel, device.ID, msg.Title, msg.Body, len(msg.Actions))
	return nil
}

// FCMSender delivers via FCM HTTPv1. Skeleton only: it carries resolved
// configuration and fails closed until the Wave-2 implementation lands.
//
// TODO (Wave-2 push): mint an OAuth2 access token from the service-account
// JSON at CredentialsFile, then POST the versioned data payload from
// Message.DataPayload to
// https://fcm.googleapis.com/v1/projects/<projectID>/messages:send with the
// device registration token (pairing PR adds token storage). Depends on:
// FCM_PROJECT_ID, FCM_CREDENTIALS_FILE (path to a mounted service-account
// JSON — never committed, never baked into the image).
type FCMSender struct {
	ProjectID       string
	CredentialsFile string
}

// NewFCMSender builds the skeleton driver from already-resolved config.
func NewFCMSender(projectID, credentialsFile string) *FCMSender {
	return &FCMSender{ProjectID: projectID, CredentialsFile: credentialsFile}
}

// Send implements Sender. Always returns an error until Wave-2 implements
// the HTTPv1 call, so misconfiguration can never silently drop pushes.
func (s *FCMSender) Send(_ context.Context, device Device, _ Message) error {
	if s.ProjectID == "" || s.CredentialsFile == "" {
		return errors.New("notify: FCM driver unconfigured (need FCM_PROJECT_ID and FCM_CREDENTIALS_FILE)")
	}
	// TODO (Wave-2 push): real HTTPv1 send for device " + device.ID.
	return errors.New("notify: FCM HTTPv1 driver not implemented yet (TODO Wave-2 push)")
}

// Driver selection. NOTIFY_DRIVER picks the driver:
//
//	(empty or "log") LogSender — the default, no credentials needed
//	"noop"           NoopSender — silent success for dev/CI/verification
//	"fcm"            FCMSender skeleton — reads FCM_PROJECT_ID and
//	                 FCM_CREDENTIALS_FILE; fails closed until Wave-2
//
// Unknown values fall back to the log driver with a warning so a typo can
// never silently become a black hole.
func SenderFromEnv() Sender {
	switch d := strings.ToLower(strings.TrimSpace(os.Getenv("NOTIFY_DRIVER"))); d {
	case "", "log":
		return LogSender{}
	case "noop":
		return NoopSender{}
	case "fcm":
		return NewFCMSender(os.Getenv("FCM_PROJECT_ID"), os.Getenv("FCM_CREDENTIALS_FILE"))
	default:
		log.Printf("notify: unknown NOTIFY_DRIVER %q, falling back to log driver", d)
		return LogSender{}
	}
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var b [8]byte
	p := len(b)
	for n := i; n > 0; n /= 10 {
		p--
		b[p] = byte('0' + n%10)
	}
	return string(b[p:])
}
