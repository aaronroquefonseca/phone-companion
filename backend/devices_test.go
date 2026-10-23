package main

import (
	"database/sql"
	"encoding/base64"
	"net/http"
	"strings"
	"testing"
	"time"
)

func openDevicesTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := OpenDB(t.TempDir() + "/test.db")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	return db
}

func TestMigrateUpgradesP0Schema(t *testing.T) {
	db, err := OpenDB(t.TempDir() + "/old.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	// Simulate a P0 database: base table only, no pairing columns.
	if _, err := db.Exec(`CREATE TABLE devices (
		id TEXT PRIMARY KEY, name TEXT NOT NULL,
		token_hash TEXT NOT NULL, secret_key BLOB)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash) VALUES ('d1','old','h')`); err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate over P0 schema: %v", err)
	}
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate not idempotent: %v", err)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("row lost in migration: n=%d err=%v", n, err)
	}
}

func TestEnrollHappyPathAndSingleUse(t *testing.T) {
	db := openDevicesTestDB(t)

	raw, _, err := IssueEnrollToken(db, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	ok, err := consumeEnrollToken(db, raw)
	if err != nil || !ok {
		t.Fatalf("first consume: ok=%v err=%v", ok, err)
	}
	ok, err = consumeEnrollToken(db, raw)
	if err != nil || ok {
		t.Fatalf("second consume must fail: ok=%v err=%v", ok, err)
	}
	if ok, _ := consumeEnrollToken(db, "bogus"); ok {
		t.Fatal("bogus token consumed")
	}

	id, tok, key, err := EnrollDevice(db, "Pixel 8", "fcm-1")
	if err != nil {
		t.Fatal(err)
	}
	if id == "" || tok == "" || key == "" {
		t.Fatal("empty enroll outputs")
	}
	keyBytes, err := base64.StdEncoding.DecodeString(key)
	if err != nil || len(keyBytes) != 32 {
		t.Fatalf("device_key must be base64 256-bit: %v", err)
	}

	// Server stores only hashes/keys, never raw tokens.
	var storedHash string
	var storedKey []byte
	if err := db.QueryRow(`SELECT token_hash, secret_key FROM devices WHERE id = ?`, id).
		Scan(&storedHash, &storedKey); err != nil {
		t.Fatal(err)
	}
	if storedHash == tok || strings.Contains(storedHash, tok) {
		t.Fatal("raw device token stored")
	}
	if string(storedKey) == key || len(storedKey) != 32 {
		t.Fatal("secret_key not stored as raw 32 bytes")
	}

	// Bearer auth works, then revocation takes immediate effect.
	req, _ := http.NewRequest("GET", "/api/v1/widgets", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	dev, err := AuthenticateDevice(db, req)
	if err != nil || dev.ID != id {
		t.Fatalf("auth before revoke: %v", err)
	}
	found, err := RevokeDevice(db, id)
	if err != nil || !found {
		t.Fatalf("revoke: %v %v", found, err)
	}
	if _, err := AuthenticateDevice(db, req); err == nil {
		t.Fatal("revoked token still authenticates")
	}
	if found, _ := RevokeDevice(db, "nope"); found {
		t.Fatal("unknown id reported found")
	}
}

func TestRePairRotatesKey(t *testing.T) {
	db := openDevicesTestDB(t)
	id1, tok1, key1, err := EnrollDevice(db, "Phone", "fcm-same")
	if err != nil {
		t.Fatal(err)
	}
	id2, tok2, key2, err := EnrollDevice(db, "Phone", "fcm-same")
	if err != nil {
		t.Fatal(err)
	}
	if id1 != id2 {
		t.Fatal("same fcm_token must rotate the same row")
	}
	if tok1 == tok2 || key1 == key2 {
		t.Fatal("rotation must issue fresh token and key")
	}
	req, _ := http.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer "+tok1)
	if _, err := AuthenticateDevice(db, req); err == nil {
		t.Fatal("pre-rotation token still valid")
	}

	// Distinct/empty fcm tokens create distinct devices (two phones pair).
	a, _, _, _ := EnrollDevice(db, "Phone A", "")
	b, _, _, _ := EnrollDevice(db, "Phone A", "")
	if a == b {
		t.Fatal("expected two device rows")
	}
}

func TestRePairPreservesCreatedAtAndStampsUpdatedAt(t *testing.T) {
	db := openDevicesTestDB(t)

	id1, tok1, _, err := EnrollDevice(db, "Phone", "fcm-lifecycle")
	if err != nil {
		t.Fatal(err)
	}
	var created1, updated1, fcm1 string
	var updatedNull sql.NullString
	if err := db.QueryRow(`SELECT created_at, updated_at, fcm_token FROM devices WHERE id = ?`, id1).
		Scan(&created1, &updatedNull, &fcm1); err != nil {
		t.Fatal(err)
	}
	if created1 == "" {
		t.Fatal("created_at not stamped on insert")
	}
	updated1 = updatedNull.String
	if updated1 == "" {
		t.Fatal("updated_at not stamped on insert")
	}
	if fcm1 != "fcm-lifecycle" {
		t.Fatalf("fcm_token stored = %q, want normalized input", fcm1)
	}

	// Backdate updated_at so the rotation stamp is observably newer even
	// within the same second.
	if _, err := db.Exec(`UPDATE devices SET updated_at = ? WHERE id = ?`,
		"2000-01-01T00:00:00Z", id1); err != nil {
		t.Fatal(err)
	}

	id2, tok2, key2, err := EnrollDevice(db, "Phone Renamed", "fcm-lifecycle")
	if err != nil {
		t.Fatal(err)
	}
	if id2 != id1 {
		t.Fatal("same fcm_token must rotate the same row")
	}
	if tok2 == tok1 {
		t.Fatal("rotation must issue a fresh bearer token")
	}
	if key2 == "" {
		t.Fatal("rotation must issue a fresh device key")
	}

	var created2, updated2, name2, fcm2 string
	if err := db.QueryRow(`SELECT created_at, updated_at, name, fcm_token FROM devices WHERE id = ?`, id1).
		Scan(&created2, &updated2, &name2, &fcm2); err != nil {
		t.Fatal(err)
	}
	if created2 != created1 {
		t.Fatalf("rotation overwrote created_at: %q -> %q", created1, created2)
	}
	if updated2 == "" || updated2 == "2000-01-01T00:00:00Z" {
		t.Fatalf("rotation did not stamp updated_at: %q", updated2)
	}
	if name2 != "Phone Renamed" {
		t.Fatalf("rotation did not update name: %q", name2)
	}
	if fcm2 != "fcm-lifecycle" {
		t.Fatalf("rotation did not persist fcm_token: %q", fcm2)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices WHERE fcm_token = ?`, "fcm-lifecycle").Scan(&n); err != nil || n != 1 {
		t.Fatalf("re-pair duplicated rows: n=%d err=%v", n, err)
	}

	// New token authenticates; pre-rotation token is dead.
	req, _ := http.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer "+tok2)
	if dev, err := AuthenticateDevice(db, req); err != nil || dev.ID != id1 {
		t.Fatalf("post-rotation auth: %v", err)
	}
	req.Header.Set("Authorization", "Bearer "+tok1)
	if _, err := AuthenticateDevice(db, req); err == nil {
		t.Fatal("pre-rotation token still valid")
	}

	// Registry exposes the lifecycle timestamps with created_at stable.
	infos, err := ListDevices(db)
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, d := range infos {
		if d.ID != id1 {
			continue
		}
		found = true
		if d.CreatedAt != created1 {
			t.Fatalf("ListDevices created_at = %q, want %q", d.CreatedAt, created1)
		}
		if d.UpdatedAt == "" {
			t.Fatal("ListDevices missing updated_at")
		}
	}
	if !found {
		t.Fatal("rotated device missing from ListDevices")
	}
}

func TestEmptyFCMCreatesDistinctRows(t *testing.T) {
	db := openDevicesTestDB(t)

	a, _, _, err := EnrollDevice(db, "Same Name", "")
	if err != nil {
		t.Fatal(err)
	}
	b, _, _, err := EnrollDevice(db, "Same Name", "")
	if err != nil {
		t.Fatal(err)
	}
	if a == b {
		t.Fatal("empty fcm_token must never dedup: got same id")
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices WHERE name = ?`, "Same Name").Scan(&n); err != nil || n != 2 {
		t.Fatalf("want 2 distinct rows, n=%d err=%v", n, err)
	}
}

func TestEnrollTrimsFCMTokenWhitespace(t *testing.T) {
	db := openDevicesTestDB(t)

	id1, _, _, err := EnrollDevice(db, "Pad", "  fcm-padded  ")
	if err != nil {
		t.Fatal(err)
	}
	var stored string
	if err := db.QueryRow(`SELECT fcm_token FROM devices WHERE id = ?`, id1).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if stored != "fcm-padded" {
		t.Fatalf("fcm_token stored = %q, want trimmed", stored)
	}
	// Re-pair with a differently padded variant rotates the same row.
	id2, _, _, err := EnrollDevice(db, "Pad", "\tfcm-padded\n")
	if err != nil {
		t.Fatal(err)
	}
	if id2 != id1 {
		t.Fatal("padded fcm_token variants must match the same row")
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("n=%d err=%v, want 1 row", n, err)
	}
}

func TestAuthenticateLegacyNullCreatedAt(t *testing.T) {
	db := openDevicesTestDB(t)
	if err := ensureDevicesUpdatedAtColumn(db); err != nil {
		t.Fatal(err)
	}

	// Pre-pairing-migration shape: valid token hash but NULL created_at.
	const raw = "legacy-bearer-token-for-null-created-at"
	if _, err := db.Exec(
		`INSERT INTO devices (id, name, token_hash, secret_key, created_at, updated_at, revoked_at, fcm_token)
		 VALUES (?, ?, ?, ?, NULL, NULL, NULL, ?)`,
		"d-legacy", "Legacy", sha256Hex(raw),
		[]byte("0123456789abcdef0123456789abcdef"), ""); err != nil {
		t.Fatal(err)
	}
	req, _ := http.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer "+raw)
	dev, err := AuthenticateDevice(db, req)
	if err != nil {
		t.Fatalf("legacy NULL created_at row must authenticate: %v", err)
	}
	if dev.ID != "d-legacy" {
		t.Fatalf("dev.ID = %q, want d-legacy", dev.ID)
	}
}

func TestFindSiblingDeviceIDs(t *testing.T) {
	db := openDevicesTestDB(t)

	a, _, _, _ := EnrollDevice(db, "Phone A", "")
	b, _, _, _ := EnrollDevice(db, "Phone A", "")
	c, _, _, _ := EnrollDevice(db, "Other", "")
	d, _, _, _ := EnrollDevice(db, "Phone A", "fcm-sib")
	if a == b || a == c || a == d {
		t.Fatal("setup: expected distinct device rows")
	}

	sibs, err := FindSiblingDeviceIDs(db, "Phone A", a)
	if err != nil {
		t.Fatal(err)
	}
	has := map[string]bool{}
	for _, id := range sibs {
		has[id] = true
	}
	if !has[b] || !has[d] {
		t.Fatalf("siblings missing: %v", sibs)
	}
	if has[a] {
		t.Fatalf("exceptID not excluded: %v", sibs)
	}
	if has[c] {
		t.Fatalf("different-name device listed: %v", sibs)
	}

	// Revoked siblings drop out (nothing left to revoke).
	if found, err := RevokeDevice(db, b); err != nil || !found {
		t.Fatalf("revoke sibling: %v %v", found, err)
	}
	sibs, err = FindSiblingDeviceIDs(db, "Phone A", a)
	if err != nil {
		t.Fatal(err)
	}
	for _, id := range sibs {
		if id == b {
			t.Fatalf("revoked sibling still listed: %v", sibs)
		}
	}

	// Unknown exceptID returns every live row with the name.
	sibs, err = FindSiblingDeviceIDs(db, "Phone A", "no-such-id")
	if err != nil {
		t.Fatal(err)
	}
	if len(sibs) != 2 {
		t.Fatalf("want 2 live siblings, got %v", sibs)
	}

	// Empty name matches nothing (never a wildcard).
	sibs, err = FindSiblingDeviceIDs(db, "   ", a)
	if err != nil {
		t.Fatal(err)
	}
	if len(sibs) != 0 {
		t.Fatalf("empty name must match nothing, got %v", sibs)
	}
}

func TestPruneEnrollTokens(t *testing.T) {
	db := openDevicesTestDB(t)

	live, _, err := IssueEnrollToken(db, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	used, _, err := IssueEnrollToken(db, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if ok, err := consumeEnrollToken(db, used); err != nil || !ok {
		t.Fatalf("consume used token: ok=%v err=%v", ok, err)
	}
	// Seed an expired row via direct SQL: going through IssueEnrollToken
	// would prune it on the way in.
	past := formatTime(utcNow().Add(-time.Minute))
	if _, err := db.Exec(
		`INSERT INTO enroll_tokens (token_hash, expires_at, used, created_at) VALUES (?, ?, 0, ?)`,
		sha256Hex("expired-seed"), past, past); err != nil {
		t.Fatal(err)
	}

	pruned, err := pruneEnrollTokens(db, utcNow())
	if err != nil {
		t.Fatal(err)
	}
	if pruned != 2 {
		t.Fatalf("pruned = %d, want 2 (1 expired + 1 used)", pruned)
	}
	if ok, _ := consumeEnrollToken(db, used); ok {
		t.Fatal("used token survived prune")
	}
	var liveRows int
	if err := db.QueryRow(`SELECT COUNT(*) FROM enroll_tokens WHERE token_hash = ?`,
		sha256Hex(live)).Scan(&liveRows); err != nil || liveRows != 1 {
		t.Fatalf("live token wrongly pruned: n=%d err=%v", liveRows, err)
	}

	// Steady state: the live unused token is kept, nothing pruned.
	if n, err := pruneEnrollTokens(db, utcNow()); err != nil || n != 0 {
		t.Fatalf("steady-state prune: n=%d err=%v", n, err)
	}
}

func TestIssueEnrollTokenPrunesExpiredAndCapsOutstanding(t *testing.T) {
	db := openDevicesTestDB(t)

	// Seed an already-expired row, then issue: the issue-time prune must
	// remove it before inserting the fresh token.
	if _, _, err := IssueEnrollToken(db, -time.Minute); err != nil {
		t.Fatal(err)
	}
	live, _, err := IssueEnrollToken(db, time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM enroll_tokens`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("expired row survived issue-time prune: n=%d err=%v", n, err)
	}
	if ok, _ := consumeEnrollToken(db, live); !ok {
		t.Fatal("live token not consumable after prune")
	}

	// Cap: outstanding live tokens stay bounded.
	for i := 0; i < maxOutstandingEnrollTokens+5; i++ {
		if _, _, err := IssueEnrollToken(db, time.Minute); err != nil {
			t.Fatal(err)
		}
	}
	if err := db.QueryRow(`SELECT COUNT(*) FROM enroll_tokens WHERE used = 0`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != maxOutstandingEnrollTokens {
		t.Fatalf("outstanding = %d, want cap %d", n, maxOutstandingEnrollTokens)
	}
}

func TestUpdatedAtMigrationIdempotent(t *testing.T) {
	db, err := OpenDB(t.TempDir() + "/old-updated.db")
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	// Simulate a P0 database: base table only, no pairing columns.
	if _, err := db.Exec(`CREATE TABLE devices (
		id TEXT PRIMARY KEY, name TEXT NOT NULL,
		token_hash TEXT NOT NULL, secret_key BLOB)`); err != nil {
		t.Fatal(err)
	}
	if _, err := db.Exec(`INSERT INTO devices (id, name, token_hash) VALUES ('d1','old','h')`); err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate over P0 schema: %v", err)
	}
	// Pairing writes add updated_at idempotently on a Migrate-only DB.
	if _, _, _, err := EnrollDevice(db, "new", "fcm-mig"); err != nil {
		t.Fatalf("EnrollDevice after Migrate: %v", err)
	}
	if _, _, _, err := EnrollDevice(db, "new", "fcm-mig"); err != nil {
		t.Fatalf("re-pair after Migrate: %v", err)
	}
	rows, err := db.Query(`PRAGMA table_info(devices)`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	seen := map[string]bool{}
	var cid int
	var name, ctype string
	var notnull, pk int
	var dflt sql.NullString
	for rows.Next() {
		if err := rows.Scan(&cid, &name, &ctype, &notnull, &dflt, &pk); err != nil {
			t.Fatal(err)
		}
		seen[name] = true
	}
	if err := rows.Err(); err != nil {
		t.Fatal(err)
	}
	for _, col := range []string{"created_at", "revoked_at", "fcm_token", "updated_at"} {
		if !seen[col] {
			t.Fatalf("column %s missing after pairing migration", col)
		}
	}
	if _, err := ListDevices(db); err != nil {
		t.Fatalf("ListDevices after migration: %v", err)
	}
}

// PurgeDevice hard-deletes revoked rows only: active rows are refused,
// unknown IDs report not-found, and a purged token authenticates 401
// (unknown) exactly like a revoked one.
func TestPurgeDeviceRevokedOnly(t *testing.T) {
	db := openDevicesTestDB(t)

	liveID, liveToken, _, err := EnrollDevice(db, "Live", "fcm-live-purge")
	if err != nil {
		t.Fatal(err)
	}
	goneID, goneToken, _, err := EnrollDevice(db, "Gone", "fcm-gone-purge")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := RevokeDevice(db, goneID); err != nil {
		t.Fatal(err)
	}

	// Active rows are refused (no delete), unknown IDs not-found.
	if found, err := PurgeDevice(db, liveID); err != nil || found {
		t.Fatalf("purge active: found=%v err=%v, want false", found, err)
	}
	if found, err := PurgeDevice(db, "does-not-exist"); err != nil || found {
		t.Fatalf("purge unknown: found=%v err=%v, want false", found, err)
	}
	var n int
	if err := db.QueryRow(`SELECT COUNT(*) FROM devices`).Scan(&n); err != nil || n != 2 {
		t.Fatalf("refused purges must not delete: n=%d err=%v", n, err)
	}

	// Purging the revoked row hard-deletes it; re-purge is not-found.
	if found, err := PurgeDevice(db, goneID); err != nil || !found {
		t.Fatalf("purge revoked: found=%v err=%v, want true", found, err)
	}
	if found, err := PurgeDevice(db, goneID); err != nil || found {
		t.Fatalf("re-purge: found=%v err=%v, want false", found, err)
	}

	// Both tokens now fail closed: purged is 401-unknown, revoked-live
	// would be 401-revoked — the same operator-visible outcome.
	bearerReq := func(tok string) *http.Request {
		r, _ := http.NewRequest("GET", "/api/v1/widgets", nil)
		r.Header.Set("Authorization", "Bearer "+tok)
		return r
	}
	if _, err := AuthenticateDevice(db, bearerReq(goneToken)); err != ErrDeviceAuthInvalid {
		t.Fatalf("purged token auth: err=%v, want invalid", err)
	}
	if _, err := AuthenticateDevice(db, bearerReq(liveToken)); err != nil {
		t.Fatalf("live token must still authenticate: %v", err)
	}
}
