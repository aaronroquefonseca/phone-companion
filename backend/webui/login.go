package webui

import (
	"html/template"
	"net/http"
	"strings"
)

// loginBody is the sign-in surface inside the shared layout: sign-in form
// and failure message. Error text stays generic so usernames cannot be
// enumerated — hence the banner only, with no per-field errors (a
// field-level message would oracle which half failed). Fields use the
// stacked pattern: div.field > label ABOVE its control, so both text
// fields share one width and their left edges align. The username echoes
// back on failure and autofocuses; the signed-in state is not rendered
// here — authed GET /login redirects to / (see handleLoginGet).
var loginBody = template.Must(template.New("loginBody").Parse(`<h1>Sign in</h1>
{{if .SignedOut}}<p class="success" role="status">Signed out.</p>{{end}}
{{if .Error}}<p class="error" id="login-error" role="alert">Invalid username or password.</p>{{end}}
<form method="post" action="/login"{{if .Error}} aria-describedby="login-error"{{end}}>
<div class="field">
<label for="login-username">Username</label>
<input id="login-username" type="text" name="username" value="{{.Username}}" autocomplete="username" required autofocus>
</div>
<div class="field">
<label for="login-password">Password</label>
<input id="login-password" type="password" name="password" autocomplete="current-password" required>
</div>
<div class="form-actions">
<button type="submit">Sign in</button>
</div>
</form>`))

type loginData struct {
	Error    bool
	Username string
	// SignedOut renders the post-logout flash (?signed_out=1, whitelisted
	// in handleLoginGet; any other value renders nothing).
	SignedOut bool
	// CSRFToken is the session-bound token for the nav logout form when
	// signed in. Empty when there is no session (or the protector is
	// fail-closed); the logout POST then fails closed with 403.
	CSRFToken string
}

// maxLoginFieldLen caps username/password bytes BEFORE bcrypt (see
// handleLoginPost). Generous for legitimate input, bounded for CPU.
const maxLoginFieldLen = 256

func (h *handler) handleLoginGet(w http.ResponseWriter, r *http.Request) {
	// Signed-in operators go to the dashboard: rendering a second signed-in
	// page here would duplicate the nav and strand the logout form.
	if h.store.ValidateSession(r) {
		http.Redirect(w, r, "/", http.StatusSeeOther)
		return
	}
	data := loginData{}
	// Whitelisted one-shot flag only (?signed_out=1 from the logout
	// redirect); anything else renders no notice.
	if r.URL.Query().Get("signed_out") == "1" {
		data.SignedOut = true
	}
	var body strings.Builder
	_ = loginBody.Execute(&body, data)
	renderLayout(w, http.StatusOK, layoutData{
		Title:    "Sign in",
		Active:   "login",
		SignedIn: false,
		Body:     template.HTML(body.String()),
	})
}

func (h *handler) handleLoginPost(w http.ResponseWriter, r *http.Request) {
	// Already signed in: no credential check, straight to the dashboard
	// (covers POST-while-authed as well as GET-while-authed).
	if h.store.ValidateSession(r) {
		http.Redirect(w, r, "/", http.StatusSeeOther)
		return
	}
	// Credential-bearing response: never cache, even on failure.
	w.Header().Set("Cache-Control", "no-store")
	// Rate-limit BEFORE bcrypt: every attempt burns a bcrypt comparison,
	// so the bucket must gate entry, not failure.
	if !allowLogin(w, r) {
		renderOperatorError(w, r, h.csrf, false, "login", http.StatusTooManyRequests,
			"Too many sign-in attempts", "Too many sign-in attempts — please wait a minute and try again (rate_limited).",
			"/login", "Back to Sign in")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := r.ParseForm(); err != nil {
		renderOperatorError(w, r, h.csrf, false, "login", http.StatusBadRequest,
			"Sign in", "Bad request — please try signing in again.",
			"/login", "Back to Sign in")
		return
	}
	// Length caps BEFORE bcrypt: bcrypt only hashes the first 72 bytes, so
	// arbitrarily long inputs buy attacker-controlled CPU for zero signal.
	// 256 bytes is orders of magnitude above any legitimate username or
	// password typed into this form.
	username, password := r.FormValue("username"), r.FormValue("password")
	if len(username) > maxLoginFieldLen || len(password) > maxLoginFieldLen {
		renderOperatorError(w, r, h.csrf, false, "login", http.StatusBadRequest,
			"Sign in", "Bad request — please try signing in again.",
			"/login", "Back to Sign in")
		return
	}
	if !h.verify(username, password) {
		var body strings.Builder
		// Echo the username so a typo is visible; the password never
		// echoes. Error text stays generic (no enumeration oracle).
		_ = loginBody.Execute(&body, loginData{Error: true, Username: username})
		renderLayout(w, http.StatusUnauthorized, layoutData{
			Title:  "Sign in",
			Active: "login",
			Body:   template.HTML(body.String()),
		})
		return
	}
	if err := h.store.CreateSession(w, r); err != nil {
		renderOperatorError(w, r, h.csrf, false, "login", http.StatusInternalServerError,
			"Sign in", "Could not create a session — please try again.",
			"/login", "Back to Sign in")
		return
	}
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (h *handler) handleLogoutPost(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	// Logout is state-changing and the session cookie is sent on top-level
	// navigations (SameSite=Lax still sends it), so it requires the same
	// session-bound CSRF token as the other WebUI POST forms. A missing or
	// wrong token fails closed with 403 — including when the protector is
	// nil or fail-closed, so logout can never be downgraded to unguarded.
	if h.csrf == nil || !h.csrf.VerifyRequest(r) {
		renderOperatorError(w, r, h.csrf, h.store.ValidateSession(r), "login", http.StatusForbidden,
			"Sign out", "Security token mismatch — please sign in again and try signing out once more (csrf_mismatch).",
			"/", "Back to dashboard")
		return
	}
	h.store.DestroySession(w, r)
	http.Redirect(w, r, "/login?signed_out=1", http.StatusSeeOther)
}

// handleLogoutMethodNotAllowed answers GET /logout (and any non-POST routed
// here) with a layout-wrapped 405: the sign-out action is POST-only
// (CSRF-guarded form in the nav), so a plain navigation explains that
// instead of 404ing or signing out via GET.
func (h *handler) handleLogoutMethodNotAllowed(w http.ResponseWriter, r *http.Request) {
	renderOperatorError(w, r, h.csrf, h.store.ValidateSession(r), "login", http.StatusMethodNotAllowed,
		"Sign out", "Signing out needs the Sign out button (POST /logout) — a plain link cannot sign you out.",
		"/", "Back to dashboard")
}
