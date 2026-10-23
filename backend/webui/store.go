// Package webui serves the login-gated pairing page: a QR code encoding a
// single-use enroll token, the device registry, and revoke buttons.
//
// The package takes a Store interface so it stays independent of the main
// package's sqlite wiring; the concrete adapter lives in the main package
// (see pair_wire.go). Admin gating is applied by the caller when mounting
// the handlers (requireAdminPairingStub until Wave-2 P8 lands RequireAdmin).
//
// NOTE (merge resolution P1+P2): this file held the P2 pairing Store
// interface, originally added as backend/webui/webui.go on the pairing
// branch, which collided add/add with the P1 login file of the same name.
// P1's login/auth file keeps the webui.go name; the pairing interface
// lives here.
package webui

import (
	"context"
	"time"
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

// Store abstracts the pairing backend for the page handlers.
type Store interface {
	IssueEnrollToken(ctx context.Context, ttl time.Duration) (rawToken string, expiresAt time.Time, err error)
	ListDevices(ctx context.Context) ([]DeviceInfo, error)
	RevokeDevice(ctx context.Context, id string) (found bool, err error)
}
