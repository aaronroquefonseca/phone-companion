// P10 secret widget round-trip tests: per-device encryption at serve time.
package main

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strings"
	"testing"

	"github.com/aaronroquefonseca/phone-companion/backend/crypto"
)

// setupRoundTripEnv enrolls two devices, writes one text + one secret widget
// targeted at devA, loads the registry, and returns the mux plus credentials.
func setupRoundTripEnv(t *testing.T, secretValue string) (http.Handler, string, string, string, string, string) {
	t.Helper()
	db, err := OpenDB(t.TempDir() + "/roundtrip.db")
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	old := deviceDB
	SetDeviceDB(db)
	t.Cleanup(func() {
		db.Close()
		SetDeviceDB(old)
	})
	idA, tokA, keyA, err := EnrollDevice(db, "Phone A", "fcm-roundtrip-a")
	if err != nil {
		t.Fatal(err)
	}
	idB, tokB, _, err := EnrollDevice(db, "Phone B", "fcm-roundtrip-b")
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	secretJSON, _ := json.Marshal(map[string]string{"secret": secretValue})
	writeWidget(t, dir, "vault",
		"id = \"vault\"\ntype = \"secret\"\ntitle = \"Vault\"\nsecret_ref = \""+idA+"\"\n",
		"", string(secretJSON))
	writeWidget(t, dir, "hello",
		"id = \"hello\"\ntype = \"text\"\ntitle = \"Hello\"\n",
		"# Hello\n", "")
	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)
	return mux, idA, tokA, keyA, idB, tokB
}

func decodeRoundTripKey(t *testing.T, keyB64 string) [crypto.KeySize]byte {
	t.Helper()
	raw, err := base64.StdEncoding.DecodeString(keyB64)
	if err != nil || len(raw) != crypto.KeySize {
		t.Fatalf("bad test key: %v", err)
	}
	var k [crypto.KeySize]byte
	copy(k[:], raw)
	return k
}

func fetchSecretEnvelope(t *testing.T, mux http.Handler, target, token string) (widgetEnvelope, int, string) {
	t.Helper()
	rec := authedGet(mux, "GET", target, token, nil)
	if rec.Code != http.StatusOK {
		return widgetEnvelope{}, rec.Code, rec.Body.String()
	}
	var env widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &env); err != nil {
		t.Fatalf("decode %s: %v (%s)", target, err, rec.Body.String())
	}
	return env, rec.Code, ""
}

// TestSecretRoundTrip encrypts per-device at serve and decrypts via
// crypto.Decrypt with the exact AAD (kid == device_id, binding == widget id).
func TestSecretRoundTrip(t *testing.T) {
	mux, idA, tokA, keyA, _, _ := setupRoundTripEnv(t, "hello secret")
	env, code, body := fetchSecretEnvelope(t, mux, "/api/v1/widgets/vault", tokA)
	if code != http.StatusOK {
		t.Fatalf("own secret status = %d (%s)", code, body)
	}
	if env.SecretRef != idA {
		t.Fatalf("secret_ref = %q, want %q", env.SecretRef, idA)
	}
	var wire crypto.Envelope
	if err := json.Unmarshal(env.Payload, &wire); err != nil {
		t.Fatalf("payload not a P5 envelope: %v (%s)", err, env.Payload)
	}
	if wire.Alg != crypto.Algorithm || wire.Kid != idA {
		t.Fatalf("bad envelope identity: %+v", wire)
	}
	key := decodeRoundTripKey(t, keyA)
	plain, err := crypto.Decrypt(key, idA, "vault", wire)
	if err != nil {
		t.Fatalf("decrypt served envelope: %v", err)
	}
	if string(plain) != "hello secret" {
		t.Fatalf("plaintext = %q, want %q", plain, "hello secret")
	}
	// Non-secret unchanged.
	rec := authedGet(mux, "GET", "/api/v1/widgets/hello", tokA, nil)
	var text widgetEnvelope
	if err := json.Unmarshal(rec.Body.Bytes(), &text); err != nil {
		t.Fatal(err)
	}
	var tp map[string]string
	if err := json.Unmarshal(text.Payload, &tp); err != nil || tp["body"] != "# Hello\n" {
		t.Fatalf("text payload changed: %s", text.Payload)
	}
	// Unauthenticated → 401.
	unauth := authedGet(mux, "GET", "/api/v1/widgets/vault", "", nil)
	if unauth.Code != http.StatusUnauthorized {
		t.Fatalf("no-auth status = %d, want 401", unauth.Code)
	}
}

// TestSecretWrongDevice404 ensures a device never learns whether a foreign
// secret exists: same 404 as an unknown id, no envelope, no plaintext.
func TestSecretWrongDevice404(t *testing.T) {
	mux, _, tokA, _, _, tokB := setupRoundTripEnv(t, "top-secret-value")
	// Owner sees it.
	if rec := authedGet(mux, "GET", "/api/v1/widgets/vault", tokA, nil); rec.Code != http.StatusOK {
		t.Fatalf("owner status = %d, want 200", rec.Code)
	}
	// Foreign device: 404 with the same shape as unknown ids.
	foreign := authedGet(mux, "GET", "/api/v1/widgets/vault", tokB, nil)
	if foreign.Code != http.StatusNotFound {
		t.Fatalf("foreign status = %d, want 404", foreign.Code)
	}
	var errBody map[string]string
	if err := json.Unmarshal(foreign.Body.Bytes(), &errBody); err != nil || errBody["error"] != "widget_not_found" {
		t.Fatalf("foreign body = %q, want widget_not_found", foreign.Body.String())
	}
	if strings.Contains(foreign.Body.String(), "top-secret-value") {
		t.Fatal("foreign response leaks plaintext")
	}
	unknown := authedGet(mux, "GET", "/api/v1/widgets/no-such-widget", tokB, nil)
	if unknown.Code != http.StatusNotFound {
		t.Fatalf("unknown status = %d, want 404", unknown.Code)
	}
	if unknown.Body.String() != foreign.Body.String() {
		t.Fatalf("oracle: foreign %q != unknown %q", foreign.Body.String(), unknown.Body.String())
	}
	// Foreign list omits the secret entirely.
	var list struct {
		Widgets []widgetEnvelope `json:"widgets"`
	}
	rec := authedGet(mux, "GET", "/api/v1/widgets", tokB, nil)
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	for _, w := range list.Widgets {
		if w.ID == "vault" {
			t.Fatalf("foreign list leaks secret: %s", rec.Body.String())
		}
	}
}

// TestSecretRotationInvalidatesOldCiphertext: re-pair rotates secret_key, so
// ciphertexts encrypted under the old key fail closed under the new one,
// while the next poll serves a freshly-encrypted envelope that decrypts.
func TestSecretRotationInvalidatesOldCiphertext(t *testing.T) {
	db, err := OpenDB(t.TempDir() + "/rotation.db")
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(db); err != nil {
		t.Fatal(err)
	}
	old := deviceDB
	SetDeviceDB(db)
	t.Cleanup(func() {
		db.Close()
		SetDeviceDB(old)
	})
	idA, tokA, keyA, err := EnrollDevice(db, "Phone A", "fcm-rotation-a")
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	writeWidget(t, dir, "vault",
		"id = \"vault\"\ntype = \"secret\"\ntitle = \"Vault\"\nsecret_ref = \""+idA+"\"\n",
		"", `{"secret": "rotation-value"}`)
	reg := NewWidgetRegistry(dir)
	if err := reg.Load(); err != nil {
		t.Fatal(err)
	}
	mux := NewMuxWithWidgets(reg, nil, nil)

	env, code, body := fetchSecretEnvelope(t, mux, "/api/v1/widgets/vault", tokA)
	if code != http.StatusOK {
		t.Fatalf("pre-rotation status = %d (%s)", code, body)
	}
	var oldWire crypto.Envelope
	if err := json.Unmarshal(env.Payload, &oldWire); err != nil {
		t.Fatal(err)
	}
	oldKey := decodeRoundTripKey(t, keyA)
	if _, err := crypto.Decrypt(oldKey, idA, "vault", oldWire); err != nil {
		t.Fatalf("pre-rotation decrypt: %v", err)
	}

	// Re-pair with the same fcm_token: same device id, fresh token + key.
	idA2, tokA2, keyA2, err := EnrollDevice(db, "Phone A", "fcm-rotation-a")
	if err != nil {
		t.Fatal(err)
	}
	if idA2 != idA {
		t.Fatal("rotation must keep the same device id")
	}
	if keyA2 == keyA {
		t.Fatal("rotation must issue a fresh key")
	}
	newKey := decodeRoundTripKey(t, keyA2)
	if _, err := crypto.Decrypt(newKey, idA, "vault", oldWire); err == nil {
		t.Fatal("old ciphertext decrypts under rotated key (must fail closed)")
	}
	// Old bearer is dead.
	if rec := authedGet(mux, "GET", "/api/v1/widgets/vault", tokA, nil); rec.Code != http.StatusUnauthorized {
		t.Fatalf("old token status = %d, want 401", rec.Code)
	}
	// Next poll with the new token serves fresh ciphertext that decrypts.
	env2, code2, body2 := fetchSecretEnvelope(t, mux, "/api/v1/widgets/vault", tokA2)
	if code2 != http.StatusOK {
		t.Fatalf("post-rotation status = %d (%s)", code2, body2)
	}
	var newWire crypto.Envelope
	if err := json.Unmarshal(env2.Payload, &newWire); err != nil {
		t.Fatal(err)
	}
	plain, err := crypto.Decrypt(newKey, idA, "vault", newWire)
	if err != nil || string(plain) != "rotation-value" {
		t.Fatalf("post-rotation decrypt = %q err=%v", plain, err)
	}
}
