package main

import (
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func validTestConfig() Config {
	return Config{
		AdminUser:     "admin",
		AdminPassword: "bootstrap-only-pw",
		SessionSecret: strings.Repeat("s", 32),
	}
}

func TestConfigValidateAcceptsPasswordBootstrap(t *testing.T) {
	if err := validTestConfig().Validate(); err != nil {
		t.Fatalf("expected valid config, got %v", err)
	}
}

func TestConfigValidateAcceptsHashOnly(t *testing.T) {
	c := validTestConfig()
	c.AdminPassword = ""
	c.AdminPasswordHash = "$2a$10$" + strings.Repeat("a", 53)
	if err := c.Validate(); err != nil {
		t.Fatalf("expected valid config, got %v", err)
	}
}

func TestConfigValidateRejects(t *testing.T) {
	cases := map[string]func(*Config){
		"missing secret":   func(c *Config) { c.SessionSecret = "" },
		"short secret":     func(c *Config) { c.SessionSecret = "too-short" },
		"placeholder sec":  func(c *Config) { c.SessionSecret = "CHANGEME" + strings.Repeat("x", 40) },
		"missing user":     func(c *Config) { c.AdminUser = "" },
		"placeholder user": func(c *Config) { c.AdminUser = "CHANGEME-admin" },
		"no credential":    func(c *Config) { c.AdminPassword, c.AdminPasswordHash = "", "" },
		"weak password":    func(c *Config) { c.AdminPassword = "short" },
		"short hash":       func(c *Config) { c.AdminPasswordHash, c.AdminPassword = "abc", "" },
	}
	for name, mutate := range cases {
		c := validTestConfig()
		mutate(&c)
		if err := c.Validate(); err == nil {
			t.Errorf("%s: expected rejection, got nil", name)
		}
	}
}

func TestConfigValidateErrorsLeakNoSecrets(t *testing.T) {
	c := validTestConfig()
	c.SessionSecret = "short"
	err := c.Validate()
	if err == nil {
		t.Fatal("expected error")
	}
	if strings.Contains(err.Error(), "short") {
		t.Errorf("error message echoes secret material: %q", err)
	}
}

func TestConfigExternalBaseURLHardening(t *testing.T) {
	c := validTestConfig()

	// https anywhere stays allowed.
	c.ExternalBaseURL = "https://example.com"
	if err := c.Validate(); err != nil {
		t.Errorf("https origin: unexpected error %v", err)
	}

	// Loopback http stays allowed for dev (no override needed).
	for _, loopback := range []string{
		"http://localhost:8080",
		"http://localhost",
		"http://127.0.0.1:8080",
		"http://[::1]:8080",
	} {
		c.ExternalBaseURL = loopback
		if err := c.Validate(); err != nil {
			t.Errorf("loopback %s: unexpected error %v", loopback, err)
		}
	}

	// Reject userinfo, opaque URLs, and paths/query/fragments.
	for _, bad := range []string{
		"https://user@example.com",
		"https://user:pass@example.com",
		"http://localhost/enroll",
		"https://example.com?x=1",
	} {
		c.ExternalBaseURL = bad
		if err := c.Validate(); err == nil {
			t.Errorf("base %q: expected rejection", bad)
		}
	}

	// Plain http to a non-loopback host is fail-closed by default ...
	c.ExternalBaseURL = "http://192.168.1.10:8080"
	c.ExternalBaseURLAllowHTTP = false
	if err := c.Validate(); err == nil {
		t.Error("non-loopback http without override: expected rejection")
	} else if !strings.Contains(err.Error(), "EXTERNAL_BASE_URL_ALLOW_HTTP") {
		t.Errorf("rejection should name the override flag, got %q", err)
	}

	// ... and allowed only with the explicit dev override.
	c.ExternalBaseURLAllowHTTP = true
	if err := c.Validate(); err != nil {
		t.Errorf("non-loopback http with override: unexpected error %v", err)
	}
}

func TestAllowHTTPExbaseParsing(t *testing.T) {
	for _, v := range []string{"1", "true", "TRUE", "yes", " Yes "} {
		if !allowHTTPExbase(v) {
			t.Errorf("allowHTTPExbase(%q) = false, want true", v)
		}
	}
	for _, v := range []string{"", "0", "false", "no", "2"} {
		if allowHTTPExbase(v) {
			t.Errorf("allowHTTPExbase(%q) = true, want false", v)
		}
	}
}

// The insecure-cookie escape hatch stays fail-closed by default and opts in
// only on explicit values — same shape as the EXTERNAL_BASE_URL override.
func TestAllowInsecureCookiesParsing(t *testing.T) {
	for _, v := range []string{"1", "true", "TRUE", "yes", " Yes "} {
		if !allowInsecureCookies(v) {
			t.Errorf("allowInsecureCookies(%q) = false, want true", v)
		}
	}
	for _, v := range []string{"", "0", "false", "no", "2"} {
		if allowInsecureCookies(v) {
			t.Errorf("allowInsecureCookies(%q) = true, want false", v)
		}
	}
	c := validTestConfig()
	if err := c.Validate(); err != nil {
		t.Fatalf("insecure cookies unset: unexpected error %v", err)
	}
	c.AllowInsecureCookies = true
	if err := c.Validate(); err != nil {
		t.Fatalf("insecure cookies opt-in: unexpected error %v (warn-only, never reject)", err)
	}
}

// The Secure flag stays on by default and drops only under the explicit
// escape hatch (restored after the test so other tests keep the default).
func TestSessionCookieSecureFlag(t *testing.T) {
	SetAllowInsecureSessionCookie(false)
	t.Cleanup(func() { SetAllowInsecureSessionCookie(false) })

	secure := func() bool {
		w := httptest.NewRecorder()
		setSessionCookie(w, "v", time.Now().Add(time.Hour))
		for _, c := range w.Result().Cookies() {
			if c.Name == SessionCookieName {
				return c.Secure
			}
		}
		t.Fatal("session cookie not set")
		return false
	}
	if !secure() {
		t.Error("default session cookie must be Secure")
	}
	SetAllowInsecureSessionCookie(true)
	if secure() {
		t.Error("escape hatch must drop Secure for plain-HTTP dev")
	}
}
