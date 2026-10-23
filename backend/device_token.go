// Package main — device FCM-token backfill endpoint (Wave-2 push).
//
// POST /api/v1/device/fcm-token lets a paired phone upload (or refresh)
// its FCM registration token outside pairing: the app calls it right after
// onNewToken rotation and opportunistically on the next widgets poll when
// the local token is present. Auth is the per-device Bearer token — never
// the admin session, never an enroll token.
//
// Why a separate endpoint instead of riding the widgets poll: the poll
// handlers (widgets_serve.go) stay untouched by design — no header sniffing
// in the poll path, no change to poll/wipe semantics. The backfill only
// rewrites devices.fcm_token (+updated_at) when the presented token differs
// from the stored one, so redundant calls are cheap no-ops.
//
// A device row whose stored token dies server-side (FCM UNREGISTERED /
// INVALID_ARGUMENT during fan-out) heals through this same path: the send
// failure is recorded as a failed history row with a re-register signal and
// the device keeps polling; the next successful backfill restores delivery.
// The server never revokes on send failures.
package main

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"strings"
)

// maxFCMTokenLen mirrors the enroll bound in EnrollDevice: FCM registration
// tokens are ~150 chars; 4096 rejects garbage without tripping real ones.
const maxFCMTokenLen = 4096

type fcmTokenUpdateRequest struct {
	FCMToken string `json:"fcm_token"`
}

// UpdateDeviceFCMToken stores token for a live device, returning changed=true
// when the stored value actually moved (re-pair parity: same normalization
// as EnrollDevice — trimmed, length-capped). Unknown/revoked devices report
// found=false. updated_at stamps the rotation lifecycle (column ensured
// idempotently, same as EnrollDevice).
func UpdateDeviceFCMToken(db *sql.DB, deviceID, fcmToken string) (changed bool, found bool, err error) {
	if err := ensureDevicesUpdatedAtColumn(db); err != nil {
		return false, false, err
	}
	fcmToken = strings.TrimSpace(fcmToken)
	if fcmToken == "" || len(fcmToken) > maxFCMTokenLen {
		return false, false, sql.ErrNoRows // caller maps to 400 before reaching here; kept total
	}
	var current sql.NullString
	qerr := db.QueryRow(
		`SELECT fcm_token FROM devices WHERE id = ? AND revoked_at IS NULL`,
		deviceID).Scan(&current)
	switch {
	case qerr == sql.ErrNoRows:
		return false, false, nil
	case qerr != nil:
		return false, false, qerr
	}
	if current.Valid && current.String == fcmToken {
		return false, true, nil
	}
	res, err := db.Exec(
		`UPDATE devices SET fcm_token = ?, updated_at = ? WHERE id = ? AND revoked_at IS NULL`,
		fcmToken, formatTime(utcNow()), deviceID)
	if err != nil {
		return false, false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, false, err
	}
	return n == 1, n == 1, nil
}

// HandleUpdateFCMToken serves POST /api/v1/device/fcm-token. Device Bearer
// auth only (401 without/unknown; revoked devices fail closed the same way
// — no oracle between "unknown" and "revoked"). Malformed bodies are 400
// invalid_request; success is 200 {"updated": bool} (false = already stored).
func HandleUpdateFCMToken(w http.ResponseWriter, r *http.Request) {
	if deviceDB == nil {
		writeAPIError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	dev, err := AuthenticateDevice(deviceDB, r)
	if err != nil {
		writeAPIError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1<<16)
	var req fcmTokenUpdateRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeAPIError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	token := strings.TrimSpace(req.FCMToken)
	if token == "" || len(token) > maxFCMTokenLen {
		writeAPIError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	changed, found, err := UpdateDeviceFCMToken(deviceDB, dev.ID, token)
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal")
		return
	}
	if !found {
		// Authenticated a moment ago but the row vanished/was revoked
		// between auth and update: fail closed without saying which.
		writeAPIError(w, http.StatusUnauthorized, "unauthorized")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]bool{"updated": changed})
}
