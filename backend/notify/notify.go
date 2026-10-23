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
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/url"
	"os"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
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
	// MaxDeviceIDs caps an explicit device_ids target so the lookup never
	// builds an unbounded SQL IN list (sqlite SQLITE_MAX_VARIABLE_NUMBER).
	MaxDeviceIDs = 500
	// MaxRequestBodyBytes caps the notify request body (handler enforces via
	// http.MaxBytesReader); 1 MiB is orders of magnitude above a legitimate
	// notify request and bounds the storage/CPU amplifier of fan-out.
	MaxRequestBodyBytes = 1 << 20
	// Field length caps (runes): long enough for real notifications, short
	// enough to bound history rows and log lines.
	MaxKindLen        = 64
	MaxTitleLen       = 500
	MaxBodyLen        = 4096
	MaxChannelLen     = 64
	MaxImageURLLen    = 2048
	MaxActionIDLen    = 64
	MaxActionTitleLen = 200
	// MaxDataPayloadBytes caps the worst-case marshaled FCM data payload.
	// FCM data messages are limited to ~4KB; AES-256-GCM envelopes expand
	// secret title/body (base64 + nonce + 16-byte tag + envelope JSON), so
	// Validate measures the worst case including that expansion and leaves
	// headroom for transport framing. Over-cap messages fail closed with
	// payload_too_long — the operator shortens the text, the app never
	// sees a truncated ciphertext.
	MaxDataPayloadBytes = 3500
	// MaxSecretKidLen bounds the kid length assumed when estimating a
	// secret payload for {"all":true} targets (explicit device_ids use
	// their real lengths). Server-issued device ids are 32 hex chars; 64
	// keeps the estimate conservative without rejecting real traffic.
	MaxSecretKidLen = 64
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
	ID    string `json:"id"`
	Title string `json:"title"`
}

// Target selects fan-out recipients: exactly one of All or DeviceIDs.
type Target struct {
	All       bool
	DeviceIDs []string
}

// Message is a validated enqueue request: one logical notification fanned
// out to every resolved Device. Secret marks operator-declared
// secret-bearing content: when true the server per-device encrypts title
// and body (see SecretDataPayload) and the fan-out payload carries P5
// envelopes instead of plaintext. Non-secret messages are unchanged.
type Message struct {
	Target   Target
	Kind     string
	Title    string
	Body     string
	Priority string
	Channel  string
	Actions  []Action
	ImageURL string
	Secret   bool
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
// Note: Channel has a contract default (ApplyDefaults), so by the time
// Validate runs it is always non-empty — no missing_channel check needed.
func (m Message) Validate() error {
	if m.Target.All && len(m.Target.DeviceIDs) > 0 {
		return validationError("ambiguous_target", `target must be exactly one of {"all":true} or {"device_ids":[...]}`)
	}
	if !m.Target.All && len(m.Target.DeviceIDs) == 0 {
		return validationError("empty_target", `target must be exactly one of {"all":true} or non-empty {"device_ids":[...]}`)
	}
	if len(m.Target.DeviceIDs) > MaxDeviceIDs {
		return validationError("too_many_targets", "device_ids supports at most 500 entries")
	}
	if utf8.RuneCountInString(m.Kind) > MaxKindLen {
		return validationError("kind_too_long", "kind must be at most 64 characters")
	}
	if m.Title == "" {
		return validationError("missing_title", "title must be non-empty")
	}
	if utf8.RuneCountInString(m.Title) > MaxTitleLen {
		return validationError("title_too_long", "title must be at most 500 characters")
	}
	if m.Body == "" {
		return validationError("missing_body", "body must be non-empty")
	}
	if utf8.RuneCountInString(m.Body) > MaxBodyLen {
		return validationError("body_too_long", "body must be at most 4096 characters")
	}
	if !ValidPriorities[m.Priority] {
		return validationError("invalid_priority", "priority must be one of min|low|default|high|max")
	}
	if utf8.RuneCountInString(m.Channel) > MaxChannelLen {
		return validationError("channel_too_long", "channel must be at most 64 characters")
	}
	if len(m.Actions) > MaxActions {
		return validationError("too_many_actions", "actions supports at most 3 entries")
	}
	for i, a := range m.Actions {
		if a.ID == "" || a.Title == "" {
			return validationError("invalid_action", "actions["+strconv.Itoa(i)+"] needs non-empty id and title")
		}
		if utf8.RuneCountInString(a.ID) > MaxActionIDLen || utf8.RuneCountInString(a.Title) > MaxActionTitleLen {
			return validationError("action_too_long", "actions["+strconv.Itoa(i)+"] id must be at most 64 and title at most 200 characters")
		}
	}
	if m.ImageURL != "" {
		if utf8.RuneCountInString(m.ImageURL) > MaxImageURLLen {
			return validationError("invalid_image_url", "image_url must be at most 2048 characters")
		}
		u, err := url.Parse(m.ImageURL)
		if err != nil || !strings.EqualFold(u.Scheme, "https") || u.Host == "" {
			return validationError("invalid_image_url", "image_url must be an https URL with a host")
		}
	}
	if size := m.estimatedPayloadBytes(); size > MaxDataPayloadBytes {
		return validationError("payload_too_large", "data payload exceeds the FCM size budget: shorten title/body")
	}
	return nil
}

// estimatedPayloadBytes measures the worst-case marshaled data payload for
// this message, including secret-envelope expansion when Secret is set. It
// uses a fixed 32-char placeholder notification id (the length
// newNotificationID mints) and, for secret messages, envelope sizes
// computed from the real plaintext lengths with the longest known kid
// (explicit targets) or MaxSecretKidLen ({"all":true}). JSON escaping can
// only shrink the estimate (base64/hex need no escaping), so the real
// per-device payload never exceeds this by more than a few bytes — the
// 3500-vs-4096 headroom absorbs that slack.
func (m Message) estimatedPayloadBytes() int {
	const placeholderID = "0123456789abcdef0123456789abcdef"
	payload := m.DataPayload(placeholderID)
	if m.Secret {
		kidLen := MaxSecretKidLen
		if !m.Target.All {
			for _, id := range m.Target.DeviceIDs {
				if len(id) > kidLen {
					kidLen = len(id)
				}
			}
		}
		payload["title_env"] = strings.Repeat("A", secretEnvelopeSize(kidLen, len(m.Title)))
		payload["body_env"] = strings.Repeat("A", secretEnvelopeSize(kidLen, len(m.Body)))
		delete(payload, "title")
		delete(payload, "body")
		payload["secret"] = "true"
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return MaxDataPayloadBytes + 1
	}
	return len(raw)
}

// secretEnvelopeSize predicts the JSON length of one P5 envelope produced
// by SecretDataPayload for plaintextLen bytes under a kid of kidLen bytes:
// {"alg":"AES-256-GCM","kid":"<kid>","nonce":"<16>","ciphertext":"<b64>"}
// with b64 = 4*ceil((plaintext+tag)/3) and a 16-char nonce field.
func secretEnvelopeSize(kidLen, plaintextLen int) int {
	ctB64Len := 4 * ((plaintextLen + crypto.TagSize + 2) / 3)
	return len(`{"alg":"AES-256-GCM","kid":"`) + kidLen +
		len(`","nonce":"`) + 16 +
		len(`","ciphertext":"`) + ctB64Len + len(`"}`)
}

// DataPayload renders the versioned data payload shipped alongside the
// visible notification. String-only values keep it FCM-data compatible.
// Unknown kinds flow through untouched: the app keys generic rendering off
// "kind" with "v" gating future shape changes. Actions ride as flat
// action_<i>_id / action_<i>_title pairs (i = 0..2, at most MaxActions, so
// no JSON parsing is needed on the app); absent entirely when empty so old
// readers see no new keys beyond the documented version "v".
//
// Secret messages (Message.Secret) MUST NOT use this method for fan-out:
// use SecretDataPayload, which replaces plaintext title/body with per-device
// P5 envelopes under "title_env"/"body_env" plus "secret":"true". Shipping
// a secret message through DataPayload would put operator plaintext on the
// FCM leg, in sender logs, and in any transport-level retention — the exact
// leak this package exists to prevent.
func (m Message) DataPayload(notificationID string) map[string]string {
	payload := map[string]string{
		"v":               PayloadVersion,
		"notification_id": notificationID,
		"kind":            m.Kind,
		"title":           m.Title,
		"body":            m.Body,
		"priority":        m.Priority,
		"channel":         m.Channel,
		"image_url":       m.ImageURL,
	}
	for i, a := range m.Actions {
		payload["action_"+strconv.Itoa(i)+"_id"] = a.ID
		payload["action_"+strconv.Itoa(i)+"_title"] = a.Title
	}
	return payload
}

// Secret payload keys: a secret fan-out payload carries P5 envelopes here
// (not plaintext) plus the "secret":"true" flag so the app knows to
// decrypt. Old app versions ignore these unknown keys and render the
// blank-fallback (app-name title) — never plaintext, never a crash.
const (
	SecretFlagKey = "secret"
	TitleEnvKey   = "title_env"
	BodyEnvKey    = "body_env"
)

// SecretDataPayload renders the per-device data payload for a secret
// message: title and body are each sealed with backend/crypto under the
// target device's key (kid == deviceID, binding =
// NotifyBinding(notificationID, "title"|"body"), fresh random nonce per
// field via Encrypt) and shipped as P5 envelope JSON strings. Kind,
// priority, channel, image_url, and actions ride in the clear exactly as in
// DataPayload — only title/body are secret-bearing. Any encrypt or binding
// failure returns an error and the caller must record a failed delivery
// (the app maps undecryptable secrets to its needs-refresh UI with no
// oracle — see SecretCrypto on Android).
func (m Message) SecretDataPayload(notificationID, deviceID string, key [crypto.KeySize]byte) (map[string]string, error) {
	payload := m.DataPayload(notificationID)
	delete(payload, "title")
	delete(payload, "body")
	for field, plaintext := range map[string]string{"title": m.Title, "body": m.Body} {
		binding, err := crypto.NotifyBinding(notificationID, field)
		if err != nil {
			return nil, err
		}
		env, err := crypto.Encrypt(key, deviceID, binding, []byte(plaintext))
		if err != nil {
			return nil, err
		}
		raw, err := json.Marshal(env)
		if err != nil {
			return nil, err
		}
		if field == "title" {
			payload[TitleEnvKey] = string(raw)
		} else {
			payload[BodyEnvKey] = string(raw)
		}
	}
	payload[SecretFlagKey] = "true"
	return payload, nil
}

// Sender delivers one precomputed data payload to one resolved Device. The
// handler builds the payload (DataPayload or SecretDataPayload) before
// calling Send, so drivers only ever handle the wire form: for secret
// messages the sender never sees title/body plaintext — not in FCM posts,
// not in logs. Senders MUST NOT log payload values beyond notification_id
// and kind (see LogSender); full content dumps are gated behind
// NOTIFY_DEBUG=1 at the handler layer, never in drivers.
type Sender interface {
	Send(ctx context.Context, device Device, payload map[string]string) error
}

// DebugEnabled reports whether NOTIFY_DEBUG=1. When set, the handler may
// log full notify content for local debugging; drivers stay metadata-only
// regardless. Never set in production: it re-enables the plaintext logging
// this package otherwise forbids.
func DebugEnabled() bool {
	return os.Getenv("NOTIFY_DEBUG") == "1"
}

// NoopSender drops every delivery successfully. Used for local dev, CI,
// and contract verification (curl expects queued/ok without side effects).
type NoopSender struct{}

// Send implements Sender.
func (NoopSender) Send(_ context.Context, _ Device, _ map[string]string) error { return nil }

// LogSender records deliveries to the process log and succeeds. It is the
// default driver: visible behavior with no credentials or network.
//
// Secrecy: logs carry ONLY notification_id, device id, and kind — never
// title/body/image_url or envelope contents, for secret and non-secret
// messages alike. Operators needing content visibility set NOTIFY_DEBUG=1,
// which enables full dumps at the handler layer (never here).
type LogSender struct{}

// Send implements Sender.
func (LogSender) Send(_ context.Context, device Device, payload map[string]string) error {
	log.Printf("notify: notification_id=%q device=%q kind=%q",
		payload["notification_id"], device.ID, payload["kind"])
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
// The payload (not operator plaintext) is what will be POSTed, so the
// secret fan-out shape is already safe to pass through here.
func (s *FCMSender) Send(_ context.Context, device Device, _ map[string]string) error {
	if s.ProjectID == "" || s.CredentialsFile == "" {
		return errors.New("notify: FCM driver unconfigured (need FCM_PROJECT_ID and FCM_CREDENTIALS_FILE)")
	}
	// TODO (Wave-2 push): real HTTPv1 send for device " + device.ID.
	return errors.New("notify: FCM HTTPv1 driver not implemented yet (TODO Wave-2 push)")
}

// SenderFromEnv resolves the driver from caller-provided config (main wires
// Config's FCM fields through here; driver comes from NOTIFY_DRIVER).
// Selection:
//
//	(""/"log") LogSender — the default, no credentials needed
//	"noop"     NoopSender — silent success for dev/CI/verification
//	"fcm"      FCMSender skeleton — fails closed until Wave-2 push
//
// An unknown driver is an error so startup fails fast — a typo must never
// silently become a black hole.
func SenderFromEnv(driver, projectID, credentialsFile string) (Sender, error) {
	switch d := strings.ToLower(strings.TrimSpace(driver)); d {
	case "", "log":
		return LogSender{}, nil
	case "noop":
		return NoopSender{}, nil
	case "fcm":
		return NewFCMSender(projectID, credentialsFile), nil
	default:
		return nil, fmt.Errorf("notify: unknown NOTIFY_DRIVER %q (want log|noop|fcm)", d)
	}
}
