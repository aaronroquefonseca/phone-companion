package crypto

import (
	"bytes"
	"encoding/base64"
	"errors"
	"strings"
	"testing"
)

func mustKey(t *testing.T) [KeySize]byte {
	t.Helper()
	k, err := GenerateKey()
	if err != nil {
		t.Fatalf("GenerateKey: %v", err)
	}
	return k
}

func TestRoundTrip(t *testing.T) {
	key := mustKey(t)
	for _, tc := range []string{
		"hello",
		"",
		"unicode: café ☕ — こんにちは",
		strings.Repeat("long-secret-body ", 256),
	} {
		env, err := Encrypt(key, "dev-1", "hello", []byte(tc))
		if err != nil {
			t.Fatalf("Encrypt(%q): %v", tc, err)
		}
		if env.Alg != Algorithm {
			t.Fatalf("alg = %q, want %q", env.Alg, Algorithm)
		}
		nonce, err := base64.StdEncoding.DecodeString(env.Nonce)
		if err != nil || len(nonce) != NonceSize {
			t.Fatalf("nonce decodes to %d bytes, err=%v", len(nonce), err)
		}
		got, err := Decrypt(key, "dev-1", "hello", env)
		if err != nil {
			t.Fatalf("Decrypt(%q): %v", tc, err)
		}
		if !bytes.Equal(got, []byte(tc)) {
			t.Fatalf("round trip mismatch for %q", tc)
		}
	}
}

func TestNonceFreshness(t *testing.T) {
	key := mustKey(t)
	a, err := Encrypt(key, "dev-1", "hello", []byte("same"))
	if err != nil {
		t.Fatal(err)
	}
	b, err := Encrypt(key, "dev-1", "hello", []byte("same"))
	if err != nil {
		t.Fatal(err)
	}
	if a.Nonce == b.Nonce {
		t.Fatal("nonce reused across two messages")
	}
	if a.Ciphertext == b.Ciphertext {
		t.Fatal("ciphertext identical across two messages with fresh nonces")
	}
}

func TestWrongKeyFails(t *testing.T) {
	a := mustKey(t)
	b := mustKey(t)
	env, err := Encrypt(a, "dev-1", "hello", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Decrypt(b, "dev-1", "hello", env); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("wrong-key decrypt err = %v, want ErrDecryptFailed", err)
	}
}

func TestTamperedNonceFails(t *testing.T) {
	key := mustKey(t)
	env, err := Encrypt(key, "dev-1", "hello", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := base64.StdEncoding.DecodeString(env.Nonce)
	raw[0] ^= 0x01
	env.Nonce = base64.StdEncoding.EncodeToString(raw)
	if _, err := Decrypt(key, "dev-1", "hello", env); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("tampered-nonce decrypt err = %v, want ErrDecryptFailed", err)
	}
}

func TestTamperedCiphertextFails(t *testing.T) {
	key := mustKey(t)
	env, err := Encrypt(key, "dev-1", "hello", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	raw, _ := base64.StdEncoding.DecodeString(env.Ciphertext)
	raw[len(raw)/2] ^= 0x01
	env.Ciphertext = base64.StdEncoding.EncodeToString(raw)
	if _, err := Decrypt(key, "dev-1", "hello", env); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("tampered-ciphertext decrypt err = %v, want ErrDecryptFailed", err)
	}
}

func TestUnknownAlgFails(t *testing.T) {
	key := mustKey(t)
	env, err := Encrypt(key, "dev-1", "hello", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	for _, alg := range []string{"", "AES-256-CBC", "aes-256-gcm", "CHACHA20-POLY1305"} {
		env.Alg = alg
		if _, err := Decrypt(key, "dev-1", "hello", env); !errors.Is(err, ErrUnknownAlgorithm) {
			t.Fatalf("alg %q decrypt err = %v, want ErrUnknownAlgorithm", alg, err)
		}
	}
}

func TestAADBinding(t *testing.T) {
	key := mustKey(t)
	env, err := Encrypt(key, "dev-1", "hello", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	// Wrong kid: fail closed with kid mismatch, no decrypt attempt.
	if _, err := Decrypt(key, "dev-2", "hello", env); !errors.Is(err, ErrKidMismatch) {
		t.Fatalf("wrong-kid decrypt err = %v, want ErrKidMismatch", err)
	}
	// Transplanted kid field: same failure even though the struct was edited.
	moved := env
	moved.Kid = "dev-2"
	if _, err := Decrypt(key, "dev-2", "hello", moved); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("transplanted ciphertext decrypt err = %v, want ErrDecryptFailed", err)
	}
	// Wrong binding (widget id / notify context): authentication failure.
	if _, err := Decrypt(key, "dev-1", "other-widget", env); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("wrong-binding decrypt err = %v, want ErrDecryptFailed", err)
	}
	// Notify bindings differ per notification and per field.
	nb := NotifyBinding("n-1", "body")
	nenv, err := Encrypt(key, "dev-1", nb, []byte("dinner"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Decrypt(key, "dev-1", NotifyBinding("n-2", "body"), nenv); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("wrong-notification decrypt err = %v, want ErrDecryptFailed", err)
	}
	if _, err := Decrypt(key, "dev-1", NotifyBinding("n-1", "title"), nenv); !errors.Is(err, ErrDecryptFailed) {
		t.Fatalf("wrong-field decrypt err = %v, want ErrDecryptFailed", err)
	}
}

func TestMalformedEnvelopesFailClosed(t *testing.T) {
	key := mustKey(t)
	good, err := Encrypt(key, "dev-1", "hello", []byte("secret"))
	if err != nil {
		t.Fatal(err)
	}
	badNonce := good
	badNonce.Nonce = "!!!not-base64!!!"
	if _, err := Decrypt(key, "dev-1", "hello", badNonce); err == nil {
		t.Fatal("bad nonce base64 accepted")
	}
	shortNonce := good
	shortNonce.Nonce = base64.StdEncoding.EncodeToString([]byte("short"))
	if _, err := Decrypt(key, "dev-1", "hello", shortNonce); !errors.Is(err, ErrInvalidEnvelope) {
		t.Fatalf("short nonce err = %v, want ErrInvalidEnvelope", err)
	}
	badCT := good
	badCT.Ciphertext = "!!!not-base64!!!"
	if _, err := Decrypt(key, "dev-1", "hello", badCT); err == nil {
		t.Fatal("bad ciphertext base64 accepted")
	}
	if _, err := Encrypt(key, "", "hello", []byte("x")); err == nil {
		t.Fatal("empty kid accepted")
	}
	if _, err := Encrypt(key, "dev-1", "", []byte("x")); err == nil {
		t.Fatal("empty binding accepted")
	}
	if _, err := Encrypt(key, "dev-1", "hello", []byte{0xff, 0xfe}); err == nil {
		t.Fatal("non-UTF-8 plaintext accepted")
	}
}

func TestKeyHelpers(t *testing.T) {
	k := mustKey(t)
	s := EncodeKey(k)
	if strings.ContainsAny(s, "\n\r") {
		t.Fatal("key base64 contains line breaks")
	}
	back, err := DecodeKey(s)
	if err != nil {
		t.Fatal(err)
	}
	if back != k {
		t.Fatal("key round trip mismatch")
	}
	if _, err := DecodeKey("!!!"); err == nil {
		t.Fatal("bad key base64 accepted")
	}
	if _, err := DecodeKey(base64.StdEncoding.EncodeToString([]byte("short"))); err == nil {
		t.Fatal("short key accepted")
	}
}

func TestAADConstruction(t *testing.T) {
	got := BuildAAD("dev-1", "hello")
	want := append(append([]byte("dev-1"), 0x00), []byte("hello")...)
	if !bytes.Equal(got, want) {
		t.Fatalf("AAD = %q, want %q", got, want)
	}
	if NotifyBinding("n-1", "body") != "notify/n-1/body" {
		t.Fatalf("NotifyBinding = %q", NotifyBinding("n-1", "body"))
	}
}
