package main

// Batch C auth-hardening tests: auth no-store routing, secret-aware widget
// cache policy, and the FCM credentials boot gate.

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestAuthNoStorePaths(t *testing.T) {
	for _, p := range []string{
		"/login", "/logout",
		"/api/v1/enroll", "/api/v1/enroll-tokens",
		"/webui/pair", "/webui/pair/revoke",
	} {
		if !authNoStore(p) {
			t.Errorf("authNoStore(%q) = false, want true", p)
		}
	}
	for _, p := range []string{
		"/healthz", "/api/v1/devices", "/api/v1/widgets",
		"/api/v1/notify", "/api/v1/enroll-tokens-extra",
	} {
		if authNoStore(p) {
			t.Errorf("authNoStore(%q) = true, want false", p)
		}
	}
}

func TestWithAuthNoStoreMiddleware(t *testing.T) {
	stub := http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusCreated)
		_, _ = w.Write([]byte(`{}`))
	})
	h := withAuthNoStore(stub)

	req := httptest.NewRequest("POST", "/api/v1/enroll", nil)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if w.Code != http.StatusCreated {
		t.Fatalf("enroll passthrough: got %d, want 201", w.Code)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("enroll Cache-Control %q, want no-store", cc)
	}

	req = httptest.NewRequest("GET", "/healthz", nil)
	w = httptest.NewRecorder()
	h.ServeHTTP(w, req)
	if cc := w.Header().Get("Cache-Control"); cc != "" {
		t.Errorf("healthz Cache-Control %q, want untouched", cc)
	}
}

func widgetStubHandler(body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Cache-Control", "private, max-age=60, must-revalidate")
		w.Header().Set("ETag", `"stub"`)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(body))
	}
}

func TestWidgetCachePolicySecretVsNonSecret(t *testing.T) {
	// Any secret envelope → private, no-store (fresh ciphertext per request
	// must never sit in a cache), ETag preserved.
	secret := `{"widgets":[{"id":"vault","type":"secret","title":"Vault","updated_at":"t","payload":{"alg":"a"},"secret_ref":"dev-1"}]}`
	w := httptest.NewRecorder()
	withWidgetCachePolicy(widgetStubHandler(secret))(
		w, httptest.NewRequest("GET", "/api/v1/widgets", nil))
	if cc := w.Header().Get("Cache-Control"); cc != "private, no-store, must-revalidate" {
		t.Errorf("secret list Cache-Control %q, want private,no-store", cc)
	}
	if w.Header().Get("ETag") == "" {
		t.Error("secret list: ETag must pass through")
	}

	// Non-secret keeps the handler's max-age=60.
	plain := `{"widgets":[{"id":"hello","type":"text","title":"Hello","updated_at":"t","payload":{"body":"hi"}}]}`
	w = httptest.NewRecorder()
	withWidgetCachePolicy(widgetStubHandler(plain))(
		w, httptest.NewRequest("GET", "/api/v1/widgets", nil))
	if cc := w.Header().Get("Cache-Control"); cc != "private, max-age=60, must-revalidate" {
		t.Errorf("non-secret Cache-Control %q, want max-age=60 passthrough", cc)
	}
}

func TestCheckFCMCredentialsFile(t *testing.T) {
	if err := checkFCMCredentialsFile(""); err == nil {
		t.Error("empty path: expected fail-closed error")
	}
	if err := checkFCMCredentialsFile(filepath.Join(t.TempDir(), "missing.json")); err == nil {
		t.Error("missing file: expected fail-closed error")
	}
	dir := t.TempDir()
	if err := checkFCMCredentialsFile(dir); err == nil {
		t.Error("directory: expected fail-closed error (not a regular file)")
	}
	good := filepath.Join(t.TempDir(), "fcm.json")
	if err := os.WriteFile(good, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := checkFCMCredentialsFile(good); err != nil {
		t.Errorf("0600 file: unexpected error %v", err)
	}
	loose := filepath.Join(t.TempDir(), "loose.json")
	if err := os.WriteFile(loose, []byte(`{}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(loose, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := checkFCMCredentialsFile(loose); err == nil {
		t.Error("0644 file: expected fail-closed error (group/other readable)")
	} else if !strings.Contains(err.Error(), "0600") {
		t.Errorf("0644 rejection should name 0600, got %q", err)
	}
}

// The no-store pin lives inside the mux constructors (not only at
// ListenAndServe), so even the test routers serve enroll secrets
// uncacheable. This pins it against future rewiring.
func TestTestRouterEnrollNoStore(t *testing.T) {
	mux, _, _, _ := setupPairingMux(t)
	// Missing token → 400, but the pin must hold on every status path.
	w := doReq(t, mux, "POST", "/api/v1/enroll", `{"device_name":"x"}`, "application/json", nil)
	if w.Code != http.StatusBadRequest {
		t.Fatalf("enroll missing token: got %d, want 400", w.Code)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("test-router enroll Cache-Control %q, want no-store", cc)
	}
	// Same for the issuance endpoint (operator-gated; 401 without session).
	w = doReq(t, mux, "POST", "/api/v1/enroll-tokens", "", "", nil)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("enroll-tokens anon: got %d, want 401", w.Code)
	}
	if cc := w.Header().Get("Cache-Control"); cc != "no-store" {
		t.Errorf("test-router enroll-tokens Cache-Control %q, want no-store", cc)
	}
}
