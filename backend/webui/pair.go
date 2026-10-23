package webui

import (
	"encoding/base64"
	"encoding/json"
	"html/template"
	"net/http"
	"strings"
	"time"
)

// enrollPayload is the QR contents scanned by the phone: where to POST and
// the single-use token authorizing that POST. The keys enroll_url +
// enroll_token are canonical — the Android app (P6 PairingQr) parses them —
// and must not be renamed.
type enrollPayload struct {
	EnrollURL   string `json:"enroll_url"`
	EnrollToken string `json:"enroll_token"`
}

// ExternalBaseURL is the configured public origin (EXTERNAL_BASE_URL,
// e.g. "https://example.com") used to build enroll_url. Set once at boot
// from Config. When empty the builder falls back to the request Host +
// X-Forwarded-Proto, which is dev-only: Host headers are
// client-controlled and spoofable behind a misconfigured proxy, so
// production must set EXTERNAL_BASE_URL (see docs/threat-model.md).
var ExternalBaseURL string

// EnrollPayloadForRequest builds the canonical QR payload
// {"enroll_url":..., "enroll_token":...}. With ExternalBaseURL set the URL
// is ExternalBaseURL + "/api/v1/enroll"; otherwise it reflects the request
// (X-Forwarded-Proto, then TLS state) and Host — dev-only fallback.
func EnrollPayloadForRequest(r *http.Request, rawToken string) (string, error) {
	base := strings.TrimSuffix(strings.TrimSpace(ExternalBaseURL), "/")
	if base == "" {
		scheme := r.Header.Get("X-Forwarded-Proto")
		if scheme == "" {
			if r.TLS != nil {
				scheme = "https"
			} else {
				scheme = "http"
			}
		}
		host := r.Host
		if host == "" {
			host = "localhost"
		}
		base = scheme + "://" + host
	}
	raw, err := json.Marshal(enrollPayload{
		EnrollURL:   base + "/api/v1/enroll",
		EnrollToken: rawToken,
	})
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

var pairBodyTmpl = template.Must(template.New("pairBody").Parse(`<h1>Pair a device</h1>
{{if .Notice}}<p class="error">{{.Notice}}</p>{{end}}
{{if .QRDataURI}}
<div class="notice">
<p>Scan with the Phone Companion app to enroll. Single-use, expires {{.ExpiresAt}} (UTC).</p>
<img src="{{.QRDataURI}}" alt="Pairing QR code" width="256" height="256">
<p>Token (copy manually if needed):<br><code>{{.RawToken}}</code></p>
<p><a href="/webui/pair">Issue a fresh token</a></p>
</div>
{{else}}
<p class="notice">Could not issue a pairing token: {{.Error}}</p>
{{end}}
<h2>Devices</h2>
{{if .Devices}}
<table>
<tr><th>Name</th><th>ID</th><th>Paired</th><th>Status</th><th></th></tr>
{{range .Devices}}
<tr{{if .Revoked}} class="revoked"{{end}}>
<td>{{.Name}}</td>
<td><code>{{.ID}}</code></td>
<td>{{.CreatedAt}}</td>
<td>{{if .Revoked}}revoked{{else}}active{{end}}</td>
<td>{{if .Revoked}}{{else}}<form method="POST" action="/webui/pair/revoke"><input type="hidden" name="device_id" value="{{.ID}}"><input type="hidden" name="csrf_token" value="{{$.CSRFToken}}"><button type="submit">Revoke</button></form>{{end}}</td>
</tr>
{{end}}
</table>
{{else}}
<p>No devices paired yet.</p>
{{end}}`))

type pairPageData struct {
	// QRDataURI is a server-generated PNG data URI (template.URL: the
	// bytes come from our own QRPNG encoder, never from user input).
	QRDataURI template.URL
	RawToken  string
	ExpiresAt string
	Devices   []DeviceInfo
	Error     string
	// Notice is a one-shot operator message (e.g. unknown revoke ID),
	// sourced only from a whitelisted ?error= value — never raw query.
	Notice string
	// CSRFToken is the session-bound token embedded in each revoke form.
	CSRFToken string
}

// revokeNotice maps whitelisted ?error= values to operator text. Anything
// else renders no notice, so query input can never inject page content.
func revokeNotice(v string) string {
	switch v {
	case "device_not_found":
		return "Device not found — it may already have been revoked or the ID is stale."
	case "csrf":
		return "Security token mismatch — please try again."
	default:
		return ""
	}
}

// renderPairPage executes the body template inside the shared layout on
// every path (including errors), so browsers never sniff a missing type.
// Cache-Control is no-store throughout: the page embeds a single-use enroll
// token and the operator device registry — both must never sit in a cache.
func renderPairPage(w http.ResponseWriter, status int, data pairPageData) {
	var body strings.Builder
	_ = pairBodyTmpl.Execute(&body, data)
	renderLayout(w, status, layoutData{
		Title:     "Pair a device",
		Active:    "pairing",
		SignedIn:  true,
		CSRFToken: data.CSRFToken,
		Body:      template.HTML(body.String()),
	})
}

// HandlePairPage serves GET /webui/pair: issues a fresh single-use enroll
// token (TTL supplied by the caller so there is exactly one TTL constant —
// main.EnrollTokenTTL), renders it as a QR PNG (embedded data URI, no
// extra endpoint, no token in URLs/logs), and lists devices with
// CSRF-guarded revoke buttons.
func HandlePairPage(store Store, ttl time.Duration, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		data := pairPageData{Notice: revokeNotice(r.URL.Query().Get("error"))}
		if store == nil {
			data.Error = "internal error"
			renderPairPage(w, http.StatusInternalServerError, data)
			return
		}
		if devices, err := store.ListDevices(r.Context()); err == nil {
			data.Devices = devices
		}
		if token, ok := csrfTokenForPage(csrf, r); ok {
			data.CSRFToken = token
		}
		raw, exp, err := store.IssueEnrollToken(r.Context(), ttl)
		if err != nil {
			data.Error = "internal error"
			renderPairPage(w, http.StatusInternalServerError, data)
			return
		}
		payload, err := EnrollPayloadForRequest(r, raw)
		if err != nil {
			data.Error = "internal error"
			renderPairPage(w, http.StatusInternalServerError, data)
			return
		}
		png, err := QRPNG(payload)
		if err != nil {
			data.Error = "internal error"
			renderPairPage(w, http.StatusInternalServerError, data)
			return
		}
		data.QRDataURI = template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(png))
		data.RawToken = raw
		data.ExpiresAt = exp.UTC().Format(time.RFC3339)
		renderPairPage(w, http.StatusOK, data)
	}
}

func csrfTokenForPage(csrf *CSRFProtection, r *http.Request) (string, bool) {
	if csrf == nil {
		return "", false
	}
	return csrf.TokenForRequest(r)
}

// HandlePairRevoke serves POST /webui/pair/revoke (HTML form from the
// revoke buttons) and redirects back to the pairing page. The form must
// carry the session-bound CSRF token; mismatches fail closed with 403.
// Unknown IDs redirect with ?error=device_not_found so the operator sees
// what happened instead of a silent no-op.
func HandlePairRevoke(store Store, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Revocation changes operator state; never cache the outcome.
		w.Header().Set("Cache-Control", "no-store")
		if store == nil {
			http.Error(w, "internal", http.StatusInternalServerError)
			return
		}
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid_request", http.StatusBadRequest)
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			http.Error(w, "csrf_mismatch", http.StatusForbidden)
			return
		}
		id := strings.TrimSpace(r.FormValue("device_id"))
		if id == "" {
			http.Error(w, "invalid_request", http.StatusBadRequest)
			return
		}
		found, err := store.RevokeDevice(r.Context(), id)
		if err != nil {
			http.Error(w, "internal", http.StatusInternalServerError)
			return
		}
		if !found {
			http.Redirect(w, r, "/webui/pair?error=device_not_found", http.StatusSeeOther)
			return
		}
		http.Redirect(w, r, "/webui/pair", http.StatusSeeOther)
	}
}
