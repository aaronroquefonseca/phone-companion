// Package notify — real FCM HTTPv1 transport (Wave-2 push).
//
// FCMSender delivers each per-device data payload to the device's FCM
// registration token (devices.fcm_token) via FCM HTTPv1
// (POST /v1/projects/<projectID>/messages:send) with a short-lived OAuth2
// access token minted from the service-account JSON at CredentialsFile
// (FCM_CREDENTIALS_FILE, 0600-enforced at boot; never committed).
//
// Secrecy: only data payloads are ever POSTed — for secret messages the
// handler pre-seals title/body into per-device P5 envelopes (see
// Message.SecretDataPayload), so the FCM leg carries ciphertext, never
// plaintext. Drivers log metadata only (notification_id, device id, kind);
// full content dumps stay behind NOTIFY_DEBUG=1 at the handler layer.
//
// Failure model: every send failure becomes a per-device failed history row
// (sanitized by RecordDelivery). Token-gone failures (UNREGISTERED /
// INVALID_ARGUMENT) return a typed *FCMError with ReRegister set so the
// handler (and operators reading history) can see the device needs a fresh
// token — the driver NEVER revokes the device row itself (revocation is
// destructive and stays an explicit operator action). When no device
// accepts, the handler answers 500 driver_unavailable (fail closed).
package notify

import (
	"bytes"
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"strings"
	"sync"
	"time"
)

// fcmOAuthScope is the single scope needed to send via FCM HTTPv1.
const fcmOAuthScope = "https://www.googleapis.com/auth/firebase.messaging"

// fcmTokenURI is the default OAuth2 token endpoint when the service-account
// JSON carries no explicit token_uri.
const fcmTokenURI = "https://oauth2.googleapis.com/token"

// fcmSendURLFmt renders the HTTPv1 send endpoint for a project.
const fcmSendURLFmt = "https://fcm.googleapis.com/v1/projects/%s/messages:send"

// FCM error codes surfaced as *FCMError.Code (also stored, sanitized, in
// notification_history.error so operators can grep for re-register work).
const (
	// FCMCodeUnregistered marks UNREGISTERED / NotRegistered: the token is
	// dead (app uninstalled, token rotated server-side). Re-register signal.
	FCMCodeUnregistered = "fcm-unregistered"
	// FCMCodeInvalidToken marks INVALID_ARGUMENT rejections of the
	// registration token. Re-register signal (fresh token fixes it).
	FCMCodeInvalidToken = "fcm-invalid-token"
	// FCMCodeMissingToken marks a device row with no stored registration
	// token (paired before push was configured). Re-register signal via
	// the token backfill path (re-pair or the token-update endpoint).
	FCMCodeMissingToken = "fcm-missing-token"
	// FCMCodeMisconfigured marks an unusable driver (no project/credentials,
	// unreadable key file). Fails the device closed, never sends.
	FCMCodeMisconfigured = "fcm-misconfigured"
	// FCMCodeAuthFailed marks OAuth2 mint/exchange failures.
	FCMCodeAuthFailed = "fcm-auth-failed"
	// FCMCodeSendFailed marks transport and non-token FCM rejections
	// (including 5xx). Transient: retry the notification, not the token.
	FCMCodeSendFailed = "fcm-send-failed"
)

// FCMError is the typed send failure for FCM deliveries. Handlers record
// err.Error() (sanitized at RecordDelivery time) into the per-device failed
// history row; ReRegister tells the handler/operator the device needs a
// fresh registration token. The driver never revokes on its own.
type FCMError struct {
	Code       string
	msg        string
	Permanent  bool
	ReRegister bool
}

func (e *FCMError) Error() string { return "notify: " + e.Code + ": " + e.msg }

// NeedsReRegistration reports whether err is a typed FCM failure signalling
// the device should upload a fresh registration token (unregistered,
// invalid, or missing token). It never fires for transient send/auth
// failures, and callers must NOT revoke on it — only record.
func NeedsReRegistration(err error) bool {
	var fe *FCMError
	return errors.As(err, &fe) && fe.ReRegister
}

// FCMSender delivers via FCM HTTPv1. Build with NewFCMSender; the zero value
// is unusable (fails closed as misconfigured until fields are set).
type FCMSender struct {
	ProjectID       string
	CredentialsFile string

	// HTTPClient performs the FCM and OAuth2 POSTs. Nil means a default
	// client with a 15s timeout. Tests inject a fake Transport here, so no
	// test in this package touches the network.
	HTTPClient *http.Client

	// TokenSource overrides OAuth2 minting (tests stub the access token so
	// no key material or network is involved). Nil selects the production
	// path: mint from CredentialsFile with in-memory caching.
	TokenSource func(ctx context.Context) (token string, err error)

	mu           sync.Mutex
	cachedToken  string
	cachedExpiry time.Time
}

// NewFCMSender builds the driver from already-resolved config.
func NewFCMSender(projectID, credentialsFile string) *FCMSender {
	return &FCMSender{ProjectID: projectID, CredentialsFile: credentialsFile}
}

// serviceAccountKey is the minimal service-account JSON shape the OAuth2
// mint needs. Extra fields are ignored.
type serviceAccountKey struct {
	Type        string `json:"type"`
	ProjectID   string `json:"project_id"`
	PrivateKey  string `json:"private_key"`
	ClientEmail string `json:"client_email"`
	TokenURI    string `json:"token_uri"`
}

// accessToken returns a live Bearer token, refreshing the cache when it is
// missing or expiring within a minute.
func (s *FCMSender) accessToken(ctx context.Context) (string, error) {
	if s.TokenSource != nil {
		return s.TokenSource(ctx)
	}
	s.mu.Lock()
	cached, expiry := s.cachedToken, s.cachedExpiry
	s.mu.Unlock()
	if cached != "" && time.Now().Add(time.Minute).Before(expiry) {
		return cached, nil
	}
	tok, expiresIn, err := s.mintAccessToken(ctx)
	if err != nil {
		return "", err
	}
	s.mu.Lock()
	s.cachedToken = tok
	s.cachedExpiry = time.Now().Add(expiresIn)
	s.mu.Unlock()
	return tok, nil
}

// dropCachedToken forgets the cached Bearer token so the next accessToken
// call mints fresh (used once after a 401/403 before giving up).
func (s *FCMSender) dropCachedToken() {
	s.mu.Lock()
	s.cachedToken = ""
	s.cachedExpiry = time.Time{}
	s.mu.Unlock()
}

// mintAccessToken loads the service-account JSON, signs a JWT grant, and
// exchanges it for an access token. Error text carries only the failure
// class — never key material, tokens, or file contents.
func (s *FCMSender) mintAccessToken(ctx context.Context) (string, time.Duration, error) {
	sa, err := loadServiceAccount(s.CredentialsFile)
	if err != nil {
		return "", 0, err
	}
	assertion, err := signServiceAccountJWT(sa, time.Now())
	if err != nil {
		return "", 0, &FCMError{Code: FCMCodeAuthFailed, msg: "cannot sign OAuth2 grant"}
	}
	return exchangeAssertion(s.client(), ctx, sa.tokenEndpoint(), assertion)
}

// exchangeAssertion trades a signed JWT grant for an access token. Split
// from mintAccessToken so tests can drive the exchange with an in-memory
// key (no key material on disk) and a stub transport (no network).
func exchangeAssertion(client *http.Client, ctx context.Context, endpoint, assertion string) (string, time.Duration, error) {
	form := url.Values{
		"grant_type": {"urn:ietf:params:oauth:grant-type:jwt-bearer"},
		"assertion":  {assertion},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint,
		strings.NewReader(form.Encode()))
	if err != nil {
		return "", 0, &FCMError{Code: FCMCodeAuthFailed, msg: "cannot build token request"}
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := client.Do(req)
	if err != nil {
		return "", 0, &FCMError{Code: FCMCodeAuthFailed, msg: "token endpoint unreachable"}
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if err != nil {
		return "", 0, &FCMError{Code: FCMCodeAuthFailed, msg: "cannot read token response"}
	}
	if resp.StatusCode != http.StatusOK {
		return "", 0, &FCMError{Code: FCMCodeAuthFailed,
			msg: fmt.Sprintf("token endpoint rejected grant: http %d", resp.StatusCode)}
	}
	var parsed struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int64  `json:"expires_in"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil || parsed.AccessToken == "" {
		return "", 0, &FCMError{Code: FCMCodeAuthFailed, msg: "malformed token response"}
	}
	ttl := time.Duration(parsed.ExpiresIn) * time.Second
	if ttl <= 0 || ttl > time.Hour {
		ttl = time.Hour
	}
	return parsed.AccessToken, ttl, nil
}

// tokenEndpoint returns the configured token_uri or the Google default.
func (sa *serviceAccountKey) tokenEndpoint() string {
	if strings.TrimSpace(sa.TokenURI) != "" {
		return sa.TokenURI
	}
	return fcmTokenURI
}

// Send implements Sender: POST the precomputed data payload as an FCM
// data-only message. The payload is passed through untouched — secret
// messages arrive here already sealed into envelopes by the handler, so
// plaintext title/body never reach this function, the FCM leg, or logs.
func (s *FCMSender) Send(ctx context.Context, device Device, payload map[string]string) error {
	if strings.TrimSpace(s.ProjectID) == "" || strings.TrimSpace(s.CredentialsFile) == "" {
		return &FCMError{Code: FCMCodeMisconfigured,
			msg: "FCM driver unconfigured (need FCM_PROJECT_ID and FCM_CREDENTIALS_FILE)"}
	}
	token := strings.TrimSpace(device.FCMToken)
	if token == "" {
		return &FCMError{Code: FCMCodeMissingToken, msg: "device has no FCM registration token (pair with push configured)",
			Permanent: true, ReRegister: true}
	}
	access, err := s.accessToken(ctx)
	if err != nil {
		return err
	}
	// One retry on 401/403: the cached Bearer token may have been revoked
	// between mint and use. Drop the cache, mint fresh, retry once — then
	// give up so an actually-broken grant cannot spin.
	if err := s.postMessage(ctx, access, token, payload); err != nil {
		var fe *FCMError
		if errors.As(err, &fe) && fe.Code == FCMCodeAuthFailed {
			s.dropCachedToken()
			if access, aerr := s.accessToken(ctx); aerr == nil {
				return s.postMessage(ctx, access, token, payload)
			}
		}
		return err
	}
	return nil
}

// fcmSendRequest is the HTTPv1 wire shape: data-only (no notification
// block), so FCM never sees title/body plaintext — only the opaque data map
// the handler built (envelopes for secret messages).
type fcmSendRequest struct {
	Message fcmSendMessage `json:"message"`
}

type fcmSendMessage struct {
	Token string            `json:"token"`
	Data  map[string]string `json:"data"`
}

// fcmErrorBody is the HTTPv1 error shape. Only status/errorCode drive the
// mapping; the human message is dropped (it can echo request detail and
// adds nothing operators can act on beyond the code).
type fcmErrorBody struct {
	Error struct {
		Code    int    `json:"code"`
		Status  string `json:"status"`
		Message string `json:"message"`
		Details []struct {
			Type      string `json:"@type"`
			ErrorCode string `json:"errorCode"`
		} `json:"details"`
	} `json:"error"`
}

// postMessage POSTs one data message and maps the outcome to nil or a typed
// *FCMError. It never logs payload values — only notification_id, device,
// and kind (metadata-only, matching LogSender).
func (s *FCMSender) postMessage(ctx context.Context, access, regToken string, payload map[string]string) error {
	body, err := json.Marshal(fcmSendRequest{Message: fcmSendMessage{Token: regToken, Data: payload}})
	if err != nil {
		return &FCMError{Code: FCMCodeSendFailed, msg: "cannot encode FCM request"}
	}
	endpoint := fmt.Sprintf(fcmSendURLFmt, url.PathEscape(strings.TrimSpace(s.ProjectID)))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(body))
	if err != nil {
		return &FCMError{Code: FCMCodeSendFailed, msg: "cannot build FCM request"}
	}
	req.Header.Set("Authorization", "Bearer "+access)
	req.Header.Set("Content-Type", "application/json")
	resp, err := s.client().Do(req)
	if err != nil {
		return &FCMError{Code: FCMCodeSendFailed, msg: "FCM endpoint unreachable"}
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 64*1024))
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		log.Printf("notify: fcm sent notification_id=%q device=%q kind=%q",
			payload["notification_id"], shortDeviceID(regToken), payload["kind"])
		return nil
	}
	return classifyFCMFailure(resp.StatusCode, raw)
}

// classifyFCMFailure maps an FCM HTTPv1 rejection to a typed error. Token
// death (UNREGISTERED / NotRegistered / INVALID_ARGUMENT on the token, 404)
// is a re-register signal: permanent for this token, fixed by the device
// uploading a fresh one. Everything else is transient (auth, 5xx, quota)
// and must NOT trigger re-registration. Response text is dropped — the code
// is the actionable signal and nothing request-derived is retained.
func classifyFCMFailure(httpStatus int, raw []byte) error {
	var parsed fcmErrorBody
	if err := json.Unmarshal(raw, &parsed); err == nil {
		status := strings.ToUpper(strings.TrimSpace(parsed.Error.Status))
		for _, d := range parsed.Error.Details {
			switch code := strings.ToUpper(strings.TrimSpace(d.ErrorCode)); code {
			case "UNREGISTERED":
				return &FCMError{Code: FCMCodeUnregistered,
					msg:       "FCM token unregistered (re-register device for a fresh token)",
					Permanent: true, ReRegister: true}
			case "INVALID_ARGUMENT":
				return &FCMError{Code: FCMCodeInvalidToken,
					msg:       "FCM token rejected as invalid (re-register device for a fresh token)",
					Permanent: true, ReRegister: true}
			}
		}
		switch status {
		case "NOT_FOUND":
			return &FCMError{Code: FCMCodeUnregistered,
				msg:       "FCM token unregistered (re-register device for a fresh token)",
				Permanent: true, ReRegister: true}
		case "INVALID_ARGUMENT":
			return &FCMError{Code: FCMCodeInvalidToken,
				msg:       "FCM token rejected as invalid (re-register device for a fresh token)",
				Permanent: true, ReRegister: true}
		case "UNAUTHENTICATED", "PERMISSION_DENIED":
			return &FCMError{Code: FCMCodeAuthFailed,
				msg: fmt.Sprintf("FCM rejected credentials: http %d", httpStatus)}
		}
		// Legacy/canonical string codes also surface inside message
		// ("... error: NotRegistered"); match conservatively on the two
		// token-death spellings only.
		upper := strings.ToUpper(parsed.Error.Message)
		if strings.Contains(upper, "NOTREGISTERED") || strings.Contains(upper, "UNREGISTERED") {
			return &FCMError{Code: FCMCodeUnregistered,
				msg:       "FCM token unregistered (re-register device for a fresh token)",
				Permanent: true, ReRegister: true}
		}
		if strings.Contains(upper, "INVALIDREGISTRATION") {
			return &FCMError{Code: FCMCodeInvalidToken,
				msg:       "FCM token rejected as invalid (re-register device for a fresh token)",
				Permanent: true, ReRegister: true}
		}
	}
	if httpStatus == http.StatusNotFound {
		return &FCMError{Code: FCMCodeUnregistered,
			msg:       "FCM token unregistered (re-register device for a fresh token)",
			Permanent: true, ReRegister: true}
	}
	if httpStatus == http.StatusUnauthorized || httpStatus == http.StatusForbidden {
		return &FCMError{Code: FCMCodeAuthFailed,
			msg: fmt.Sprintf("FCM rejected credentials: http %d", httpStatus)}
	}
	return &FCMError{Code: FCMCodeSendFailed,
		msg: fmt.Sprintf("FCM send rejected: http %d", httpStatus)}
}

// shortDeviceID keeps logs metadata-only: the registration token itself
// never appears in logs (it is a bearer-adjacent secret).
func shortDeviceID(regToken string) string {
	if len(regToken) <= 8 {
		return "?"
	}
	return regToken[:4] + "…" + regToken[len(regToken)-4:]
}

// loadServiceAccount reads and minimally validates the service-account JSON.
// Error text names the failure class only — never file contents, paths with
// secrets, or key material.
func loadServiceAccount(path string) (*serviceAccountKey, error) {
	if strings.TrimSpace(path) == "" {
		return nil, &FCMError{Code: FCMCodeMisconfigured,
			msg: "FCM driver unconfigured (need FCM_PROJECT_ID and FCM_CREDENTIALS_FILE)"}
	}
	raw, err := readCredentialsFile(path)
	if err != nil {
		return nil, err
	}
	var sa serviceAccountKey
	if err := json.Unmarshal(raw, &sa); err != nil {
		return nil, &FCMError{Code: FCMCodeMisconfigured, msg: "FCM credentials file is not valid JSON"}
	}
	if sa.Type != "" && sa.Type != "service_account" {
		return nil, &FCMError{Code: FCMCodeMisconfigured, msg: "FCM credentials file is not a service-account key"}
	}
	if strings.TrimSpace(sa.ClientEmail) == "" || strings.TrimSpace(sa.PrivateKey) == "" {
		return nil, &FCMError{Code: FCMCodeMisconfigured,
			msg: "FCM credentials file lacks client_email/private_key"}
	}
	return &sa, nil
}

// readCredentialsFile reads the service-account JSON. Separated for tests:
// error text carries only the failure class, never contents.
func readCredentialsFile(path string) ([]byte, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, &FCMError{Code: FCMCodeMisconfigured, msg: "cannot read FCM credentials file"}
	}
	if len(raw) == 0 || len(raw) > 64*1024 {
		return nil, &FCMError{Code: FCMCodeMisconfigured, msg: "FCM credentials file has an implausible size"}
	}
	return raw, nil
}

// signServiceAccountJWT builds the RS256 JWT bearer grant for the OAuth2
// token exchange: iss=client_email, scope=FCM send, aud=token endpoint,
// 1h expiry. Accepts PKCS#8 ("PRIVATE KEY") and PKCS#1 ("RSA PRIVATE KEY")
// PEM blocks — both shapes the Firebase console emits.
func signServiceAccountJWT(sa *serviceAccountKey, now time.Time) (string, error) {
	key, err := parseServiceAccountKey(sa.PrivateKey)
	if err != nil {
		return "", err
	}
	header := base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"RS256","typ":"JWT"}`))
	claims, err := json.Marshal(map[string]any{
		"iss":   sa.ClientEmail,
		"scope": fcmOAuthScope,
		"aud":   sa.tokenEndpoint(),
		"iat":   now.Unix(),
		"exp":   now.Add(time.Hour).Unix(),
	})
	if err != nil {
		return "", err
	}
	input := header + "." + base64.RawURLEncoding.EncodeToString(claims)
	sum := sha256.Sum256([]byte(input))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, sum[:])
	if err != nil {
		return "", err
	}
	return input + "." + base64.RawURLEncoding.EncodeToString(sig), nil
}

// parseServiceAccountKey decodes the PEM private key (PKCS#8 or PKCS#1).
func parseServiceAccountKey(pemKey string) (*rsa.PrivateKey, error) {
	block, _ := pem.Decode([]byte(pemKey))
	if block == nil {
		return nil, errors.New("notify: no PEM block in service-account private_key")
	}
	if key, err := x509.ParsePKCS8PrivateKey(block.Bytes); err == nil {
		if rsaKey, ok := key.(*rsa.PrivateKey); ok {
			return rsaKey, nil
		}
		return nil, errors.New("notify: service-account key is not RSA")
	}
	if rsaKey, err := x509.ParsePKCS1PrivateKey(block.Bytes); err == nil {
		return rsaKey, nil
	}
	return nil, errors.New("notify: cannot parse service-account private_key (want PKCS#8 or PKCS#1 RSA)")
}

// client returns the configured HTTP client or a default with a 15s timeout.
func (s *FCMSender) client() *http.Client {
	if s.HTTPClient != nil {
		return s.HTTPClient
	}
	return &http.Client{Timeout: 15 * time.Second}
}
