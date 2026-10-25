// Package webui serves the login-gated pairing page: a QR code encoding a
// single-use enroll token, the device registry, and revoke buttons.
//
// The package takes a Store interface so it stays independent of the main
// package's sqlite wiring; the concrete adapter lives in the main package
// (see pair_wire.go). Admin gating is applied by the caller when mounting
// the handlers (admin gating is applied by the caller via RequireAdmin in
// package main's pair_wire.go).
//
// NOTE (merge resolution P1+P2): this file held the P2 pairing Store
// interface, originally added as backend/webui/webui.go on the pairing
// branch, which collided add/add with the P1 login file of the same name.
// P1's login/auth file keeps the webui.go name; the pairing interface
// lives here.
package webui

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"
)

// DeviceInfo is one admin-facing registry row. Secret-free by construction:
// no token hashes, no keys, no raw tokens.
type DeviceInfo struct {
	ID        string
	Name      string
	CreatedAt string
	Revoked   bool
	RevokedAt string
}

// Token status vocabulary for the live-pairing poll behind the QR panel.
// The main package's EnrollTokenStatus reports these same literals; the
// adapter degrades anything else to TokenUnknown.
const (
	TokenLive    = "live"
	TokenUsed    = "used"
	TokenExpired = "expired"
	TokenUnknown = "unknown"
)

// TokenStatus is one enroll token's poll state. DeviceID/DeviceName name
// the paired phone once Status is used (empty when the link is missing —
// e.g. the device row was purged after pairing). ExpiresAt/ExpiresEpoch
// are set only while live.
type TokenStatus struct {
	Status       string `json:"status"`
	DeviceID     string `json:"device_id"`
	DeviceName   string `json:"device_name"`
	ExpiresAt    string `json:"expires_at"`
	ExpiresEpoch int64  `json:"expires_epoch"`
}

// ErrInvalidDeviceName reports a rename rejected by ValidateDeviceName.
// Handlers map it to an in-page operator error (?error=invalid_name),
// never a 500: the name came from operator input.
var ErrInvalidDeviceName = errors.New("invalid device name")

// maxDeviceNameLen bounds operator-supplied device names in bytes. It
// mirrors main.maxDeviceNameBytes (kept as a mirrored constant, same as
// flashName and webuiSessionCookieName, so webui stays decoupled from
// main): names that fit here store byte-exact, never truncated.
const maxDeviceNameLen = 200

// ValidateDeviceName normalizes a rename candidate: trimmed, non-empty, at
// most maxDeviceNameLen bytes, valid UTF-8, no ASCII control characters
// (names stay single-line table cells). Emoji/CJK pass through —
// enrollment already stores those. The canonical check for renames; the
// main-package RenameDevice applies it before touching the registry.
func ValidateDeviceName(name string) (string, error) {
	name = strings.TrimSpace(name)
	if name == "" || len(name) > maxDeviceNameLen || !utf8.ValidString(name) {
		return "", ErrInvalidDeviceName
	}
	for _, r := range name {
		if r < 0x20 || r == 0x7f {
			return "", ErrInvalidDeviceName
		}
	}
	return name, nil
}

// Store abstracts the pairing backend for the page handlers.
type Store interface {
	IssueEnrollToken(ctx context.Context, ttl time.Duration) (rawToken string, expiresAt time.Time, err error)
	ListDevices(ctx context.Context) ([]DeviceInfo, error)
	RevokeDevice(ctx context.Context, id string) (found bool, err error)
	// PurgeDevice hard-deletes a REVOKED device row. Active rows must not
	// be purged (implementations report found=false for them); unknown IDs
	// report found=false. Safe by construction: a purged token is simply
	// unknown afterwards, and unknown tokens 401 exactly like revoked ones.
	PurgeDevice(ctx context.Context, id string) (found bool, err error)
	// RenameDevice renames a registry row (active or revoked), idempotently
	// (renaming to the current name succeeds). Unknown IDs report
	// found=false; invalid names report ErrInvalidDeviceName.
	RenameDevice(ctx context.Context, id, name string) (found bool, err error)
	// TokenStatus reports one enroll token's live-pairing poll state for
	// the QR panel (see TokenStatus). Unknown hashes report
	// TokenUnknown, never an error.
	TokenStatus(ctx context.Context, tokenHash string) (TokenStatus, error)
}
