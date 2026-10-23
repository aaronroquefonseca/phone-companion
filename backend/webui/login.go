package webui

import (
	"html/template"
	"net/http"
)

// loginPage is the whole WebUI auth surface: sign-in form, signed-in
// state, and failure message. No framework, no external assets.
var loginPage = template.Must(template.New("login").Parse(`<!DOCTYPE html>
<html lang="en">
<head><meta charset="utf-8"><title>Phone Companion — Sign in</title></head>
<body>
<main>
<h1>Phone Companion</h1>
{{if .SignedIn}}
<p>Signed in as {{.Username}}.</p>
<form method="post" action="/logout"><button type="submit">Sign out</button></form>
{{else}}
{{if .Error}}<p role="alert">Invalid username or password.</p>{{end}}
<form method="post" action="/login">
<label>Username <input type="text" name="username" autocomplete="username" required></label><br>
<label>Password <input type="password" name="password" autocomplete="current-password" required></label><br>
<button type="submit">Sign in</button>
</form>
{{end}}
</main>
</body>
</html>`))

type loginData struct {
	Error    bool
	SignedIn bool
	Username string
}

// maxLoginFieldLen caps username/password bytes BEFORE bcrypt (see
// handleLoginPost). Generous for legitimate input, bounded for CPU.
const maxLoginFieldLen = 256

func (h *handler) handleLoginGet(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	// The login page must never be cached: it reflects auth state and its
	// form targets a credential-bearing endpoint.
	w.Header().Set("Cache-Control", "no-store")
	data := loginData{}
	if h.store.ValidateSession(r) {
		data.SignedIn = true
		data.Username = h.creds.Username
	}
	_ = loginPage.Execute(w, data)
}

func (h *handler) handleLoginPost(w http.ResponseWriter, r *http.Request) {
	// Credential-bearing response: never cache, even on failure.
	w.Header().Set("Cache-Control", "no-store")
	// Rate-limit BEFORE bcrypt: every attempt burns a bcrypt comparison,
	// so the bucket must gate entry, not failure.
	if !allowLogin(w, r) {
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := r.ParseForm(); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	// Length caps BEFORE bcrypt: bcrypt only hashes the first 72 bytes, so
	// arbitrarily long inputs buy attacker-controlled CPU for zero signal.
	// 256 bytes is orders of magnitude above any legitimate username or
	// password typed into this form.
	username, password := r.FormValue("username"), r.FormValue("password")
	if len(username) > maxLoginFieldLen || len(password) > maxLoginFieldLen {
		http.Error(w, "invalid_request", http.StatusBadRequest)
		return
	}
	if !h.verify(username, password) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.WriteHeader(http.StatusUnauthorized)
		_ = loginPage.Execute(w, loginData{Error: true})
		return
	}
	if err := h.store.CreateSession(w, r); err != nil {
		http.Error(w, "could not create session", http.StatusInternalServerError)
		return
	}
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

func (h *handler) handleLogoutPost(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	// Logout is state-changing and the session cookie is sent on top-level
	// navigations (SameSite=Lax still sends it), so it requires the same
	// session-bound CSRF token as the other WebUI POST forms. A missing or
	// wrong token fails closed with 403 — including when the protector is
	// nil or fail-closed, so logout can never be downgraded to unguarded.
	if h.csrf == nil || !h.csrf.VerifyRequest(r) {
		http.Error(w, "csrf_mismatch", http.StatusForbidden)
		return
	}
	h.store.DestroySession(w, r)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}
