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

func (h *handler) handleLoginGet(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	data := loginData{}
	if h.store.ValidateSession(r) {
		data.SignedIn = true
		data.Username = h.creds.Username
	}
	_ = loginPage.Execute(w, data)
}

func (h *handler) handleLoginPost(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
	if err := r.ParseForm(); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if !h.verify(r.FormValue("username"), r.FormValue("password")) {
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
	h.store.DestroySession(w, r)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}
