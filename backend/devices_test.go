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
