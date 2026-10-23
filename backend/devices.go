// Package main — device pairing, registry, and revocation (P2).
//
// Implements POST /api/v1/enroll exactly per docs/api-contract.md,
// including the "Key bootstrap at enrollment" section:
//
//   - The server generates a random 256-bit key per device at enroll time
//     and returns it ONCE inside the 201 enroll response as device_key
//     (base64), delivered only over TLS alongside device_token.
//   - The key is stored as secret_key on the device row; it is never
//     logged, never returned by any other endpoint, and never embedded in
//     widget payloads or notifications.
//   - Key identification: kid = device_id (v1).
//   - Rotation on re-pair: re-enrolling with the same non-empty fcm_token
//     generates a fresh 256-bit key and overwrites secret_key (plus a fresh
//     device_token); old ciphertexts fail closed by design. No key history.
//
// Enrollment is authorized by a short-lived single-use enroll token (the QR
// payload shown on the login-gated WebUI pairing page) — NOT the admin
// session, NOT a device token. Device Bearer tokens are authenticated with
// AuthenticateDevice (SHA-256 hash compare, constant-time).
//
// Re-pair / 409 note (the contract leaves this to the pairing PR): v1 never
// emits 409. Each QR scan enrolls credentials; a scan whose fcm_token
// matches a live device rotates that row in place (201 with fresh
// device_token + device_key), otherwise a new device row is created.
// Name collisions across distinct physical phones must not block pairing,
// so device_name is never a dedup key. 409 is reserved for future
// stable-device-identity dedup.
package main

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"
	"unicode/utf8"
)

// EnrollTokenTTL is how long a single-use pairing token from the WebUI QR
// page stays valid.
const EnrollTokenTTL = 15 * time.Minute

// maxOutstandingEnrollTokens bounds stored unused tokens; issuing beyond
// this drops the oldest (expired ones are always pruned on issue).
const maxOutstandingEnrollTokens = 10

// Device is the authenticated identity of a paired phone. It carries no
// secret material: token hashes and secret_keys never leave the DB.
type Device struct {
	ID        string
	Name      string
	CreatedAt time.Time
}

// DeviceInfo is the admin-facing registry row. Secret-free by construction.
type DeviceInfo struct {
	ID        string `json:"device_id"`
	Name      string `json:"device_name"`
	CreatedAt string `json:"created_at"`
	Revoked   bool   `json:"revoked"`
	RevokedAt string `json:"revoked_at,omitempty"`
}

// Sentinel errors from AuthenticateDevice.
var (
	ErrDeviceAuthMissing = errors.New("missing device bearer token")
	ErrDeviceAuthInvalid = errors.New("invalid device bearer token")
)

// deviceDB is set once at boot (see main.go). Handlers fail closed (500)
// if it is nil so a miswired server never fails open.
var deviceDB *sql.DB

// SetDeviceDB wires the opened database for the pairing handlers.
func SetDeviceDB(db *sql.DB) {
	deviceDB = db
}

func utcNow() time.Time {
	return time.Now().UTC()
}

func formatTime(t time.Time) string {
	return t.UTC().Format(time.RFC3339)
}

// maxDeviceNameBytes bounds stored device names. Truncation is
// rune-boundary safe so multi-byte UTF-8 names are never split into
// invalid bytes.
const maxDeviceNameBytes = 200

// truncateNameBytes cuts s to at most max bytes without splitting a UTF-8
// rune. ASCII-heavy names keep byte-exact behavior; emoji/CJK names lose
// whole runes instead of corrupting.
func truncateNameBytes(s string, max int) string {
	if len(s) <= max {
		return s
	}
	t := s[:max]
	for len(t) > 0 && !utf8.ValidString(t) {
		t = t[:len(t)-1]
	}
	return t
}

func randomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, nil
}

func sha256Hex(s string) string {
	sum := sha256.Sum256([]byte(s))
	return hex.EncodeToString(sum[:])
}

// writeAPIError renders {"error": "<code>"} per docs/api-contract.md.
func writeAPIError(w http.ResponseWriter, status int, code string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code})
}

// IssueEnrollToken creates a short-lived single-use pairing token. Only the
// SHA-256 hash is stored; the raw token is returned once for QR display.
func IssueEnrollToken(db *sql.DB, ttl time.Duration) (rawToken string, expiresAt time.Time, err error) {
	raw, err := randomBytes(32)
	if err != nil {
		return "", time.Time{}, err
	}
	rawToken = base64.RawURLEncoding.EncodeToString(raw)
	now := utcNow()
	expiresAt = now.Add(ttl)

	if _, err := db.Exec(`DELETE FROM enroll_tokens WHERE expires_at <= ? OR used != 0`, formatTime(now)); err != nil {
		return "", time.Time{}, err
	}
	if _, err := db.Exec(`DELETE FROM enroll_tokens WHERE token_hash NOT IN
		(SELECT token_hash FROM enroll_tokens WHERE used = 0 ORDER BY created_at DESC LIMIT ?)`,
		maxOutstandingEnrollTokens); err != nil {
		return "", time.Time{}, err
	}
	if _, err := db.Exec(
		`INSERT INTO enroll_tokens (token_hash, expires_at, used, created_at) VALUES (?, ?, 0, ?)`,
		sha256Hex(rawToken), formatTime(expiresAt), formatTime(now)); err != nil {
		return "", time.Time{}, err
	}
	return rawToken, expiresAt, nil
}

// consumeEnrollToken atomically marks a token used. It reports true only if
// the token existed, was unused, and is unexpired; concurrent double-use
// resolves to exactly one winner (fail-closed for the loser).
func consumeEnrollToken(db *sql.DB, rawToken string) (bool, error) {
	res, err := db.Exec(
		`UPDATE enroll_tokens SET used = 1 WHERE token_hash = ? AND used = 0 AND expires_at > ?`,
		sha256Hex(rawToken), formatTime(utcNow()))
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n == 1, nil
}

// EnrollDevice creates a device row (or rotates the live row holding the
// same fcm_token) with a fresh bearer token and a fresh 256-bit
// per-device key. It returns the server-issued device ID, the raw bearer
// token (base64url, shown once), and the raw 256-bit key (base64 std in the
// response, stored as secret_key, never logged or returned again).
func EnrollDevice(db *sql.DB, deviceName, fcmToken string) (deviceID, rawToken, rawKey string, err error) {
	name := strings.TrimSpace(deviceName)
	if name == "" {
		name = "unnamed-device"
	}
	name = truncateNameBytes(name, maxDeviceNameBytes)
	fcmToken = strings.TrimSpace(fcmToken)
	if len(fcmToken) > 4096 {
		return "", "", "", errors.New("fcm_token too long")
	}

	idBytes, err := randomBytes(16)
	if err != nil {
		return "", "", "", err
	}
	tokBytes, err := randomBytes(32)
	if err != nil {
		return "", "", "", err
	}
	keyBytes, err := randomBytes(32) // 256-bit per-device key
	if err != nil {
		return "", "", "", err
	}
	rawToken = base64.RawURLEncoding.EncodeToString(tokBytes)
	rawKey = base64.StdEncoding.EncodeToString(keyBytes)
	tokenHash := sha256Hex(rawToken)
	now := formatTime(utcNow())

	// Re-pair rotation: the same app instance re-scanning a QR presents the
	// same FCM registration token, so rotate that row in place (fresh
	// token + fresh key, overwriting secret_key).
	if fcmToken != "" {
		var existingID string
		qerr := db.QueryRow(
			`SELECT id FROM devices WHERE fcm_token = ? AND revoked_at IS NULL LIMIT 1`,
			fcmToken).Scan(&existingID)
		switch qerr {
		case nil:
			if _, err := db.Exec(
				`UPDATE devices SET name = ?, token_hash = ?, secret_key = ?, created_at = ? WHERE id = ?`,
				name, tokenHash, keyBytes, now, existingID); err != nil {
				return "", "", "", err
			}
			return existingID, rawToken, rawKey, nil
		case sql.ErrNoRows:
			// Fresh pairing; fall through to INSERT.
		default:
			return "", "", "", qerr
		}
	}

	deviceID = hex.EncodeToString(idBytes)
	if _, err := db.Exec(
		`INSERT INTO devices (id, name, token_hash, secret_key, created_at, revoked_at, fcm_token)
		 VALUES (?, ?, ?, ?, ?, NULL, ?)`,
		deviceID, name, tokenHash, keyBytes, now, fcmToken); err != nil {
		return "", "", "", err
	}
	return deviceID, rawToken, rawKey, nil
}

// ListDevices returns the registry ordered newest-first. Rows contain no
// secret material.
func ListDevices(db *sql.DB) ([]DeviceInfo, error) {
	rows, err := db.Query(
		`SELECT id, name, created_at, revoked_at FROM devices ORDER BY created_at DESC, id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceInfo
	for rows.Next() {
		var d DeviceInfo
		var created string
		var revoked sql.NullString
		if err := rows.Scan(&d.ID, &d.Name, &created, &revoked); err != nil {
			return nil, err
		}
		d.CreatedAt = created
		if revoked.Valid {
			d.Revoked = true
			d.RevokedAt = revoked.String
		}
		out = append(out, d)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if out == nil {
		out = []DeviceInfo{}
	}
	return out, nil
}

// RevokeDevice marks a device revoked; subsequent AuthenticateDevice calls
// fail (immediate effect on the same DB). Idempotent: revoking an already
// revoked device succeeds. Unknown IDs report found=false.
func RevokeDevice(db *sql.DB, id string) (found bool, err error) {
	id = strings.TrimSpace(id)
	if id == "" {
		return false, nil
	}
	res, err := db.Exec(
		`UPDATE devices SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL`,
		formatTime(utcNow()), id)
	if err != nil {
		return false, err
	}
	if n, err := res.RowsAffected(); err != nil {
		return false, err
	} else if n == 1 {
		return true, nil
	}
	var one int
	if err := db.QueryRow(`SELECT 1 FROM devices WHERE id = ?`, id).Scan(&one); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return false, nil
		}
		return false, err
	}
	return true, nil
}

// AuthenticateDevice validates a device Bearer token from the request
// (Authorization: Bearer <token>) against stored SHA-256 hashes. Revoked
// and unknown tokens fail closed.
func AuthenticateDevice(db *sql.DB, r *http.Request) (*Device, error) {
	h := r.Header.Get("Authorization")
	if h == "" {
		return nil, ErrDeviceAuthMissing
	}
	const prefix = "Bearer "
	if len(h) <= len(prefix) || !strings.EqualFold(h[:len(prefix)], prefix) {
		return nil, ErrDeviceAuthMissing
	}
	raw := strings.TrimSpace(h[len(prefix):])
	if raw == "" {
		return nil, ErrDeviceAuthMissing
	}
	want := sha256Hex(raw)
	var d Device
	var storedHash, created string
	err := db.QueryRow(
		`SELECT id, name, token_hash, created_at FROM devices WHERE token_hash = ? AND revoked_at IS NULL`,
		want).Scan(&d.ID, &d.Name, &storedHash, &created)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrDeviceAuthInvalid
		}
		return nil, err
	}
	if subtle.ConstantTimeCompare([]byte(storedHash), []byte(want)) != 1 {
		return nil, ErrDeviceAuthInvalid
	}
	if t, err := time.Parse(time.RFC3339, created); err == nil {
		d.CreatedAt = t
	}
	return &d, nil
}

type enrollRequest struct {
	EnrollToken string `json:"enroll_token"`
	DeviceName  string `json:"device_name"`
	FCMToken    string `json:"fcm_token"`
}

// HandleEnroll serves POST /api/v1/enroll. Auth is the single-use enroll
// token from the WebUI QR — never the admin session, never a device token.
func HandleEnroll(w http.ResponseWriter, r *http.Request) {
	if deviceDB == nil {
		writeAPIError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 1<<16)
	var req enrollRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeAPIError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	if strings.TrimSpace(req.EnrollToken) == "" {
		writeAPIError(w, http.StatusBadRequest, "invalid_request")
		return
	}
	ok, err := consumeEnrollToken(deviceDB, strings.TrimSpace(req.EnrollToken))
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal")
		return
	}
	if !ok {
		writeAPIError(w, http.StatusGone, "enroll_token_invalid")
		return
	}
	deviceID, rawToken, rawKey, err := EnrollDevice(deviceDB, req.DeviceName, req.FCMToken)
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal")
		return
	}
	// Only the opaque device ID is safe to log; the bearer token and the
	// per-device key are shown once below and never logged.
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"device_id":    deviceID,
		"device_token": rawToken,
		"device_key":   rawKey,
	})
}

// HandleIssueEnrollToken serves POST /api/v1/enroll-tokens (admin-gated).
// Additive helper for the WebUI page and curl verification; not part of the
// frozen P0 surface.
func HandleIssueEnrollToken(w http.ResponseWriter, _ *http.Request) {
	if deviceDB == nil {
		writeAPIError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	raw, exp, err := IssueEnrollToken(deviceDB, EnrollTokenTTL)
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	_ = json.NewEncoder(w).Encode(map[string]string{
		"enroll_token": raw,
		"expires_at":   formatTime(exp),
	})
}

// HandleListDevices serves GET /api/v1/devices (admin-gated).
func HandleListDevices(w http.ResponseWriter, _ *http.Request) {
	if deviceDB == nil {
		writeAPIError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	devices, err := ListDevices(deviceDB)
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]any{"devices": devices})
}

// HandleRevokeDevice serves DELETE /api/v1/devices/{id} (admin-gated).
// Revocation takes effect immediately for subsequent device auth.
func HandleRevokeDevice(w http.ResponseWriter, r *http.Request) {
	if deviceDB == nil {
		writeAPIError(w, http.StatusInternalServerError, "server misconfigured")
		return
	}
	found, err := RevokeDevice(deviceDB, r.PathValue("id"))
	if err != nil {
		writeAPIError(w, http.StatusInternalServerError, "internal")
		return
	}
	if !found {
		writeAPIError(w, http.StatusNotFound, "device_not_found")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
