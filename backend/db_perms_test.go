package main

import (
	"os"
	"path/filepath"
	"testing"
)

// OpenDB must keep the sqlite state owner-only: parent dir 0700, DB file
// 0600 — the DB holds token hashes and per-device key material.
func TestOpenDBPermissions(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "sub")
	path := filepath.Join(dir, "perms.db")

	db, err := OpenDB(path)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	defer db.Close()
	// Materialize the file through real use, then check modes.
	if err := Migrate(db); err != nil {
		t.Fatalf("Migrate: %v", err)
	}

	if fi, err := os.Stat(dir); err != nil {
		t.Fatalf("stat dir: %v", err)
	} else if got := fi.Mode().Perm(); got != 0o700 {
		t.Fatalf("dir mode = %04o, want 0700", got)
	}
	if fi, err := os.Stat(path); err != nil {
		t.Fatalf("stat db: %v", err)
	} else if got := fi.Mode().Perm(); got != 0o600 {
		t.Fatalf("db mode = %04o, want 0600", got)
	}
}

// A pre-existing world-readable DB file must be tightened on open.
func TestOpenDBTightensExistingFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "loose.db")
	if err := os.WriteFile(path, nil, 0o644); err != nil {
		t.Fatalf("seed file: %v", err)
	}
	db, err := OpenDB(path)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	db.Close()
	if fi, err := os.Stat(path); err != nil {
		t.Fatalf("stat db: %v", err)
	} else if got := fi.Mode().Perm(); got != 0o600 {
		t.Fatalf("db mode = %04o, want 0600", got)
	}
}

// A pre-existing world-readable parent dir must be tightened on open,
// mirroring TestOpenDBTightensExistingFile for the file itself.
func TestOpenDBTightensExistingDir(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "loosedir")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("seed dir: %v", err)
	}
	if err := os.Chmod(dir, 0o755); err != nil {
		t.Fatalf("seed chmod: %v", err)
	}
	path := filepath.Join(dir, "tight.db")
	db, err := OpenDB(path)
	if err != nil {
		t.Fatalf("OpenDB: %v", err)
	}
	db.Close()
	if fi, err := os.Stat(dir); err != nil {
		t.Fatalf("stat dir: %v", err)
	} else if got := fi.Mode().Perm(); got != 0o700 {
		t.Fatalf("dir mode = %04o, want 0700", got)
	}
	if fi, err := os.Stat(path); err != nil {
		t.Fatalf("stat db: %v", err)
	} else if got := fi.Mode().Perm(); got != 0o600 {
		t.Fatalf("db mode = %04o, want 0600", got)
	}
}

// A symlinked parent dir must fail closed instead of managing through the
// link (MkdirAll/Chmod follow symlinks, so they would retarget outside the
// state dir). Nothing may be created on either side of the link.
func TestOpenDBRefusesSymlinkedDir(t *testing.T) {
	base := t.TempDir()
	real := filepath.Join(base, "real")
	if err := os.MkdirAll(real, 0o755); err != nil {
		t.Fatalf("seed dir: %v", err)
	}
	link := filepath.Join(base, "link")
	if err := os.Symlink(real, link); err != nil {
		t.Fatalf("seed symlink: %v", err)
	}
	if db, err := OpenDB(filepath.Join(link, "tight.db")); err == nil {
		db.Close()
		t.Fatal("OpenDB through symlinked dir: want fail-closed error")
	}
	for _, p := range []string{filepath.Join(link, "tight.db"), filepath.Join(real, "tight.db")} {
		if _, err := os.Lstat(p); !os.IsNotExist(err) {
			t.Fatalf("refused open created %q (err=%v)", p, err)
		}
	}
}
