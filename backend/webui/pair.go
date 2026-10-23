package webui

import (
	"encoding/base64"
	"encoding/json"
	"html/template"
	"net/http"
	"strings"
	"time"
)

// PairTokenTTL matches the enroll-token lifetime used by the pairing API.
const PairTokenTTL = 15 * time.Minute

// enrollPayload is the QR contents scanned by the phone: where to POST and
// the single-use token authorizing that POST.
type enrollPayload struct {
	EnrollURL   string `json:"enroll_url"`
	EnrollToken string `json:"enroll_token"`
}

// EnrollPayloadForRequest builds the QR payload from the incoming request's
// host/scheme so the same image works behind a reverse proxy + TLS. It
// honors X-Forwarded-Proto when present and otherwise reflects the
// connection (https for TLS, http for direct plain-HTTP access).
func EnrollPayloadForRequest(r *http.Request, rawToken string) (string, error) {
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
	raw, err := json.Marshal(enrollPayload{
		EnrollURL:   scheme + "://" + host + "/api/v1/enroll",
		EnrollToken: rawToken,
	})
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

var pairPageTmpl = template.Must(template.New("pair").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Pair a device — Phone Companion</title>
<style>
body{font-family:system-ui,sans-serif;max-width:46rem;margin:2rem auto;padding:0 1rem;line-height:1.5}
table{border-collapse:collapse;width:100%}
th,td{border:1px solid #ccc;padding:.4rem .6rem;text-align:left}
.revoked{color:#777}
code{word-break:break-all}
.notice{background:#f6f6f6;border:1px solid #ddd;padding:.6rem .9rem;border-radius:.4rem}
</style>
</head>
<body>
<h1>Pair a device</h1>
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
<td>{{if .Revoked}}{{else}}<form method="POST" action="/webui/pair/revoke"><input type="hidden" name="device_id" value="{{.ID}}"><button type="submit">Revoke</button></form>{{end}}</td>
</tr>
{{end}}
</table>
{{else}}
<p>No devices paired yet.</p>
{{end}}
</body>
</html>`))

type pairPageData struct {
	// QRDataURI is a server-generated PNG data URI (template.URL: the
	// bytes come from our own QRPNG encoder, never from user input).
	QRDataURI template.URL
	RawToken  string
	ExpiresAt string
	Devices   []DeviceInfo
	Error     string
}

// HandlePairPage serves GET /webui/pair: issues a fresh single-use enroll
// token, renders it as a QR PNG (embedded data URI, no extra endpoint, no
// token in URLs/logs), and lists devices with revoke buttons.
func HandlePairPage(store Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		data := pairPageData{}
		if devices, err := store.ListDevices(r.Context()); err == nil {
			data.Devices = devices
		}
		raw, exp, err := store.IssueEnrollToken(r.Context(), PairTokenTTL)
		if err != nil {
			data.Error = "internal error"
			w.WriteHeader(http.StatusInternalServerError)
			_ = pairPageTmpl.Execute(w, data)
			return
		}
		payload, err := EnrollPayloadForRequest(r, raw)
		if err != nil {
			data.Error = "internal error"
			w.WriteHeader(http.StatusInternalServerError)
			_ = pairPageTmpl.Execute(w, data)
			return
		}
		png, err := QRPNG(payload)
		if err != nil {
			data.Error = "internal error"
			w.WriteHeader(http.StatusInternalServerError)
			_ = pairPageTmpl.Execute(w, data)
			return
		}
		data.QRDataURI = template.URL("data:image/png;base64," + base64.StdEncoding.EncodeToString(png))
		data.RawToken = raw
		data.ExpiresAt = exp.UTC().Format(time.RFC3339)
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = pairPageTmpl.Execute(w, data)
	}
}

// HandlePairRevoke serves POST /webui/pair/revoke (HTML form from the
// revoke buttons) and redirects back to the pairing page.
func HandlePairRevoke(store Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if err := r.ParseForm(); err != nil {
			http.Error(w, "invalid_request", http.StatusBadRequest)
			return
		}
		id := strings.TrimSpace(r.FormValue("device_id"))
		if id == "" {
			http.Error(w, "invalid_request", http.StatusBadRequest)
			return
		}
		if _, err := store.RevokeDevice(r.Context(), id); err != nil {
			http.Error(w, "internal", http.StatusInternalServerError)
			return
		}
		http.Redirect(w, r, "/webui/pair", http.StatusSeeOther)
	}
}
