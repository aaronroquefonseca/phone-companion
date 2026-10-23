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
//     Rotation preserves the row's original created_at and stamps updated_at
//     (added idempotently; see ensureDevicesUpdatedAtColumn). The matched
//     fcm_token is rewritten on rotation so the stored value is always the
//     normalized (trimmed) token the client last presented.
//
// Enrollment is authorized by a short-lived single-use enroll token (the QR
// payload shown on the login-gated WebUI pairing page) — NOT the admin
// session, NOT a device token. Device Bearer tokens are authenticated with
// AuthenticateDevice (SHA-256 hash equality in SQL; the post-query
// ConstantTimeCompare was removed as theater — see AuthenticateDevice).
//
// Re-pair / 409 note (the contract leaves this to the pairing PR): v1 never
// emits 409. Each QR scan enrolls credentials; a scan whose fcm_token
// matches a live device rotates that row in place (201 with fresh
// device_token + device_key), otherwise a new device row is created.
// Name collisions across distinct physical phones must not block pairing,
// so device_name is never a dedup key. 409 is reserved for future
// stable-device-identity dedup.
//
// Empty-FCM note: an empty fcm_token deliberately skips the rotation lookup,
// so every such enroll inserts a distinct row even when device_name repeats.
// This is accepted (not a bug): FCM registration tokens are the only v1
// dedup key, and two physical phones may legitimately share a name (see
// TestRePairRotatesKey and TestEmptyFCMCreatesDistinctRows). Operators spot
// those duplicates with FindSiblingDeviceIDs and revoke explicitly; there is
// no auto-revoke because revocation is destructive and must stay explicit.
package main

import (
	"crypto/rand"
	"crypto/sha256"
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
// UpdatedAt is the last re-pair rotation ("" when never rotated); it is
// omitempty so older readers ignore it.
type DeviceInfo struct {
	ID        string `json:"device_id"`
	Name      string `json:"device_name"`
	CreatedAt string `json:"created_at"`
	UpdatedAt string `json:"updated_at,omitempty"`
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

// ensureDevicesUpdatedAtColumn adds the devices.updated_at column if it is
// missing, so rotation lifecycle works on databases migrated before this
// column existed (P0 databases, older P2 databases). Idempotent: a second
// call is a no-op. It lives here — not in db.go Migrate — so this change
// stays inside devices.go; writers call it lazily before touching the
// column and readers call it before selecting it.
func ensureDevicesUpdatedAtColumn(db *sql.DB) error {
	rows, err := db.Query(`PRAGMA table_info(devices)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	var cid int
	var name, ctype string
	var notnull, pk int
	var dflt sql.NullString
	for rows.Next() {
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			return err
		}
		if name == "updated_at" {
			return rows.Err()
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	_, err = db.Exec(`ALTER TABLE devices ADD COLUMN updated_at TEXT`)
	return err
}

// PruneEnrollTokens deletes expired and already-used enroll-token rows and
// reports how many rows were removed. IssueEnrollToken calls it on every
// issue (every-Nth pruning with N=1): the table is tiny — at most
// maxOutstandingEnrollTokens live rows plus transient expired/used ones — so
// one small DELETE per issue costs less than a background ticker goroutine
// that could leak past shutdown (same no-ticker rationale as widgets.go).
func PruneEnrollTokens(db *sql.DB, now time.Time) (int64, error) {
	res, err := db.Exec(`DELETE FROM enroll_tokens WHERE expires_at <= ? OR used != 0`, formatTime(now))
	if err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	return n, nil
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

	if _, err := PruneEnrollTokens(db, now); err != nil {
		return "", time.Time{}, err
	}
	if _, err := db.Exec(
		`INSERT INTO enroll_tokens (token_hash, expires_at, used, created_at) VALUES (?, ?, 0, ?)`,
		sha256Hex(rawToken), formatTime(expiresAt), formatTime(now)); err != nil {
		return "", time.Time{}, err
	}
	// Trim AFTER the insert so the cap actually holds: trimming before the
	// insert (as v1 did) steadied at cap+1. rowid order keeps the newest
	// rows — created_at has only second precision, so ties would evict
	// arbitrarily.
	if _, err := db.Exec(`DELETE FROM enroll_tokens WHERE token_hash NOT IN
		(SELECT token_hash FROM enroll_tokens WHERE used = 0 ORDER BY rowid DESC LIMIT ?)`,
		maxOutstandingEnrollTokens); err != nil {
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
//
// Re-pair lifecycle: a non-empty fcm_token matching a live row rotates that
// row in place — name, token_hash, secret_key, and fcm_token are rewritten
// (fcm_token with the normalized trimmed input, normally identical to the
// matched value), created_at is preserved, and updated_at is stamped. Fresh
// pairings insert both timestamps. An empty fcm_token never matches: every
// such enroll inserts a distinct row (see the package note).
func EnrollDevice(db *sql.DB, deviceName, fcmToken string) (deviceID, rawToken, rawKey string, err error) {
	if err := ensureDevicesUpdatedAtColumn(db); err != nil {
		return "", "", "", err
	}
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
	// token + fresh key, overwriting secret_key). created_at is preserved;
	// updated_at records the rotation. fcm_token is rewritten with the
	// normalized input so the stored value never drifts from what the
	// client last presented.
	if fcmToken != "" {
		var existingID string
		qerr := db.QueryRow(
			`SELECT id FROM devices WHERE fcm_token = ? AND revoked_at IS NULL LIMIT 1`,
			fcmToken).Scan(&existingID)
		switch qerr {
		case nil:
			if _, err := db.Exec(
				`UPDATE devices SET name = ?, token_hash = ?, secret_key = ?, fcm_token = ?, updated_at = ? WHERE id = ?`,
				name, tokenHash, keyBytes, fcmToken, now, existingID); err != nil {
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
		`INSERT INTO devices (id, name, token_hash, secret_key, created_at, updated_at, revoked_at, fcm_token)
		 VALUES (?, ?, ?, ?, ?, ?, NULL, ?)`,
		deviceID, name, tokenHash, keyBytes, now, now, fcmToken); err != nil {
		return "", "", "", err
	}
	return deviceID, rawToken, rawKey, nil
}

// ListDevices returns the registry ordered newest-first. Rows contain no
// secret material.
func ListDevices(db *sql.DB) ([]DeviceInfo, error) {
	if err := ensureDevicesUpdatedAtColumn(db); err != nil {
		return nil, err
	}
	rows, err := db.Query(
		`SELECT id, name, created_at, updated_at, revoked_at FROM devices ORDER BY created_at DESC, id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DeviceInfo
	for rows.Next() {
		var d DeviceInfo
		var created sql.NullString
		var updated sql.NullString
		var revoked sql.NullString
		if err := rows.Scan(&d.ID, &d.Name, &created, &updated, &revoked); err != nil {
			return nil, err
		}
		// created is NULL only for rows predating the P0 pairing migration;
		// surface "" rather than failing the whole registry read.
		if created.Valid {
			d.CreatedAt = created.String
		}
		if updated.Valid {
			d.UpdatedAt = updated.String
		}
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

// FindSiblingDeviceIDs lists live device IDs sharing deviceName, excluding
// exceptID. It is the documented query for the empty-FCM duplicate case:
// two physical phones paired with the same name (or any name collision)
// show up here so an operator can inspect and revoke explicitly via
// RevokeDevice. It never revokes by itself — revocation is destructive and
// stays an explicit operator action. Only live rows (revoked_at IS NULL)
// are returned; revoked rows need no action.
func FindSiblingDeviceIDs(db *sql.DB, deviceName, exceptID string) ([]string, error) {
	name := strings.TrimSpace(deviceName)
	if name == "" {
		return []string{}, nil
	}
	rows, err := db.Query(
		`SELECT id FROM devices WHERE name = ? AND id != ? AND revoked_at IS NULL ORDER BY created_at DESC, id DESC`,
		name, exceptID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id string
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out = append(out, id)
	}
	if err := rows.Err(); err != nil {
		return nil, err
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
//
// Compare hygiene note: the lookup filters on token_hash equality in SQL,
// so a follow-up ConstantTimeCompare of the returned hash against the
// presented hash would be theater — the database already branched on
// equality, and both sides are deterministic SHA-256 hex digests of a
// 256-bit random token. Timing leakage here is negligible (unguessable
// token space, per-IP issuance rate limiting in pair_wire.go), and the
// constant-time primitive is spent where it matters: HMAC verification in
// session.go and CSRF comparison in webui. (WebUI username pre-hashing is
// left for the auth batch that owns webui/*.)
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
	var created string
	err := db.QueryRow(
		`SELECT id, name, created_at FROM devices WHERE token_hash = ? AND revoked_at IS NULL`,
		want).Scan(&d.ID, &d.Name, &created)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, ErrDeviceAuthInvalid
		}
		return nil, err
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
//
// Status split (deliberate, kept): 400 invalid_request for a missing or
// malformed body versus 410 enroll_token_invalid for an unknown, used, or
// expired token. This oracle is accepted as low-risk: tokens are 256-bit
// unguessable, single-use, expire in 15 minutes, and issuance is
// rate-limited per IP (see pair_wire.go), so distinguishing "bad format"
// from "bad token" gives an attacker nothing to enumerate.
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
