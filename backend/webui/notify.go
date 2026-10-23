package webui

import (
	"context"
	"html/template"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// NotifyRequest is one operator test-notification: exactly one of All or
// DeviceID selects the fan-out target (mirroring the notify API target
// union). The main-package NotifyService maps this onto requestToMessage so
// the form shares the API's validation and fan-out pipeline.
type NotifyRequest struct {
	All      bool
	DeviceID string
	Kind     string
	Title    string
	Body     string
	Priority string
	Secret   bool
}

// NotifyResult is a successful fan-out: the minted id plus the accepted
// device count (same semantics as the API's 202 payload).
type NotifyResult struct {
	NotificationID string
	Accepted       int
}

// NotifyError is a failed fan-out: a stable machine-readable Code plus the
// HTTP status the form surface should answer with (422 validation, 500
// driver/internal). Codes originate from the shared validation pipeline,
// never from secret-bearing content.
type NotifyError struct {
	Code   string
	Status int
}

func (e *NotifyError) Error() string { return "notify: " + e.Code }

// NotifyService fans out one operator test-notification through the shared
// pipeline (requestToMessage validation + the handleNotify fan-out core).
// Implemented in package main; tests inject fakes.
type NotifyService interface {
	SendNotify(ctx context.Context, req NotifyRequest) (NotifyResult, error)
}

// notifyPriorities is the closed contract vocabulary for the priority
// select. Kind stays a free-text input with suggestions: kinds are an open
// vocabulary by design (unknown kinds render generically on the app).
var notifyPriorities = []string{"min", "low", "default", "high", "max"}

var notifyFormTmpl = template.Must(template.New("notifyForm").Parse(`<h1>Send a test notification</h1>
{{if .Error}}<p class="error" id="notify-error" role="alert">{{.Error}}</p>{{end}}
<form method="post" action="/webui/notify"{{if .Error}} aria-describedby="notify-error"{{end}}>
<input type="hidden" name="csrf_token" value="{{.CSRFToken}}">
<div class="field">
<label for="notify-target">Target</label>
<select id="notify-target" name="target"{{if .FieldErrors.Target}} aria-invalid="true" aria-describedby="notify-target-error"{{end}}>
<option value="all"{{if eq .Target "all"}} selected{{end}}>All devices</option>
{{range .Devices}}<option value="{{.ID}}"{{if eq $.Target .ID}} selected{{end}}>{{.Name}} ({{.ID}})</option>{{end}}
</select>
{{if .FieldErrors.Target}}<p class="field-error" id="notify-target-error">{{.FieldErrors.Target}}</p>{{end}}
{{if not .Devices}}<p class="hint">No active devices yet — pairing one first makes targeting specific devices possible. Sending to "All devices" now records accepted:0 (nothing delivered); <a href="/webui/pair">pair a device first</a>.</p>{{end}}
</div>
<div class="field">
<label for="notify-kind">Kind</label>
<input id="notify-kind" type="text" name="kind" list="notify-kinds" value="{{.Kind}}" maxlength="64"{{if .FieldErrors.Kind}} aria-invalid="true" aria-describedby="notify-kind-error"{{end}}>
<datalist id="notify-kinds"><option value="note"></option><option value="alert"></option></datalist>
{{if .FieldErrors.Kind}}<p class="field-error" id="notify-kind-error">{{.FieldErrors.Kind}}</p>{{else}}<p class="hint">Open vocabulary: unknown kinds render generically on the app.</p>{{end}}
</div>
<div class="field">
<label for="notify-title">Title</label>
<input id="notify-title" type="text" name="title" value="{{.Title}}" maxlength="500" required{{if .FieldErrors.Title}} aria-invalid="true" aria-describedby="notify-title-error"{{end}}>
{{if .FieldErrors.Title}}<p class="field-error" id="notify-title-error">{{.FieldErrors.Title}}</p>{{end}}
</div>
<div class="field">
<label for="notify-body">Body</label>
<textarea id="notify-body" name="body" rows="4" maxlength="4096" required{{if .FieldErrors.Body}} aria-invalid="true" aria-describedby="notify-body-error"{{end}}>{{.Body}}</textarea>
{{if .FieldErrors.Body}}<p class="field-error" id="notify-body-error">{{.FieldErrors.Body}}</p>{{end}}
</div>
<div class="field">
<label for="notify-priority">Priority</label>
<select id="notify-priority" name="priority"{{if .FieldErrors.Priority}} aria-invalid="true" aria-describedby="notify-priority-error"{{end}}>
{{range .Priorities}}<option value="{{.}}"{{if eq $.Priority .}} selected{{end}}>{{.}}</option>{{end}}
</select>
{{if .FieldErrors.Priority}}<p class="field-error" id="notify-priority-error">{{.FieldErrors.Priority}}</p>{{end}}
</div>
<div class="field">
<label for="notify-secret">Secret content</label>
<input id="notify-secret" type="checkbox" name="secret" value="true"{{if .Secret}} checked{{end}}>
<p class="hint">When checked, title and body are encrypted per device instead of sent as plaintext. Each target device needs its key on file; devices without a usable key fail closed for that device.</p>
</div>
<div class="form-actions">
<button type="submit">Send notification</button>
</div>
</form>`))

var notifySentTmpl = template.Must(template.New("notifySent").Parse(`<h1>Notification sent</h1>
{{if .Celebrate}}<p class="success" role="status">Accepted by {{.Accepted}} {{if eq .Accepted 1}}device{{else}}devices{{end}}.</p>{{else}}<p class="notice" role="status">Notification recorded (ID <code>{{.NotificationID}}</code>) but no devices received it — <a href="/webui/pair">pair a device first</a>.</p>{{end}}
{{if .Celebrate}}<p>Notification ID: <code>{{.NotificationID}}</code></p>{{end}}
<p><a href="/webui/notify">Send another</a> · <a href="/">Back to dashboard</a></p>`))

type notifySentData struct {
	NotificationID string
	Accepted       int
	// Celebrate selects the green success banner (accepted > 0). A zero
	// fan-out renders neutral guidance instead — never a green
	// "accepted by 0" celebration.
	Celebrate bool
}

type notifyFormData struct {
	Devices    []DeviceInfo
	Priorities []string
	Target     string
	Kind       string
	Title      string
	Body       string
	Priority   string
	Secret     bool
	CSRFToken  string
	Error      string
	// FieldErrors carries per-field messages rendered under the matching
	// control (with aria-invalid + aria-describedby). Keys: Target, Kind,
	// Title, Body, Priority. The banner (.Error, role=alert) always stays
	// for the overall failure; generic codes set no field entry.
	FieldErrors map[string]string
}

// notifyFieldForCode maps a pipeline error code onto the form field it
// belongs under. Empty means banner-only (generic/driver/internal codes
// with no single owning control).
func notifyFieldForCode(code string) string {
	switch code {
	case "empty_target", "ambiguous_target", "too_many_targets":
		return "Target"
	case "kind_too_long":
		return "Kind"
	case "missing_title", "title_too_long":
		return "Title"
	case "missing_body", "body_too_long":
		return "Body"
	case "invalid_priority":
		return "Priority"
	default:
		return ""
	}
}

// notifyErrorText maps pipeline codes to operator-facing copy. Codes are
// internal constants, safe to render; unknown codes fall back to generic
// text plus the code so new validations stay debuggable.
func notifyErrorText(code string) string {
	switch code {
	case "empty_target":
		return "Pick a target: all devices or one device."
	case "ambiguous_target":
		return "Pick exactly one target: all devices or one device."
	case "too_many_targets":
		return "Too many targets selected."
	case "kind_too_long":
		return "Kind must be at most 64 characters."
	case "missing_title":
		return "Title must be non-empty."
	case "title_too_long":
		return "Title must be at most 500 characters."
	case "missing_body":
		return "Body must be non-empty."
	case "body_too_long":
		return "Body must be at most 4096 characters."
	case "invalid_priority":
		return "Priority must be one of min, low, default, high, max."
	case "channel_too_long", "invalid_action", "action_too_long", "too_many_actions":
		return "Request invalid (" + code + ")."
	case "payload_too_long":
		return "Message too large to send."
	case "target_lookup_failed", "internal":
		return "Internal error — please try again."
	case "driver_unavailable":
		return "No delivery driver available, or every send failed. Check the notify driver configuration."
	default:
		if code == "" {
			return "Invalid request — please check the form."
		}
		return "Request invalid (" + code + ")."
	}
}

// activeDevices filters the registry to non-revoked rows for the target
// dropdown: revoked devices can receive nothing, so offering them would
// only produce accepted:0 surprises.
func activeDevices(devices []DeviceInfo) []DeviceInfo {
	out := make([]DeviceInfo, 0, len(devices))
	for _, d := range devices {
		if !d.Revoked {
			out = append(out, d)
		}
	}
	return out
}

// sentNotice parses the whitelisted PRG query (?sent=<id>&accepted=<n>)
// from the POST redirect. IDs are opaque hex (bounded length, word chars
// only); accepted is a small non-negative integer. Anything else renders
// no notice, so forged query input can never inject page content or fake
// a confirmation for a fan-out that never happened.
func sentNotice(sent, accepted string) (id string, n int, ok bool) {
	if sent == "" || len(sent) > 128 {
		return "", 0, false
	}
	for _, c := range sent {
		if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
			return "", 0, false
		}
	}
	a, err := strconv.Atoi(accepted)
	if err != nil || a < 0 || a > 1000000 {
		return "", 0, false
	}
	return sent, a, true
}

// HandleNotifyGet serves GET /webui/notify: the test-notification form with
// the target dropdown built from the live device registry, plus the PRG
// confirmation banner after a successful POST (?sent=<id>&accepted=<n>).
func HandleNotifyGet(store Store, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if store == nil {
			renderNotifyForm(w, http.StatusInternalServerError, notifyFormData{Error: "internal error"})
			return
		}
		devices, err := store.ListDevices(r.Context())
		if err != nil {
			renderNotifyForm(w, http.StatusInternalServerError, notifyFormData{Error: "internal error"})
			return
		}
		if id, n, ok := sentNotice(r.URL.Query().Get("sent"), r.URL.Query().Get("accepted")); ok {
			var body strings.Builder
			_ = notifySentTmpl.Execute(&body, notifySentData{NotificationID: id, Accepted: n, Celebrate: n > 0})
			renderLayout(w, http.StatusOK, layoutData{
				Title:     "Notification sent",
				Active:    "notify",
				SignedIn:  true,
				CSRFToken: layoutCSRFToken(csrf, r),
				Body:      template.HTML(body.String()),
			})
			return
		}
		renderNotifyForm(w, http.StatusOK, notifyFormData{
			Devices:    activeDevices(devices),
			Priorities: notifyPriorities,
			Target:     "all",
			Kind:       "note",
			Priority:   "default",
			CSRFToken:  layoutCSRFToken(csrf, r),
		})
	}
}

// HandleNotifyPost serves POST /webui/notify: validates the session CSRF
// token exactly like the revoke form, then fans out through NotifyService
// (the shared requestToMessage + fan-out pipeline — no duplicated logic).
// Success is PRG: 303 to /webui/notify?sent=<id>&accepted=<n>, and GET
// renders the confirmation — so refreshing the result page can never
// re-send the fan-out. Validation failures re-render the form with the
// pipeline's status and echoed input.
func HandleNotifyPost(store Store, csrf *CSRFProtection, svc NotifyService) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// The outcome (notification id, accepted count) is operator state;
		// never cache it.
		w.Header().Set("Cache-Control", "no-store")
		if store == nil || svc == nil {
			renderNotifyForm(w, http.StatusInternalServerError, notifyFormData{Error: "internal error"})
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		if err := r.ParseForm(); err != nil {
			renderOperatorError(w, r, csrf, true, "notify", http.StatusBadRequest,
				"Send a test notification", "Bad request — please try sending again.",
				"/webui/notify", "Back to notify")
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			renderOperatorError(w, r, csrf, true, "notify", http.StatusForbidden,
				"Send a test notification", "Security token mismatch — please reload the form and try again (csrf_mismatch).",
				"/webui/notify", "Back to notify")
			return
		}
		target := strings.TrimSpace(r.FormValue("target"))
		req := NotifyRequest{
			All:      target == "" || target == "all",
			DeviceID: target,
			Kind:     strings.TrimSpace(r.FormValue("kind")),
			Title:    strings.TrimSpace(r.FormValue("title")),
			Body:     strings.TrimSpace(r.FormValue("body")),
			Priority: strings.TrimSpace(r.FormValue("priority")),
			Secret:   r.FormValue("secret") == "true" || r.FormValue("secret") == "on",
		}
		if req.All {
			req.DeviceID = ""
		}
		res, err := svc.SendNotify(r.Context(), req)
		if err != nil {
			status := http.StatusInternalServerError
			text := "Internal error — please try again."
			code := ""
			if nerr, ok := err.(*NotifyError); ok {
				if nerr.Status != 0 {
					status = nerr.Status
				}
				code = nerr.Code
				text = notifyErrorText(code)
			}
			var fieldErrors map[string]string
			if field := notifyFieldForCode(code); field != "" {
				fieldErrors = map[string]string{field: text}
			}
			devices, lerr := store.ListDevices(r.Context())
			if lerr != nil {
				devices = nil
			}
			displayTarget := target
			if displayTarget == "" {
				displayTarget = "all"
			}
			renderNotifyForm(w, status, notifyFormData{
				Devices:     activeDevices(devices),
				Priorities:  notifyPriorities,
				Target:      displayTarget,
				Kind:        req.Kind,
				Title:       req.Title,
				Body:        req.Body,
				Priority:    req.Priority,
				Secret:      req.Secret,
				CSRFToken:   layoutCSRFToken(csrf, r),
				Error:       text,
				FieldErrors: fieldErrors,
			})
			return
		}
		// PRG: redirect to the GET confirmation so a refresh re-renders
		// state instead of re-fanning-out. Params are server-issued and
		// whitelisted on parse (see sentNotice); the id charset needs no
		// escaping beyond QueryEscape here.
		loc := "/webui/notify?sent=" + url.QueryEscape(res.NotificationID) +
			"&accepted=" + strconv.Itoa(res.Accepted)
		http.Redirect(w, r, loc, http.StatusSeeOther)
	}
}

func renderNotifyForm(w http.ResponseWriter, status int, data notifyFormData) {
	if data.Priorities == nil {
		data.Priorities = notifyPriorities
	}
	if data.Priority == "" {
		data.Priority = "default"
	}
	var body strings.Builder
	_ = notifyFormTmpl.Execute(&body, data)
	renderLayout(w, status, layoutData{
		Title:     "Send a test notification",
		Active:    "notify",
		SignedIn:  true,
		CSRFToken: data.CSRFToken,
		Body:      template.HTML(body.String()),
	})
}
