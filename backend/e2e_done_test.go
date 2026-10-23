// P12 E2E done-criteria verification harness (no phones, no network/docker).
//
// Proves the merged P0-P10 surface end to end through HTTP only:
//  1. Two devices pair via the operator-issued enroll-token API flow.
//  2. Targeted notify reaches exactly the targeted device (asserted via
//     notification_history rows per device), broadcast reaches both, and a
//     revoked device gets nothing further (its Bearer token dies on the next
//     request and later targeted sends record no rows for it).
//  3. A secret widget round-trips encrypted: the served body is the P5
//     envelope (kid == device, fresh nonce + ciphertext, NO plaintext
//     substring), decrypts under the owner's device_key via backend/crypto,
//     fails under the other device's key, is 404 byte-identical to an
//     unknown id for the wrong device, and 401s without auth.
//
// Runs inside `go test ./...`: httptest + temp dirs + NoopSender + a real
// operator login (POST /login) gated by webui.RequireAdmin.
package main

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// e2eNullSender is the harness delivery driver: records nothing, succeeds
// always. It is deliberately a distinct type from notify.NoopSender — stub
// drivers fail closed in handleNotify, while the harness needs delivery
// success to assert history rows.
type e2eNullSender struct{}

func (e2eNullSender) Send(_ context.Context, _ notify.Device, _ map[string]string) error {
	return nil
}

// e2eOperatorLogin performs a real WebUI login (POST /login with operator
// credentials) and returns the session cookie that RequireAdmin accepts.
func e2eOperatorLogin(t *testing.T, mux *http.ServeMux, username, password string) *http.Cookie {
	t.Helper()
	form := url.Values{"username": {username}, "password": {password}}.Encode()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(form))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.RemoteAddr = "192.0.2.1:1234"
	w := httptest.NewRecorder()
	mux.ServeHTTP(w, req)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("operator login: got %d (%s), want 303", w.Code, w.Body.String())
	}
	cookie := sessionCookie(w)
	if cookie == nil {
		t.Fatal("operator login set no pc_session cookie")
	}
	return cookie
}

// e2eIssueEnrollToken mints a single-use pairing token via the operator API.
func e2eIssueEnrollToken(t *testing.T, mux *http.ServeMux, cookie *http.Cookie) string {
	t.Helper()
	w := doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	if w.Code != http.StatusCreated {
		t.Fatalf("issue enroll token: got %d (%s), want 201", w.Code, w.Body.String())
	}
	var issued map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &issued); err != nil || issued["enroll_token"] == "" {
		t.Fatalf("bad enroll-token response: %q err=%v", w.Body.String(), err)
	}
	return issued["enroll_token"]
}

// e2eEnrollDevice pairs one phone via POST /api/v1/enroll (token-only auth,
// no session — the phone has no cookie). Returns id, bearer token, key.
func e2eEnrollDevice(t *testing.T, mux *http.ServeMux, token, name, fcm string) (id, devToken, devKey string) {
	t.Helper()
	body := `{"enroll_token":` + jsonQuote(token) +
		`,"device_name":` + jsonQuote(name) +
		`,"fcm_token":` + jsonQuote(fcm) + `}`
	w := doReq(t, mux, "POST", "/api/v1/enroll", body, "application/json", nil)
	if w.Code != http.StatusCreated {
		t.Fatalf("enroll %q: got %d (%s), want 201", name, w.Code, w.Body.String())
	}
	var enrolled map[string]string
	if err := json.Unmarshal(w.Body.Bytes(), &enrolled); err != nil {
		t.Fatalf("enroll %q: bad JSON: %v (%s)", name, err, w.Body.String())
	}
	id, devToken, devKey = enrolled["device_id"], enrolled["device_token"], enrolled["device_key"]
	if id == "" || devToken == "" || devKey == "" {
		t.Fatalf("enroll %q: incomplete credentials: %v", name, enrolled)
	}
	if raw, err := base64.StdEncoding.DecodeString(devKey); err != nil || len(raw) != crypto.KeySize {
		t.Fatalf("enroll %q: device_key must be base64 256-bit: err=%v", name, err)
	}
	return id, devToken, devKey
}

// e2eNotify posts a notify request and returns the 202 id + accepted count.
func e2eNotify(t *testing.T, mux *http.ServeMux, cookie *http.Cookie, body string) (notificationID string, accepted int) {
	t.Helper()
	w := doReq(t, mux, "POST", "/api/v1/notify", body, "application/json", cookie)
	if w.Code != http.StatusAccepted {
		t.Fatalf("notify %s: got %d (%s), want 202", body, w.Code, w.Body.String())
	}
	var got struct {
		NotificationID string `json:"notification_id"`
		Accepted       int    `json:"accepted"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &got); err != nil || got.NotificationID == "" {
		t.Fatalf("notify: bad 202 payload: %q err=%v", w.Body.String(), err)
	}
	return got.NotificationID, got.Accepted
}

// e2eHistoryRows returns (row count, statuses) for one (notification, device)
// fan-out so tests assert per-device delivery exactly.
func e2eHistoryRows(t *testing.T, db *sql.DB, notificationID, deviceID string) (int, []string) {
	t.Helper()
	rows, err := db.Query(
		`SELECT status FROM notification_history WHERE notification_id = ? AND device_id = ?`,
		notificationID, deviceID)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var statuses []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatal(err)
		}
		statuses = append(statuses, s)
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	return len(statuses), statuses
}

func e2eDecodeKey(t *testing.T, devKey string) [crypto.KeySize]byte {
	t.Helper()
	raw, err := base64.StdEncoding.DecodeString(devKey)
	if err != nil || len(raw) != crypto.KeySize {
		t.Fatalf("bad device_key: err=%v", err)
	}
	var k [crypto.KeySize]byte
	copy(k[:], raw)
	return k
}

func TestE2EDoneCriteria(t *testing.T) {
	db, err := OpenDB(t.TempDir() + "/e2e-done.db")
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	if err := notify.Migrate(db); err != nil {
		t.Fatal(err)
	}
	oldDB := deviceDB
	SetDeviceDB(db)
	t.Cleanup(func() {
		db.Close()
		SetDeviceDB(oldDB)
	})
	resetEnrollIssueLimiter()

	const sessionSecret = "e2e-done-session-secret-01234567"
	sessions := NewSessionStore(db, sessionSecret)
	csrf := webui.NewCSRFProtection(sessionSecret)

	widgetDir := t.TempDir()
	reg := NewWidgetRegistry(widgetDir)
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}
	mux := NewMuxWithWidgets(reg, sessions, csrf)
	// Same operator notify wiring as production NewMux. The harness uses a
	// hermetic recording driver (stub Log/NoopSender fail closed in the
	// handler now): rows still land in notification_history.
	mux.Handle("POST /api/v1/notify",
		notifyAdminChain(sessions, handleNotify(db, e2eNullSender{})))
	webui.RegisterRoutes(mux, sessions, webui.Credentials{
		Username: "op",
		Password: "op-pass-e2e-0123456789abcdef",
	})

	// --- Operator login: the real RequireAdmin gate for everything below.
	cookie := e2eOperatorLogin(t, mux, "op", "op-pass-e2e-0123456789abcdef")

	// Notify without a session must 401 (operator-only endpoint).
	w := doReq(t, mux, "POST", "/api/v1/notify",
		`{"target":{"all":true},"title":"Hi","body":"Yo"}`, "application/json", nil)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("notify without session: got %d, want 401 (%s)", w.Code, w.Body.String())
	}

	// --- (a) Pair two devices via the API, exactly like two phones.
	idA, tokA, keyA := e2eEnrollDevice(t, mux,
		e2eIssueEnrollToken(t, mux, cookie), "dev-A", "fcm-e2e-dev-a")
	idB, tokB, keyB := e2eEnrollDevice(t, mux,
		e2eIssueEnrollToken(t, mux, cookie), "dev-B", "fcm-e2e-dev-b")
	if idA == idB {
		t.Fatal("dev-A and dev-B share a device id")
	}

	// Secret widget keyed to dev-A (written after pairing so secret_ref is real).
	const secretValue = "E2E-DONE-PLAINTEXT-m4rk3r-9f2c7a"
	writeWidget(t, widgetDir, "vault",
		"id = \"vault\"\ntype = \"secret\"\ntitle = \"Vault\"\nsecret_ref = \""+idA+"\"\n",
		"", `{"secret": `+jsonQuote(secretValue)+`}`)
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}

	// --- Targeted notify to dev-A only: dev-A gets exactly 1 queued row,
	// --- dev-B gets 0.
	nidTargeted, accepted := e2eNotify(t, mux, cookie,
		`{"target":{"device_ids":[`+jsonQuote(idA)+`]},"title":"Hello A","body":"only A"}`)
	if accepted != 1 {
		t.Fatalf("targeted notify: accepted = %d, want 1", accepted)
	}
	if n, st := e2eHistoryRows(t, db, nidTargeted, idA); n != 1 || len(st) != 1 || st[0] != notify.StatusQueued {
		t.Fatalf("targeted notify: dev-A rows = %d %v, want 1 queued", n, st)
	}
	if n, _ := e2eHistoryRows(t, db, nidTargeted, idB); n != 0 {
		t.Fatalf("targeted notify: dev-B rows = %d, want 0", n)
	}

	// --- Broadcast reaches both.
	nidAll, accepted := e2eNotify(t, mux, cookie,
		`{"target":{"all":true},"title":"Hello all","body":"everyone"}`)
	if accepted != 2 {
		t.Fatalf("broadcast notify: accepted = %d, want 2", accepted)
	}
	for _, id := range []string{idA, idB} {
		if n, st := e2eHistoryRows(t, db, nidAll, id); n != 1 || st[0] != notify.StatusQueued {
			t.Fatalf("broadcast: device %s rows = %d %v, want 1 queued", id, n, st)
		}
	}

	// --- (b) Secret round-trip stays encrypted for the owner.
	rec := authedGet(mux, "GET", "/api/v1/widgets/vault", tokA, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("owner secret fetch: got %d (%s), want 200", rec.Code, rec.Body.String())
	}
	rawBody := rec.Body.String()
	var env widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatalf("owner secret: bad envelope: %v (%s)", err, rawBody)
	}
	if env.SecretRef != idA {
		t.Fatalf("owner secret: secret_ref = %q, want %q", env.SecretRef, idA)
	}
	var wire crypto.Envelope
	if err := json.Unmarshal(env.Payload, &wire); err != nil {
		t.Fatalf("owner secret: payload is not a P5 envelope: %v (%s)", err, env.Payload)
	}
	if wire.Alg != crypto.Algorithm || wire.Kid != idA || wire.Nonce == "" || wire.Ciphertext == "" {
		t.Fatalf("owner secret: bad envelope identity: %+v", wire)
	}
	if nonce, err := base64.StdEncoding.DecodeString(wire.Nonce); err != nil || len(nonce) != crypto.NonceSize {
		t.Fatalf("owner secret: bad nonce: err=%v", err)
	}
	if strings.Contains(rawBody, secretValue) {
		t.Fatal("owner secret: response leaks plaintext")
	}
	plain, err := crypto.Decrypt(e2eDecodeKey(t, keyA), idA, "vault", wire)
	if err != nil {
		t.Fatalf("owner secret: decrypt with dev-A key: %v", err)
	}
	if string(plain) != secretValue {
		t.Fatalf("owner secret: plaintext = %q, want %q", plain, secretValue)
	}
	// The other device's key decrypts nothing.
	if _, err := crypto.Decrypt(e2eDecodeKey(t, keyB), idA, "vault", wire); err == nil {
		t.Fatal("secret decrypts under dev-B key (must fail)")
	}

	// Wrong device: 404 byte-identical to an unknown id (no existence oracle).
	foreign := authedGet(mux, "GET", "/api/v1/widgets/vault", tokB, nil)
	unknown := authedGet(mux, "GET", "/api/v1/widgets/no-such-widget", tokB, nil)
	if foreign.Code != http.StatusNotFound || unknown.Code != http.StatusNotFound {
		t.Fatalf("oracle: foreign = %d, unknown = %d, want 404/404",
			foreign.Code, unknown.Code)
	}
	if foreign.Body.String() != unknown.Body.String() {
		t.Fatalf("oracle: foreign %q != unknown %q", foreign.Body.String(), unknown.Body.String())
	}
	if strings.Contains(foreign.Body.String(), secretValue) {
		t.Fatal("foreign 404 leaks plaintext")
	}

	// Unauthenticated secret fetch: 401.
	if rec := authedGet(mux, "GET", "/api/v1/widgets/vault", "", nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("no-auth secret fetch: got %d, want 401", rec.Code)
	}

	// --- Revoke dev-B via the API: immediate effect, then it gets nothing.
	w = doReq(t, mux, "DELETE", "/api/v1/devices/"+idB, "", "", cookie)
	if w.Code != http.StatusNoContent {
		t.Fatalf("revoke dev-B: got %d (%s), want 204", w.Code, w.Body.String())
	}
	if rec := authedGet(mux, "GET", "/api/v1/widgets", tokB, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("revoked dev-B poll: got %d, want 401", rec.Code)
	}
	nidAfter, accepted := e2eNotify(t, mux, cookie,
		`{"target":{"device_ids":[`+jsonQuote(idA)+`]},"title":"After revoke","body":"only A"}`)
	if accepted != 1 {
		t.Fatalf("post-revoke targeted notify: accepted = %d, want 1", accepted)
	}
	if n, _ := e2eHistoryRows(t, db, nidAfter, idA); n != 1 {
		t.Fatalf("post-revoke: dev-A rows = %d, want 1", n)
	}
	if n, _ := e2eHistoryRows(t, db, nidAfter, idB); n != 0 {
		t.Fatalf("post-revoke: revoked dev-B rows = %d, want 0", n)
	}
}
