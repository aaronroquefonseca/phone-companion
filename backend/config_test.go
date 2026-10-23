package main

import (
	"strings"
	"testing"
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
