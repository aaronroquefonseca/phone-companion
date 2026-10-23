package webui

import (
	"html/template"
	"net/http"
	"strings"
)

// loginBody is the sign-in surface inside the shared layout: sign-in form,
// signed-in state, and failure message. Error text stays generic so
// usernames cannot be enumerated.
var loginBody = template.Must(template.New("loginBody").Parse(`<h1>Sign in</h1>
{{if .SignedIn}}
<p>Signed in as {{.Username}}.</p>
<p><a href="/">Open the operator dashboard</a>.</p>
{{else}}
{{if .Error}}<p class="error" role="alert">Invalid username or password.</p>{{end}}
<form method="post" action="/login">
<label>Username <input type="text" name="username" autocomplete="username" required></label>
<label>Password <input type="password" name="password" autocomplete="current-password" required></label>
<button type="submit">Sign in</button>
</form>
{{end}}`))

type loginData struct {
	Error    bool
	SignedIn bool
	Username string
	// CSRFToken is the session-bound token for the nav logout form when
	// signed in. Empty when there is no session (or the protector is
	// fail-closed); the logout POST then fails closed with 403.
	CSRFToken string
}

// maxLoginFieldLen caps username/password bytes BEFORE bcrypt (see
// handleLoginPost). Generous for legitimate input, bounded for CPU.
const maxLoginFieldLen = 256

func (h *handler) handleLoginGet(w http.ResponseWriter, r *http.Request) {
	data := loginData{}
	signedIn := h.store.ValidateSession(r)
	data.SignedIn = signedIn
	if signedIn {
		data.Username = h.creds.Username
		// Mint the logout token for the nav form (pair.go pattern).
		// TokenForRequest is nil-safe: a nil/fail-closed protector yields
		// "", and the logout POST then fails closed with 403.
		if tok, ok := h.csrf.TokenForRequest(r); ok {
			data.CSRFToken = tok
		}
	}
	var body strings.Builder
	_ = loginBody.Execute(&body, data)
	renderLayout(w, http.StatusOK, layoutData{
		Title:     "Sign in",
		Active:    "login",
		SignedIn:  signedIn,
		CSRFToken: data.CSRFToken,
		Body:      template.HTML(body.String()),
	})
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
		var body strings.Builder
		_ = loginBody.Execute(&body, loginData{Error: true})
		renderLayout(w, http.StatusUnauthorized, layoutData{
			Title: "Sign in",
			Body:  template.HTML(body.String()),
		})
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
