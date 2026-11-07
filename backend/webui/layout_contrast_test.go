package webui

// Contrast regression test for sharedCSS (layout.go). The fg/bg pairs below
// mirror the palette in sharedCSS; if a color there changes, update the pair
// here too. Text pairs must reach WCAG AA 4.5:1, UI-border pairs 3:1.

import (
	"math"
	"strings"
	"testing"
)

// parseHex parses a #rgb or #rrggbb color into linear sRGB components.
func parseHex(t *testing.T, hex string) (r, g, b float64) {
	t.Helper()
	hex = strings.TrimPrefix(hex, "#")
	if len(hex) == 3 {
		hex = string([]byte{hex[0], hex[0], hex[1], hex[1], hex[2], hex[2]})
	}
	if len(hex) != 6 {
		t.Fatalf("bad hex color %q", hex)
	}
	var v [3]float64
	for i := 0; i < 3; i++ {
		var n int
		for _, c := range hex[2*i : 2*i+2] {
			n <<= 4
			switch {
			case c >= '0' && c <= '9':
				n |= int(c - '0')
			case c >= 'a' && c <= 'f':
				n |= int(c-'a') + 10
			case c >= 'A' && c <= 'F':
				n |= int(c-'A') + 10
			default:
				t.Fatalf("bad hex color %q", hex)
			}
		}
		v[i] = float64(n) / 255
	}
	return v[0], v[1], v[2]
}

func luminance(r, g, b float64) float64 {
	lin := func(c float64) float64 {
		if c <= 0.03928 {
			return c / 12.92
		}
		return math.Pow((c+0.055)/1.055, 2.4)
	}
	return 0.2126*lin(r) + 0.7152*lin(g) + 0.0722*lin(b)
}

// contrastRatio returns the WCAG contrast ratio of fg on bg.
func contrastRatio(t *testing.T, fg, bg string) float64 {
	t.Helper()
	fr, fgCh, fb := parseHex(t, fg)
	br, bgCh, bb := parseHex(t, bg)
	l1 := luminance(fr, fgCh, fb)
	l2 := luminance(br, bgCh, bb)
	if l1 < l2 {
		l1, l2 = l2, l1
	}
	return (l1 + 0.05) / (l2 + 0.05)
}

func TestSharedCSSContrast(t *testing.T) {
	// Pairs reference sharedCSS in layout.go: "name" is only a label.
	textPairs := []struct {
		name, fg, bg string
	}{
		// Light mode.
		{"light body text", "#1a1a1a", "#fff"},
		{"light primary button text", "#fff", "#1a1a1a"},
		{"light card btn text", "#fff", "#0b5fff"},
		{"light notice text", "#1a1a1a", "#f6f6f6"},
		{"light error text", "#7a1f1a", "#fdecea"},
		{"light success text", "#0d5c22", "#e6f4ea"},
		// Dark mode.
		{"dark body text", "#e8e8e8", "#121212"},
		{"dark secondary button text", "#e8e8e8", "#121212"},
		{"dark error text", "#ffdad6", "#3b1215"},
		{"dark success text", "#a8e6b3", "#0f2e1a"},
		{"dark notice text", "#e8e8e8", "#1e1e1e"},
	}
	for _, p := range textPairs {
		if r := contrastRatio(t, p.fg, p.bg); r < 4.5 {
			t.Errorf("%s: %s on %s = %.2f:1, want >= 4.5:1", p.name, p.fg, p.bg, r)
		}
	}

	borderPairs := []struct {
		name, fg, bg string
	}{
		// Light mode (page bg #fff).
		{"light card border", "#767676", "#fff"},
		{"light table border", "#767676", "#fff"},
		{"light notice border", "#767676", "#f6f6f6"},
		// Dark mode (page bg #121212).
		{"dark topbar border", "#8c8c8c", "#121212"},
		{"dark footer border", "#8c8c8c", "#121212"},
		{"dark notice border", "#8c8c8c", "#1e1e1e"},
	}
	for _, p := range borderPairs {
		if r := contrastRatio(t, p.fg, p.bg); r < 3 {
			t.Errorf("%s: %s on %s = %.2f:1, want >= 3:1", p.name, p.fg, p.bg, r)
		}
	}
}

// TestSharedCSSContrastUsesCurrentPalette guards against the hardcoded pairs
// above drifting from sharedCSS: every hex color asserted on must still be
// present in the stylesheet.
func TestSharedCSSContrastUsesCurrentPalette(t *testing.T) {
	for _, c := range []string{
		"#1a1a1a", "#fff", "#0b5fff", "#f6f6f6", "#767676",
		"#7a1f1a", "#fdecea", "#0d5c22", "#e6f4ea",
		"#e8e8e8", "#121212", "#1e1e1e", "#8c8c8c",
		"#ffdad6", "#3b1215", "#a8e6b3", "#0f2e1a",
	} {
		if !strings.Contains(sharedCSS, c) {
			t.Errorf("sharedCSS no longer contains %s; update the contrast pairs", c)
		}
	}
}

// TestSharedCSSDarkBlockLast guards the cascade order the ratio tests above
// cannot see: at equal specificity the LATER rule wins, so every light-mode
// base rule the dark theme overrides must be defined BEFORE the dark
// @media block. A base color rule appended after it silently wins in dark
// mode (this once shipped white-on-white table headers). If this fails,
// move the dark block back to the end of sharedCSS instead of weakening it.
func TestSharedCSSDarkBlockLast(t *testing.T) {
	const marker = "@media (prefers-color-scheme:dark){"
	darkIdx := strings.Index(sharedCSS, marker)
	if darkIdx < 0 {
		t.Fatal("dark @media block missing from sharedCSS")
	}
	// Exact light-mode base declarations the dark block overrides. Missing
	// entry = stylesheet changed: update this list, do not delete it.
	base := []string{
		`body{font-family:`,
		`.brand a{font-weight:700`,
		`nav a{color:#0b5fff`,
		`main a{color:#0b5fff}`,
		`button{font:inherit`,
		`.field .hint{margin:.25rem 0 0;font-size:.875rem;color:#555}`,
		`.field-error{margin:.25rem 0 0;font-size:.875rem;color:#b3261e}`,
		`.notice{background:#f6f6f6`,
		`.error{background:#fdecea`,
		`.success{background:#e6f4ea`,
		`.card p{margin:.3rem 0 1rem;color:#333}`,
		`th{background:#f0f0f0}`,
		`.revoked{color:#666}`,
		`.muted{color:#555}`,
		`footer{margin-top:2.5rem`,
	}
	for _, sel := range base {
		idx := strings.Index(sharedCSS, sel)
		if idx < 0 {
			t.Errorf("base rule %q missing; stylesheet changed, update this test", sel)
			continue
		}
		if idx > darkIdx {
			t.Errorf("base rule %q sits AFTER the dark @media block and wins in dark mode; move the dark block last", sel)
		}
	}
}
