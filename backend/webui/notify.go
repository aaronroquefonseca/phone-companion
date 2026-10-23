package webui

import (
	"context"
	"html/template"
	"net/http"
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
{{if .Error}}<p class="error" role="alert">{{.Error}}</p>{{end}}
<form method="post" action="/webui/notify">
<input type="hidden" name="csrf_token" value="{{.CSRFToken}}">
<label>Target
<select name="target">
<option value="all"{{if eq .Target "all"}} selected{{end}}>All devices</option>
{{range .Devices}}<option value="{{.ID}}"{{if eq $.Target .ID}} selected{{end}}>{{.Name}} ({{.ID}})</option>{{end}}
</select></label>
{{if not .Devices}}<p class="muted">No active devices yet — pairing one first makes targeting specific devices possible. "All devices" still works (accepted: 0 until a device pairs).</p>{{end}}
<label>Kind <input type="text" name="kind" list="notify-kinds" value="{{.Kind}}" maxlength="64">
<datalist id="notify-kinds"><option value="note"></option><option value="alert"></option></datalist></label>
<p class="muted">Open vocabulary: unknown kinds render generically on the app.</p>
<label>Title <input type="text" name="title" value="{{.Title}}" maxlength="500" required></label>
<label>Body <textarea name="body" rows="4" maxlength="4096" required>{{.Body}}</textarea></label>
<label>Priority
<select name="priority">
{{range .Priorities}}<option value="{{.}}"{{if eq $.Priority .}} selected{{end}}>{{.}}</option>{{end}}
</select></label>
<label><input type="checkbox" name="secret" value="true"{{if .Secret}} checked{{end}}> Secret content</label>
<p class="muted">When checked, title and body are encrypted per device instead of sent as plaintext. Each target device needs its key on file; devices without a usable key fail closed for that device.</p>
<button type="submit">Send notification</button>
</form>`))

var notifySentTmpl = template.Must(template.New("notifySent").Parse(`<h1>Notification sent</h1>
<p class="success" role="status">Accepted by {{.Accepted}} {{if eq .Accepted 1}}device{{else}}devices{{end}}.</p>
<p>Notification ID: <code>{{.NotificationID}}</code></p>
<p><a href="/webui/notify">Send another</a> · <a href="/">Back to dashboard</a></p>`))

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

// HandleNotifyGet serves GET /webui/notify: the test-notification form with
// the target dropdown built from the live device registry.
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
// Success answers 202 with a confirmation page; validation failures
// re-render the form with the pipeline's status and echoed input.
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
			http.Error(w, "invalid_request", http.StatusBadRequest)
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			http.Error(w, "csrf_mismatch", http.StatusForbidden)
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
			if nerr, ok := err.(*NotifyError); ok {
				if nerr.Status != 0 {
					status = nerr.Status
				}
				text = notifyErrorText(nerr.Code)
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
				Devices:    activeDevices(devices),
				Priorities: notifyPriorities,
				Target:     displayTarget,
				Kind:       req.Kind,
				Title:      req.Title,
				Body:       req.Body,
				Priority:   req.Priority,
				Secret:     req.Secret,
				CSRFToken:  layoutCSRFToken(csrf, r),
				Error:      text,
			})
			return
		}
		var body strings.Builder
		_ = notifySentTmpl.Execute(&body, res)
		renderLayout(w, http.StatusAccepted, layoutData{
			Title:     "Notification sent",
			Active:    "notify",
			SignedIn:  true,
			CSRFToken: layoutCSRFToken(csrf, r),
			Body:      template.HTML(body.String()),
		})
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
