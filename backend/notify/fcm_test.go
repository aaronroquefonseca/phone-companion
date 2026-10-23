// Package notify — FCM HTTPv1 driver tests (Wave-2 push).
//
// No network, no keys: the HTTP layer is a stub RoundTripper and the OAuth2
// mint is stubbed via TokenSource (or exercised against in-memory generated
// RSA keys that never leave the test process).
package notify

import (
	"context"
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// roundTripFunc adapts a func to http.RoundTripper.
type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

// fcmTestSender returns a driver wired to a stub transport: OAuth2 returns
// "fake-access-token", FCM posts go to handle.
func fcmTestSender(handle roundTripFunc) *FCMSender {
	s := NewFCMSender("test-project", "/nonexistent/fcm.json")
	s.TokenSource = func(context.Context) (string, error) { return "fake-access-token", nil }
	s.HTTPClient = &http.Client{Transport: handle}
	return s
}

func fcmOKResponse() *http.Response {
	return &http.Response{
		StatusCode: http.StatusOK,
		Header:     make(http.Header),
		Body:       io.NopCloser(strings.NewReader(`{"name":"projects/test-project/messages/abc"}`)),
	}
}

func fcmErrorResponse(status int, body string) *http.Response {
	h := make(http.Header)
	h.Set("Content-Type", "application/json")
	return &http.Response{StatusCode: status, Header: h, Body: io.NopCloser(strings.NewReader(body))}
}

func fcmErrCode(t *testing.T, err error) string {
	t.Helper()
	var fe *FCMError
	if !errors.As(err, &fe) {
		t.Fatalf("want *FCMError, got %T (%v)", err, err)
	}
	return fe.Code
}

func TestFCMSendSuccessPostsDataOnly(t *testing.T) {
	var sawAuth, sawURL string
	var sawBody fcmSendRequest
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		sawAuth = r.Header.Get("Authorization")
		sawURL = r.URL.String()
		if err := json.NewDecoder(r.Body).Decode(&sawBody); err != nil {
			t.Errorf("decode FCM request: %v", err)
		}
		if r.URL.Query().Get("alt") != "" {
			t.Errorf("unexpected query: %q", r.URL.RawQuery)
		}
		return fcmOKResponse(), nil
	})
	payload := map[string]string{
		"v": "1", "notification_id": "nid-1", "kind": "note",
		"title": "Dinner", "body": "Ready",
	}
	if err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "fake-reg-token-1"}, payload); err != nil {
		t.Fatalf("send: %v", err)
	}
	if sawAuth != "Bearer fake-access-token" {
		t.Fatalf("auth header = %q, want stubbed Bearer token", sawAuth)
	}
	if sawURL != "https://fcm.googleapis.com/v1/projects/test-project/messages:send" {
		t.Fatalf("send URL = %q", sawURL)
	}
	if sawBody.Message.Token != "fake-reg-token-1" {
		t.Fatalf("message.token = %q", sawBody.Message.Token)
	}
	for k, v := range payload {
		if sawBody.Message.Data[k] != v {
			t.Fatalf("data[%q] = %q, want %q", k, sawBody.Message.Data[k], v)
		}
	}
	if len(sawBody.Message.Data) != len(payload) {
		t.Fatalf("data payload mutated in transit: %v", sawBody.Message.Data)
	}
}

func TestFCMSendSecretEnvelopesPassThrough(t *testing.T) {
	var sawBody fcmSendRequest
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		if err := json.NewDecoder(r.Body).Decode(&sawBody); err != nil {
			t.Errorf("decode FCM request: %v", err)
		}
		return fcmOKResponse(), nil
	})
	// A secret fan-out payload: envelopes, no plaintext title/body.
	payload := map[string]string{
		"v": "1", "notification_id": "nid-s", "kind": "note",
		"secret": "true", "title_env": `{"alg":"x"}`, "body_env": `{"alg":"y"}`,
	}
	if err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "fake-reg-token-1"}, payload); err != nil {
		t.Fatalf("send: %v", err)
	}
	if _, ok := sawBody.Message.Data["title"]; ok {
		t.Fatal("secret payload gained plaintext title in transit")
	}
	if _, ok := sawBody.Message.Data["body"]; ok {
		t.Fatal("secret payload gained plaintext body in transit")
	}
	if sawBody.Message.Data["title_env"] != `{"alg":"x"}` || sawBody.Message.Data["secret"] != "true" {
		t.Fatalf("envelopes mutated in transit: %v", sawBody.Message.Data)
	}
	raw, _ := json.Marshal(sawBody)
	if strings.Contains(string(raw), "notification") && strings.Contains(string(raw), `"title"`) {
		t.Fatal("FCM request must be data-only (no notification block)")
	}
}

func TestFCMSendUnregisteredIsReRegisterSignal(t *testing.T) {
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		return fcmErrorResponse(http.StatusNotFound, `{"error":{"code":404,"status":"NOT_FOUND","message":"Requested entity was not found.",
			"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"UNREGISTERED"}]}}`), nil
	})
	err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "dead-token"}, map[string]string{"v": "1"})
	if code := fcmErrCode(t, err); code != FCMCodeUnregistered {
		t.Fatalf("code = %q, want %q", code, FCMCodeUnregistered)
	}
	if !NeedsReRegistration(err) {
		t.Fatal("UNREGISTERED must signal re-registration")
	}
}

func TestFCMSendInvalidTokenIsReRegisterSignal(t *testing.T) {
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		return fcmErrorResponse(http.StatusBadRequest, `{"error":{"code":400,"status":"INVALID_ARGUMENT",
			"message":"The registration token is not a valid FCM registration token",
			"details":[{"@type":"type.googleapis.com/google.firebase.fcm.v1.FcmError","errorCode":"INVALID_ARGUMENT"}]}}`), nil
	})
	err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "bad-token"}, map[string]string{"v": "1"})
	if code := fcmErrCode(t, err); code != FCMCodeInvalidToken {
		t.Fatalf("code = %q, want %q", code, FCMCodeInvalidToken)
	}
	if !NeedsReRegistration(err) {
		t.Fatal("INVALID_ARGUMENT must signal re-registration")
	}
}

func TestFCMSendServerErrorsAreTransient(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
	}{
		{"fcm 500", 500, `{"error":{"code":500,"status":"INTERNAL","message":"Internal error"}}`},
		{"fcm 503", 503, `{"error":{"code":503,"status":"UNAVAILABLE","message":"Overloaded"}}`},
		{"non-JSON 500", 500, `upstream blew up`},
		{"empty 429", 429, ``},
	} {
		t.Run(tc.name, func(t *testing.T) {
			s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
				return fcmErrorResponse(tc.status, tc.body), nil
			})
			err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "tok"}, map[string]string{"v": "1"})
			if code := fcmErrCode(t, err); code != FCMCodeSendFailed {
				t.Fatalf("code = %q, want %q", code, FCMCodeSendFailed)
			}
			if NeedsReRegistration(err) {
				t.Fatal("transient failure must not signal re-registration")
			}
		})
	}
}

func TestFCMSendUnconfiguredFailsClosed(t *testing.T) {
	for _, s := range []*FCMSender{
		NewFCMSender("", ""),
		NewFCMSender("", "/x.json"),
		NewFCMSender("p", ""),
	} {
		err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "tok"}, map[string]string{"v": "1"})
		if code := fcmErrCode(t, err); code != FCMCodeMisconfigured {
			t.Fatalf("code = %q, want %q", code, FCMCodeMisconfigured)
		}
	}
}

func TestFCMSendMissingDeviceTokenIsReRegisterSignal(t *testing.T) {
	calls := 0
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		calls++
		return fcmOKResponse(), nil
	})
	for _, tok := range []string{"", "   "} {
		err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: tok}, map[string]string{"v": "1"})
		if code := fcmErrCode(t, err); code != FCMCodeMissingToken {
			t.Fatalf("token %q: code = %q, want %q", tok, code, FCMCodeMissingToken)
		}
		if !NeedsReRegistration(err) {
			t.Fatal("missing token must signal re-registration (backfill path)")
		}
	}
	if calls != 0 {
		t.Fatalf("missing token must fail before any HTTP call, got %d calls", calls)
	}
}

func TestFCMSendRetriesOnceOnExpiredBearer(t *testing.T) {
	var posts int32
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		if got := r.Header.Get("Authorization"); got != "Bearer fake-access-token" {
			t.Errorf("auth = %q", got)
		}
		if atomic.AddInt32(&posts, 1) == 1 {
			return fcmErrorResponse(http.StatusUnauthorized, `{"error":{"code":401,"status":"UNAUTHENTICATED","message":"bad token"}}`), nil
		}
		return fcmOKResponse(), nil
	})
	if err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: "tok"}, map[string]string{"v": "1"}); err != nil {
		t.Fatalf("retry should recover: %v", err)
	}
	if posts != 2 {
		t.Fatalf("want exactly 2 posts (fail + retry), got %d", posts)
	}
}

func TestFCMSendNeverLogsTokens(t *testing.T) {
	// Error text must stay scrub-safe: no registration token, no bearer,
	// and nothing the history sanitizer would need to strip (the handler
	// sanitizes again, but the driver must already be clean).
	s := fcmTestSender(func(r *http.Request) (*http.Response, error) {
		return fcmErrorResponse(http.StatusNotFound, `{"error":{"code":404,"status":"NOT_FOUND","message":"x",
			"details":[{"@type":"t","errorCode":"UNREGISTERED"}]}}`), nil
	})
	secretToken := "secret-reg-token-xyz"
	err := s.Send(context.Background(), Device{ID: "dev-1", FCMToken: secretToken}, map[string]string{"v": "1"})
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), secretToken) || strings.Contains(err.Error(), "fake-access-token") {
		t.Fatalf("error text leaks token material: %q", err)
	}
	if got := SanitizeError(err.Error()); got == "" {
		t.Fatal("typed error text must survive history sanitization")
	}
}

// generateTestServiceAccount mints an in-memory RSA service-account key for
// JWT tests. The key lives only in the test process — never on disk, never
// in the repo.
func generateTestServiceAccount(t *testing.T) (sa *serviceAccountKey, pemKey string) {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	pemKey = string(pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: der}))
	return &serviceAccountKey{
		Type: "service_account", ProjectID: "test-project",
		PrivateKey: pemKey, ClientEmail: "test@test-project.iam.gserviceaccount.com",
	}, pemKey
}

func TestSignServiceAccountJWTVerifies(t *testing.T) {
	sa, _ := generateTestServiceAccount(t)
	assertion, err := signServiceAccountJWT(sa, time.Now())
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	parts := strings.Split(assertion, ".")
	if len(parts) != 3 {
		t.Fatalf("JWT must have 3 parts, got %d", len(parts))
	}
	claimsRaw, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		t.Fatal(err)
	}
	var claims map[string]any
	if err := json.Unmarshal(claimsRaw, &claims); err != nil {
		t.Fatal(err)
	}
	if claims["iss"] != sa.ClientEmail || claims["scope"] != fcmOAuthScope || claims["aud"] != fcmTokenURI {
		t.Fatalf("bad claims: %v", claims)
	}
}

func TestExchangeAssertion(t *testing.T) {
	sa, _ := generateTestServiceAccount(t)
	assertion, err := signServiceAccountJWT(sa, time.Now())
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	client := &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if r.Header.Get("Content-Type") != "application/x-www-form-urlencoded" {
			t.Errorf("token POST content-type = %q", r.Header.Get("Content-Type"))
		}
		raw, _ := io.ReadAll(r.Body)
		vals, err := url.ParseQuery(string(raw))
		if err != nil {
			t.Errorf("parse token form: %v", err)
		}
		if vals.Get("grant_type") != "urn:ietf:params:oauth:grant-type:jwt-bearer" || vals.Get("assertion") == "" {
			t.Errorf("bad token grant form keys")
		}
		h := make(http.Header)
		h.Set("Content-Type", "application/json")
		return &http.Response{StatusCode: 200, Header: h,
			Body: io.NopCloser(strings.NewReader(`{"access_token":"exchanged-access-token","expires_in":3600,"token_type":"Bearer"}`))}, nil
	})}
	tok, ttl, err := exchangeAssertion(client, context.Background(), sa.tokenEndpoint(), assertion)
	if err != nil {
		t.Fatalf("exchange: %v", err)
	}
	if tok != "exchanged-access-token" {
		t.Fatalf("token = %q", tok)
	}
	if ttl <= 0 {
		t.Fatalf("ttl = %v, want positive", ttl)
	}
}

func TestLoadServiceAccountValidation(t *testing.T) {
	if _, err := loadServiceAccount(""); err == nil {
		t.Fatal("empty path: expected misconfigured error")
	} else if fcmErrCode(t, err); fcmErrCode(t, err) != FCMCodeMisconfigured {
		t.Fatalf("code = %q", fcmErrCode(t, err))
	}
	if _, err := loadServiceAccount("/nonexistent/fcm.json"); err == nil {
		t.Fatal("missing file: expected error")
	}
}
