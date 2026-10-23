// Package crypto implements per-device payload encryption for Phone Companion.
//
// Wire contract: docs/api-contract.md, "Encrypted envelope for `secret`
// widgets". All secret-bearing fields use AES-256-GCM with a fresh random
// 96-bit nonce per message. This package is the reference implementation;
// Android and future backend call sites must produce byte-identical AAD so
// ciphertexts verify cross-platform.
//
// Envelope JSON:
//
//		{"alg":"AES-256-GCM","kid":"<device_id>","nonce":"<base64>","ciphertext":"<base64>"}
//
//	  - alg is fixed to AES-256-GCM at v1. Unknown values fail closed.
//	  - kid is the device_id whose secret_key encrypted the field.
//	  - nonce is 12 fresh crypto/rand bytes per message, base64-encoded.
//	  - ciphertext is the raw AES-GCM Seal output (ciphertext || 16-byte tag),
//	    base64-encoded as one field.
//	  - base64 everywhere in this contract is RFC 4648 section 4 (standard
//	    alphabet, "=" padding, no line breaks).
//
// Plaintext byte encoding: the UTF-8 bytes of the secret string value
// (unquoted — not JSON-quoted, not base64). The empty string (zero-length
// plaintext) is legal. Decrypt rejects output that is not valid UTF-8.
//
// AAD byte construction (exact, cross-platform):
//
//	AAD = UTF-8(kid) || 0x00 || UTF-8(binding)
//
// where binding is the widget `id` for secret widgets, or
// "notify/<notification_id>/<field>" for per-device notify secret fields
// (see NotifyBinding). The 0x00 separator is a single zero byte; kid and
// binding must be non-empty and must not contain 0x00 (server-issued ids,
// widget directory names, and field names never do — Encrypt rejects
// anything else). Decrypt recomputes these exact bytes; a transplanted
// ciphertext (wrong device, widget, notification, or field) fails
// authentication by design.
//
// Payload placement: for `secret` widgets the widget `payload` object IS
// exactly the envelope (four fields, no others), and the top-level
// `secret_ref` MUST equal `payload.kid` MUST equal the target device_id.
// Callers enforce that equality before Decrypt; a mismatch fails closed
// with no decrypt attempt. Notify per-device copies carry one envelope per
// secret-bearing field — there is no widget id in that path, the binding
// above replaces it.
//
// NOTE(callers) — two obligations live with envelope consumers (not this
// package), and the widgets-loader side is already tasked with both:
// (a) strict envelope JSON decoding that rejects unknown fields, so a
// future envelope extension cannot slip past a v1 parser silently; and
// (b) the secret_ref == payload.kid == target device_id triple-equality
// check before Decrypt. This package takes a decoded Envelope struct, so
// it cannot enforce (a); it enforces env.Kid == kid itself.
//
// Memory lifetime: keys live in caller-owned memory (device rows, enroll
// responses, in-memory caches). Callers MUST zeroize key copies with
// clear() where feasible when rotating or discarding them (re-pair,
// revocation). This package keeps no key state, so there is nothing to
// wipe here; key-sized stack copies during Encrypt/Decrypt die with the
// call.
//
// Dependencies are stdlib only (crypto/aes, crypto/cipher, crypto/rand).
// Tests use one fixed vector (for the Kotlin port) plus random round
// trips; keys are never logged.
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

// Algorithm is the only accepted envelope alg value at v1.
const Algorithm = "AES-256-GCM"

// KeySize is the per-device key length in bytes (256-bit).
const KeySize = 32

// NonceSize is the GCM nonce length in bytes (96-bit).
const NonceSize = 12

// TagSize is the GCM authentication tag length in bytes.
const TagSize = 16

var (
	// Sentinel errors distinguish failure modes for tests only. Production
	// callers MUST map every error from this package to a single wire/UI
	// outcome ("needs refresh") and MUST NOT branch user-visible behavior
	// on which sentinel fired — that would be a decrypt oracle.
	//
	// ErrUnknownAlgorithm is returned when the envelope alg is not
	// Algorithm. No decrypt is attempted.
	ErrUnknownAlgorithm = errors.New("crypto: unknown alg")
	// ErrKidMismatch is returned when the envelope kid does not equal the
	// expected device id (or the top-level secret_ref disagrees). No decrypt
	// is attempted.
	ErrKidMismatch = errors.New("crypto: kid mismatch")
	// ErrInvalidEnvelope is returned for malformed envelopes: empty kid or
	// binding, bad base64, or a nonce of the wrong length.
	ErrInvalidEnvelope = errors.New("crypto: invalid envelope")
	// ErrDecryptFailed is returned when AES-GCM authentication fails
	// (wrong key, transplanted ciphertext, tampered bytes, or non-UTF-8
	// plaintext). It is also returned for corrupt inputs that decode but do
	// not authenticate; callers must treat every error as fail-closed.
	ErrDecryptFailed = errors.New("crypto: decrypt failed")
)

// Envelope is the JSON envelope for one encrypted secret-bearing field.
// Nonce and Ciphertext hold base64 (RFC 4648 section 4, padded, no line
// breaks) of the 12-byte nonce and the raw AES-GCM output respectively.
type Envelope struct {
	Alg        string `json:"alg"`
	Kid        string `json:"kid"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}

// GenerateKey creates a fresh random 256-bit per-device key.
func GenerateKey() (key [KeySize]byte, err error) {
	if _, err := rand.Read(key[:]); err != nil {
		return [KeySize]byte{}, fmt.Errorf("crypto: generate key: %w", err)
	}
	return key, nil
}

// EncodeKey renders a key as base64 for the one-time enroll response.
func EncodeKey(key [KeySize]byte) string {
	return base64.StdEncoding.EncodeToString(key[:])
}

// DecodeKey parses a base64 enroll key. It rejects wrong lengths.
func DecodeKey(s string) ([KeySize]byte, error) {
	raw, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return [KeySize]byte{}, fmt.Errorf("%w: key: %v", ErrInvalidEnvelope, err)
	}
	if len(raw) != KeySize {
		return [KeySize]byte{}, fmt.Errorf("%w: key length %d", ErrInvalidEnvelope, len(raw))
	}
	var key [KeySize]byte
	copy(key[:], raw)
	return key, nil
}

// BuildAAD returns the exact AES-GCM additional authenticated data for a
// (kid, binding) pair: UTF-8(kid) || 0x00 || UTF-8(binding). Exported so
// non-Go implementations can cross-check byte-identical construction.
func BuildAAD(kid, binding string) []byte {
	aad := make([]byte, 0, len(kid)+1+len(binding))
	aad = append(aad, kid...)
	aad = append(aad, 0x00)
	aad = append(aad, binding...)
	return aad
}

// NotifyBinding builds the AAD binding for a per-device notify secret field:
// "notify/<notification_id>/<field>". There is no widget id in the notify
// path; the notification id plus field name takes its place so a ciphertext
// cannot move across devices, notifications, or fields. It fails closed on
// empty segments or segments containing "/" (the join separator —
// otherwise "a/b"+"c" and "a"+"b/c" would collide) or 0x00 (the AAD
// separator): such ids/fields are never server-issued, so there is nothing
// legitimate to encrypt under them.
func NotifyBinding(notificationID, field string) (string, error) {
	for _, seg := range []string{notificationID, field} {
		if seg == "" {
			return "", fmt.Errorf("%w: empty notify binding segment", ErrInvalidEnvelope)
		}
		if strings.ContainsAny(seg, "/\x00") {
			return "", fmt.Errorf("%w: notify binding segment with separator", ErrInvalidEnvelope)
		}
	}
	return "notify/" + notificationID + "/" + field, nil
}

// validParty reports whether s is usable as a kid or binding: non-empty and
// free of the 0x00 separator byte.
func validParty(s string) bool {
	if s == "" {
		return false
	}
	for i := 0; i < len(s); i++ {
		if s[i] == 0x00 {
			return false
		}
	}
	return true
}

// Encrypt seals plaintext (UTF-8 bytes of the secret string value) under key
// for device kid bound to binding, generating a fresh random 96-bit nonce.
// It fails closed on empty kid/binding, non-UTF-8 plaintext, or RNG failure.
func Encrypt(key [KeySize]byte, kid, binding string, plaintext []byte) (Envelope, error) {
	if !validParty(kid) {
		return Envelope{}, fmt.Errorf("%w: empty or NUL kid", ErrInvalidEnvelope)
	}
	if !validParty(binding) {
		return Envelope{}, fmt.Errorf("%w: empty or NUL binding", ErrInvalidEnvelope)
	}
	if !utf8.Valid(plaintext) {
		return Envelope{}, fmt.Errorf("%w: plaintext is not valid UTF-8", ErrInvalidEnvelope)
	}
	var nonce [NonceSize]byte
	if _, err := rand.Read(nonce[:]); err != nil {
		return Envelope{}, fmt.Errorf("crypto: generate nonce: %w", err)
	}
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return Envelope{}, fmt.Errorf("crypto: new cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return Envelope{}, fmt.Errorf("crypto: new GCM: %w", err)
	}
	sealed := gcm.Seal(nil, nonce[:], plaintext, BuildAAD(kid, binding))
	return Envelope{
		Alg:        Algorithm,
		Kid:        kid,
		Nonce:      base64.StdEncoding.EncodeToString(nonce[:]),
		Ciphertext: base64.StdEncoding.EncodeToString(sealed),
	}, nil
}

// Decrypt opens env under key, requiring env.Kid == kid and authentication
// under BuildAAD(kid, binding). The alg check and kid/binding match happen
// before any crypto; every failure — unknown alg, kid mismatch, malformed
// base64, wrong nonce length, auth-tag failure, non-UTF-8 output — is a
// fail-closed error and yields no plaintext.
func Decrypt(key [KeySize]byte, kid, binding string, env Envelope) ([]byte, error) {
	if env.Alg != Algorithm {
		return nil, fmt.Errorf("%w: %q", ErrUnknownAlgorithm, env.Alg)
	}
	if !validParty(kid) || !validParty(binding) {
		return nil, fmt.Errorf("%w: empty or NUL expected kid/binding", ErrInvalidEnvelope)
	}
	if env.Kid != kid {
		return nil, fmt.Errorf("%w: envelope for different device", ErrKidMismatch)
	}
	nonce, err := base64.StdEncoding.DecodeString(env.Nonce)
	if err != nil {
		return nil, fmt.Errorf("%w: nonce: %v", ErrInvalidEnvelope, err)
	}
	if len(nonce) != NonceSize {
		return nil, fmt.Errorf("%w: nonce length %d", ErrInvalidEnvelope, len(nonce))
	}
	raw, err := base64.StdEncoding.DecodeString(env.Ciphertext)
	if err != nil {
		return nil, fmt.Errorf("%w: ciphertext: %v", ErrInvalidEnvelope, err)
	}
	if len(raw) < TagSize {
		return nil, fmt.Errorf("%w: ciphertext too short", ErrDecryptFailed)
	}
	block, err := aes.NewCipher(key[:])
	if err != nil {
		return nil, fmt.Errorf("crypto: new cipher: %w", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, fmt.Errorf("crypto: new GCM: %w", err)
	}
	plain, err := gcm.Open(nil, nonce, raw, BuildAAD(kid, binding))
	if err != nil {
		return nil, fmt.Errorf("%w: authentication failure", ErrDecryptFailed)
	}
	if !utf8.Valid(plain) {
		return nil, fmt.Errorf("%w: output is not valid UTF-8", ErrDecryptFailed)
	}
	return plain, nil
}
