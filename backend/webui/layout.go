// Package webui — shared operator layout: one inline stylesheet and one
// header/nav shell reused by every browser page (login, dashboard, pairing,
// widgets preview, notify form).
//
// Plain stdlib HTML only — no CSS/JS framework, no external assets. The
// stylesheet is hand-written minimal CSS (system font stack, readable
// contrast) inlined in <style> so pages render with zero extra requests.
package webui

import (
	"html/template"
	"net/http"
	"strings"
)

// sharedCSS is the single stylesheet for every WebUI page. Inline
// <style> only: no external assets, no frameworks.
//
// Form rhythm (8px scale): .field blocks stack with 16px (2×8px) gaps,
// label-to-control is 4px, hints/field errors sit 4px under the control,
// and .form-actions carries 24px (3×8px) top margin so buttons sit
// left-aligned below the fields — never inline with inputs. All text
// controls share one max-width (min(100%, 28rem)) with identical
// padding/border so their left/right edges align vertically.
const sharedCSS = `*{box-sizing:border-box}
body{font-family:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;max-width:52rem;margin:0 auto;padding:0 1rem 3rem;line-height:1.5;color:#1a1a1a;background:#fff}
.topbar{display:flex;flex-wrap:wrap;align-items:center;gap:.5rem 1.2rem;border-bottom:2px solid #1a1a1a;padding:.9rem 0;margin-bottom:1.4rem}
.brand a{font-weight:700;font-size:1.15rem;color:#1a1a1a;text-decoration:none}
nav{display:flex;flex-wrap:wrap;align-items:center;gap:.4rem .9rem}
nav a{color:#0b5fff;text-decoration:none;padding:.15rem .1rem}
nav a[aria-current="page"]{font-weight:700;text-decoration:underline}
nav a:hover{text-decoration:underline}
.logout{margin:0 0 0 auto}
button{font:inherit;padding:.35rem .8rem;border:1px solid #1a1a1a;border-radius:.35rem;background:#1a1a1a;color:#fff;cursor:pointer}
button:hover{background:#333}
button.secondary{background:#fff;color:#1a1a1a}
button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline:3px solid #0b5fff;outline-offset:2px}
form{margin:0}
input[type="hidden"]{display:none!important}
.field{margin:0 0 1rem;max-width:min(100%,28rem)}
.field label{display:block;margin:0 0 .25rem;font-weight:600}
.field input[type="text"],.field input[type="password"],.field select,.field textarea{display:block;width:100%;max-width:100%;min-height:2.5rem;padding:.5rem .6rem;border:1px solid #666;border-radius:.35rem;font:inherit;line-height:1.4;background:#fff;color:#1a1a1a}
.field textarea{min-height:6rem;resize:vertical}
.field input[type="checkbox"]{display:block;width:1.1rem;height:1.1rem;margin:.25rem 0 0;min-height:0;padding:0}
.field .hint{margin:.25rem 0 0;font-size:.875rem;color:#555}
.field-error{margin:.25rem 0 0;font-size:.875rem;color:#b3261e}
.field input[aria-invalid="true"],.field select[aria-invalid="true"],.field textarea[aria-invalid="true"]{border:2px solid #b3261e}
.form-actions{margin-top:1.5rem}
@media (prefers-color-scheme:dark){body{color:#e8e8e8;background:#121212}.brand a{color:#e8e8e8}.topbar{border-color:#555}nav a{color:#8ab4ff}button:focus-visible,a:focus-visible,input:focus-visible,select:focus-visible,textarea:focus-visible{outline-color:#8ab4ff}.field input[type="text"],.field input[type="password"],.field select,.field textarea{background:#1e1e1e;color:#e8e8e8;border-color:#999}.field .hint{color:#b0b0b0}.field-error{color:#ffb4ab}.field input[aria-invalid="true"],.field select[aria-invalid="true"],.field textarea[aria-invalid="true"]{border-color:#ffb4ab}.error{background:#3b1215;border-color:#ffb4ab}.notice{background:#1e1e1e;border-color:#555}.success{background:#0f2e1a;border-color:#7bd88f}.muted{color:#b0b0b0}th{background:#1e1e1e}.card p{color:#c9c9c9}footer{border-color:#555}}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(14rem,1fr));gap:1rem;margin:1rem 0}
.card{border:1px solid #999;border-radius:.5rem;padding:1rem}
.card h2{margin:0 0 .3rem;font-size:1.05rem}
.card p{margin:.3rem 0 1rem;color:#333}
.card .count{font-size:1.8rem;font-weight:700}
.card a.btn{display:inline-block;background:#0b5fff;color:#fff;text-decoration:none;padding:.4rem .9rem;border-radius:.35rem}
table{border-collapse:collapse;width:100%;margin:1rem 0}
th,td{border:1px solid #999;padding:.4rem .6rem;text-align:left;vertical-align:top;word-break:break-word}
th{background:#f0f0f0}
.revoked{color:#666}
tr.revoked{opacity:.62}
@media (max-width:40rem){
table{display:block;max-width:100%;overflow-x:auto;-webkit-overflow-scrolling:touch}
code{word-break:break-all}
.card{overflow-wrap:anywhere}
input,select,textarea{max-width:100%}
}
code{word-break:break-all}
.notice{background:#f6f6f6;border:1px solid #999;padding:.6rem .9rem;border-radius:.4rem}
.error{background:#fdecea;border:1px solid #b3261e;padding:.6rem .9rem;border-radius:.4rem}
.success{background:#e6f4ea;border:1px solid #1e7e34;padding:.6rem .9rem;border-radius:.4rem}
.muted{color:#555}
.masked{letter-spacing:.2em;font-weight:700}
footer{margin-top:2.5rem;color:#555;font-size:.85rem;border-top:1px solid #ccc;padding-top:.8rem}`

// layoutData drives the shared shell. SignedIn gates the operator nav (the
// anonymous login page renders the brand + body only). CSRFToken feeds the
// nav logout form and the csrf-token meta fallback that older tooling reads.
type layoutData struct {
	Title     string
	Active    string // home|pairing|widgets|notify|login — marks aria-current
	SignedIn  bool
	CSRFToken string
	Body      template.HTML
}

// layoutTmpl is the shared shell: header/nav + {{.Body}}. Page bodies are
// rendered by per-page templates into trusted HTML (server-built from
// validated data, never from raw user input) and injected as template.HTML.
var layoutTmpl = template.Must(template.New("layout").Parse(`<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{{.Title}} — Phone Companion</title>
{{if .CSRFToken}}<meta name="csrf-token" content="{{.CSRFToken}}">{{end}}
<style>` + sharedCSS + `</style>
</head>
<body>
<header class="topbar">
{{if .SignedIn}}<div class="brand"><a href="/">Phone Companion</a></div>{{else}}<div class="brand"><a href="/login">Phone Companion</a></div>{{end}}
{{if .SignedIn}}
<nav aria-label="Operator">
<a href="/"{{if eq .Active "home"}} aria-current="page"{{end}}>Home</a>
<a href="/webui/pair"{{if eq .Active "pairing"}} aria-current="page"{{end}}>Pairing</a>
<a href="/webui/widgets"{{if eq .Active "widgets"}} aria-current="page"{{end}}>Widgets</a>
<a href="/webui/notify"{{if eq .Active "notify"}} aria-current="page"{{end}}>Notify</a>
<form class="logout" method="post" action="/logout"><input type="hidden" name="csrf_token" value="{{.CSRFToken}}"><button type="submit">Sign out</button></form>
</nav>
{{end}}
</header>
<main>
{{.Body}}
</main>
<footer class="muted">Phone Companion operator console</footer>
</body>
</html>`))

// renderLayout writes a full page inside the shared shell. Every operator
// page is credential-bearing (session cookie) or embeds single-use tokens,
// so Cache-Control is always no-store — same pin the login/pairing pages
// already carried before the layout existed.
func renderLayout(w http.ResponseWriter, status int, data layoutData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_ = layoutTmpl.Execute(w, data)
}

// layoutCSRFToken mints the session-bound token for the nav logout form.
// TokenForRequest is nil-safe: a nil/fail-closed protector yields "", and
// the logout POST then fails closed with 403.
func layoutCSRFToken(csrf *CSRFProtection, r *http.Request) string {
	if tok, ok := csrf.TokenForRequest(r); ok {
		return tok
	}
	return ""
}

// errorBody is the shared operator error page: heading + message + onward
// link inside the normal shell (nav/chrome preserved), so no operator path
// ever answers with a bare-text http.Error. Message is operator-safe text
// assembled by the caller (whitelisted codes or static copy) — never raw
// user input or secret material.
var errorBody = template.Must(template.New("errorBody").Parse(`<h1>{{.Heading}}</h1>
<p class="error" role="alert">{{.Message}}</p>
<p><a href="{{.OnwardHref}}">{{.OnwardText}}</a> · <a href="/">Back to dashboard</a></p>`))

type errorPageData struct {
	Heading    string
	Message    string
	OnwardHref string
	OnwardText string
}

// renderOperatorError writes a layout-wrapped error page (nav/chrome kept,
// no-store) for operator-surface failures that historically used bare
// http.Error: login 429/400, CSRF 403s, pair 429/400/403/500s, unknown
// authed paths (404), GET /logout (405). signedIn controls the nav shell;
// onward points at the page the operator should retry from.
func renderOperatorError(w http.ResponseWriter, r *http.Request, csrf *CSRFProtection, signedIn bool, active string, status int, heading, message, onwardHref, onwardText string) {
	if onwardHref == "" {
		onwardHref = "/"
	}
	if onwardText == "" {
		onwardText = "Back to dashboard"
	}
	var body strings.Builder
	_ = errorBody.Execute(&body, errorPageData{
		Heading:    heading,
		Message:    message,
		OnwardHref: onwardHref,
		OnwardText: onwardText,
	})
	renderLayout(w, status, layoutData{
		Title:     heading,
		Active:    active,
		SignedIn:  signedIn,
		CSRFToken: layoutCSRFToken(csrf, r),
		Body:      template.HTML(body.String()),
	})
}

// WriteOperatorError is the exported hook for main-package wiring (e.g. the
// enroll-issuance limiter on GET /webui/pair) so limiter/gate failures on
// operator pages render inside the shared shell too. signedIn should
// reflect the validated session; callers behind RequireAdmin pass true.
func WriteOperatorError(w http.ResponseWriter, r *http.Request, csrf *CSRFProtection, signedIn bool, active string, status int, heading, message, onwardHref, onwardText string) {
	renderOperatorError(w, r, csrf, signedIn, active, status, heading, message, onwardHref, onwardText)
}
