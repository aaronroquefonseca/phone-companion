package main

// Live pairing detection + rename tests (main package): rename validation
// and lifecycle at the DB layer, enroll-token status lifecycle (live ->
// used naming the device, expired, unknown), the paired_device_id migration
// on old databases, and the HTTP wiring for both new operator endpoints
// (anon gating, CSRF, PRG shapes).

import (
	"database/sql"
	"encoding/json"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestRenameDeviceRules(t *testing.T) {
	db := openDevicesTestDB(t)

	id, _, _, err := EnrollDevice(db, "Original", "fcm-rename-1")
	if err != nil {
		t.Fatal(err)
	}

	// Happy path: name changes, updated_at stamped.
	found, err := RenameDevice(db, id, "  Renamed Phone  ")
	if err != nil || !found {
		t.Fatalf("rename: found=%v err=%v", found, err)
	}
	var name, updated string
	if err := db.QueryRow(`SELECT name, updated_at FROM devices WHERE id = ?`, id).Scan(&name, &updated); err != nil {
		t.Fatal(err)
	}
	if name != "Renamed Phone" {
		t.Fatalf("name = %q, want trimmed rename", name)
	}
	if updated == "" {
		t.Fatal("updated_at not stamped on rename")
	}

	// Idempotent: renaming to the current name still reports found.
	if found, err := RenameDevice(db, id, "Renamed Phone"); err != nil || !found {
		t.Fatalf("same-name rename: found=%v err=%v, want idempotent success", found, err)
	}

	// Unknown and empty IDs report not-found, never an error.
	if found, err := RenameDevice(db, "does-not-exist", "New"); err != nil || found {
		t.Fatalf("unknown: found=%v err=%v, want false/nil", found, err)
	}
	if found, err := RenameDevice(db, "   ", "New"); err != nil || found {
		t.Fatalf("blank id: found=%v err=%v, want false/nil", found, err)
	}

	// Validation rejects: empty, overlong, control chars, bad UTF-8.
	for _, bad := range []string{"", "   ", strings.Repeat("n", 201), "Bad\nName", "Bad\x01Name", "bad\xffname"} {
		if _, err := RenameDevice(db, id, bad); err == nil {
			t.Fatalf("name %q: want validation error", bad)
		}
	}
	// A rejected rename leaves the stored name untouched.
	if err := db.QueryRow(`SELECT name FROM devices WHERE id = ?`, id).Scan(&name); err != nil || name != "Renamed Phone" {
		t.Fatalf("rejected rename mutated the row: %q err=%v", name, err)
	}

	// Boundary + charset parity with the WebUI validator.
	if _, err := RenameDevice(db, id, strings.Repeat("b", 200)); err != nil {
		t.Fatalf("200-byte boundary: %v", err)
	}
	if _, err := RenameDevice(db, id, "📱 Mara's phone"); err != nil {
		t.Fatalf("emoji name: %v", err)
	}
}

func TestRenameRevokedDeviceAllowed(t *testing.T) {
	db := openDevicesTestDB(t)
	id, _, _, err := EnrollDevice(db, "Old", "fcm-rename-revoked")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := RevokeDevice(db, id); err != nil {
		t.Fatal(err)
	}
	// Renaming a revoked row is allowed (operator cleanup before purge);
	// it does not un-revoke.
	if found, err := RenameDevice(db, id, "Old (retired)"); err != nil || !found {
		t.Fatalf("revoked rename: found=%v err=%v", found, err)
	}
	var name string
	var revoked sql.NullString
	if err := db.QueryRow(`SELECT name, revoked_at FROM devices WHERE id = ?`, id).Scan(&name, &revoked); err != nil {
		t.Fatal(err)
	}
	if name != "Old (retired)" || !revoked.Valid {
		t.Fatalf("revoked rename: name=%q revoked=%v, want rename without un-revoke", name, revoked.Valid)
	}
}

func TestEnrollTokenStatusLifecycle(t *testing.T) {
	db := openDevicesTestDB(t)

	raw, _, err := IssueEnrollToken(db, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	hash := sha256Hex(raw)

	// Live right after issuance, with an expiry.
	rep, err := EnrollTokenStatus(db, hash)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != "live" || !rep.Found || rep.ExpiresAt.IsZero() {
		t.Fatalf("fresh token: %+v, want live with expiry", rep)
	}

	// Consume + link (the HandleEnroll path): used, naming the device.
	ok, err := consumeEnrollToken(db, raw)
	if err != nil || !ok {
		t.Fatalf("consume: ok=%v err=%v", ok, err)
	}
	deviceID, _, _, err := EnrollDevice(db, "Status Phone", "fcm-status-1")
	if err != nil {
		t.Fatal(err)
	}
	if err := linkEnrollTokenDevice(db, raw, deviceID); err != nil {
		t.Fatal(err)
	}
	rep, err = EnrollTokenStatus(db, hash)
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != "used" || rep.DeviceID != deviceID || rep.DeviceName != "Status Phone" {
		t.Fatalf("used token: %+v, want used naming the device", rep)
	}

	// Case/whitespace-tolerant lookup.
	rep, err = EnrollTokenStatus(db, "  "+strings.ToUpper(hash)+"  ")
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != "used" || rep.DeviceID != deviceID {
		t.Fatalf("normalized lookup: %+v", rep)
	}

	// Expired row: issued with a negative TTL, still unused.
	expiredRaw, _, err := IssueEnrollToken(db, -time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	rep, err = EnrollTokenStatus(db, sha256Hex(expiredRaw))
	if err != nil {
		t.Fatal(err)
	}
	if rep.Status != "expired" || !rep.Found {
		t.Fatalf("expired token: %+v, want expired", rep)
	}

	// Unknown hash (bogus or pruned): unknown, no error.
	for _, bogus := range []string{"", "  ", sha256Hex("never-issued")} {
		rep, err = EnrollTokenStatus(db, bogus)
		if err != nil {
			t.Fatal(err)
		}
		if rep.Status != "unknown" || rep.Found {
			t.Fatalf("bogus %q: %+v, want unknown", bogus, rep)
		}
	}
}

func TestEnrollTokensPairedColumnMigration(t *testing.T) {
	// Simulate a database from before the paired_device_id column: the
	// live-pairing link and status paths must add it idempotently.
	db, err := OpenDB(t.TempDir() + "/old-tokens.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, stmt := range []string{
		`CREATE TABLE devices (id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL, secret_key BLOB)`,
		`CREATE TABLE enroll_tokens (token_hash TEXT PRIMARY KEY, expires_at TEXT NOT NULL, used INTEGER NOT NULL DEFAULT 0, created_at TEXT NOT NULL)`,
	} {
		if _, err := db.Exec(stmt); err != nil {
			t.Fatal(err)
		}
	}
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate over pre-link schema: %v", err)
	}
	raw, _, err := IssueEnrollToken(db, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if err := linkEnrollTokenDevice(db, raw, "dev-1"); err != nil {
		t.Fatalf("link on migrated DB: %v", err)
	}
	if rep, err := EnrollTokenStatus(db, sha256Hex(raw)); err != nil || rep.Status != "live" {
		t.Fatalf("status on migrated DB: %+v err=%v", rep, err)
	}
	if err := ensureEnrollTokensPairedColumn(db); err != nil {
		t.Fatalf("second ensure: %v", err)
	}
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate not idempotent: %v", err)
	}
}

func TestLivePairingEndpointsWiring(t *testing.T) {
	mux, _, _, cookie := setupPairingMux(t)

	// Anonymous: both new endpoints gate like their neighbors — the GET
	// poll 303s to /login (never JSON), the rename POST 303s too.
	w := doReq(t, mux, "GET", "/webui/pair/token-status?token_hash="+strings.Repeat("a", 64), "", "", nil)
	if w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/login" {
		t.Fatalf("anon poll: got %d loc=%q, want 303 /login", w.Code, w.Header().Get("Location"))
	}
	w = doReq(t, mux, "POST", "/webui/pair/rename", url.Values{"device_id": {"x"}, "new_name": {"y"}}.Encode(),
		"application/x-www-form-urlencoded", nil)
	if w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/login" {
		t.Fatalf("anon rename: got %d loc=%q, want 303 /login", w.Code, w.Header().Get("Location"))
	}

	// Malformed hash with a session: 400 JSON + no-store.
	w = doReq(t, mux, "GET", "/webui/pair/token-status?token_hash=xyz", "", "", cookie)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("malformed hash: got %d, want 400", w.Code)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("poll Cache-Control %q, want no-store", cc)
	}

	// Unknown hash: 200 {"status":"unknown"} (pruned rows stop the poll).
	w = doReq(t, mux, "GET", "/webui/pair/token-status?token_hash="+strings.Repeat("b", 64), "", "", cookie)
	if w.Code != http.StatusOK {
		t.Fatalf("unknown hash: got %d, want 200", w.Code)
	}
	var unknown map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &unknown); err != nil || unknown["status"] != "unknown" {
		t.Fatalf("unknown body: %q", w.Body.String())
	}

	// Full lifecycle through the real enroll path: issue → live → enroll
	// (phone, no session) → used naming the device.
	iw := doOperatorWriteReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", cookie)
	var issued map[string]string
	if err := json.Unmarshal(iw.Body.Bytes(), &issued); err != nil || issued["enroll_token"] == "" {
		t.Fatalf("issue: %q", iw.Body.String())
	}
	hash := sha256Hex(issued["enroll_token"])
	pollURL := "/webui/pair/token-status?token_hash=" + hash
	w = doReq(t, mux, "GET", pollURL, "", "", cookie)
	var live map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &live); err != nil || live["status"] != "live" {
		t.Fatalf("pre-enroll poll: %q", w.Body.String())
	}
	enrollBody := `{"enroll_token":` + jsonQuote(issued["enroll_token"]) + `,"device_name":"Live Phone","fcm_token":"fcm-live-poll-1"}`
	ew := doReq(t, mux, "POST", "/api/v1/enroll", enrollBody, "application/json", nil)
	if ew.Code != http.StatusCreated {
		t.Fatalf("enroll: got %d (%s)", ew.Code, ew.Body.String())
	}
	var enrolled map[string]string
	if err := json.Unmarshal(ew.Body.Bytes(), &enrolled); err != nil {
		t.Fatal(err)
	}
	w = doReq(t, mux, "GET", pollURL, "", "", cookie)
	var used map[string]any
	if err := json.Unmarshal(w.Body.Bytes(), &used); err != nil {
		t.Fatal(err)
	}
	if used["status"] != "used" || used["device_id"] != enrolled["device_id"] || used["device_name"] != "Live Phone" {
		t.Fatalf("post-enroll poll: %q, want used naming the device", w.Body.String())
	}

	// Rename via the WebUI form: CSRF + PRG flash, visible on the page.
	page := doReq(t, mux, "GET", "/webui/pair", "", "", cookie)
	csrfTok := extractCSRFToken(t, page.Body.String())
	form := url.Values{"device_id": {enrolled["device_id"]}, "new_name": {"Renamed Live"}, "csrf_token": {csrfTok}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/rename", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("rename: got %d (%s), want 303", w.Code, w.Body.String())
	}
	if loc := w.Header().Get("Location"); !strings.HasPrefix(loc, "/webui/pair?renamed=") {
		t.Fatalf("rename redirect to %q, want ?renamed=", loc)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("rename Cache-Control %q, want no-store", cc)
	}
	fw := doReq(t, mux, "GET", w.Header().Get("Location"), "", "", cookie)
	if body := fw.Body.String(); !strings.Contains(body, `class="success"`) || !strings.Contains(body, "Renamed Live") {
		t.Fatalf("renamed flash missing: %s", body)
	}
	// The registry actually changed.
	lw := doReq(t, mux, "GET", "/api/v1/devices", "", "", cookie)
	if !strings.Contains(lw.Body.String(), "Renamed Live") {
		t.Fatalf("devices list missing rename: %s", lw.Body.String())
	}

	// Rename rejects: empty name → ?error=invalid_name; unknown id →
	// ?error=device_not_found; missing CSRF → 403 HTML shell.
	form = url.Values{"device_id": {enrolled["device_id"]}, "new_name": {""}, "csrf_token": {csrfTok}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/rename", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/webui/pair?error=invalid_name" {
		t.Fatalf("empty rename: got %d loc=%q", w.Code, w.Header().Get("Location"))
	}
	form = url.Values{"device_id": {"nope"}, "new_name": {"Fine"}, "csrf_token": {csrfTok}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/rename", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusSeeOther || w.Header().Get("Location") != "/webui/pair?error=device_not_found" {
		t.Fatalf("unknown rename: got %d loc=%q", w.Code, w.Header().Get("Location"))
	}
	form = url.Values{"device_id": {enrolled["device_id"]}, "new_name": {"Nope"}}.Encode()
	w = doReq(t, mux, "POST", "/webui/pair/rename", form, "application/x-www-form-urlencoded", cookie)
	if w.Code != http.StatusForbidden {
		t.Fatalf("rename without CSRF: got %d, want 403", w.Code)
	}
	if ct := w.Header().Get("Content-Type"); !strings.Contains(ct, "text/html") {
		t.Fatalf("rename CSRF failure content type %q, want html", ct)
	}
}

func TestStoreAdapterNilGuardRenameAndStatus(t *testing.T) {
	oldDB := deviceDB
	SetDeviceDB(nil)
	t.Cleanup(func() { SetDeviceDB(oldDB) })
	var a webuiStoreAdapter
	if _, err := a.RenameDevice(t.Context(), "x", "y"); err == nil {
		t.Error("RenameDevice(nil db): want error")
	}
	if _, err := a.TokenStatus(t.Context(), strings.Repeat("c", 64)); err == nil {
		t.Error("TokenStatus(nil db): want error")
	}
}

func TestPairingUnauthenticatedGatingCoversLiveEndpoints(t *testing.T) {
	// Pins the two new operator routes into the same gating contract as
	// their neighbors (covered in TestPairingUnauthenticatedGating):
	// anonymous callers 303 to /login on /webui/*, never JSON or HTML.
	mux, _, _, _ := setupPairingMux(t)
	for _, tc := range []struct{ method, target, body string }{
		{"GET", "/webui/pair/token-status?token_hash=" + strings.Repeat("d", 64), ""},
		{"POST", "/webui/pair/rename", url.Values{"device_id": {"x"}, "new_name": {"y"}}.Encode()},
	} {
		ct := ""
		if tc.method == "POST" {
			ct = "application/x-www-form-urlencoded"
		}
		w := doReq(t, mux, tc.method, tc.target, tc.body, ct, nil)
		if w.Code != http.StatusSeeOther {
			t.Errorf("%s %s: got %d, want 303", tc.method, tc.target, w.Code)
		}
		if loc := w.Header().Get("Location"); loc != "/login" {
			t.Errorf("%s %s: redirect to %q, want /login", tc.method, tc.target, loc)
		}
	}
}
