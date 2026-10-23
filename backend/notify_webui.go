// Package main — WebUI operator pages wiring (dashboard/widgets/notify).
//
// The page rendering lives in backend/webui (layout, dashboard, widgets
// preview, notify form). This file is the main-package glue: adapters from
// the process widget registry and the notify fan-out pipeline onto the
// webui interfaces, plus route registration behind webui.RequireAdmin.
//
// Auth gating matches the pairing pages: every route here is operator-only
// (302 to /login without a session). The notify POST requires the same
// session-bound CSRF token as the revoke form; the API write guard is not
// needed because a plain HTML form is the intended client.
package main

import (
	"context"
	"database/sql"
	"errors"
	"net/http"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// webuiWidgetAdapter exposes the process widget registry to the operator
// preview as read-only webui.WidgetView rows. Secrets stay masked by
// construction: the snapshot holds no secret payload bytes (payload is nil
// for secret widgets), and only envelope fields are copied out.
type webuiWidgetAdapter struct {
	reg *WidgetRegistry
}

func (a webuiWidgetAdapter) ListWidgets(_ context.Context) ([]webui.WidgetView, error) {
	if a.reg == nil {
		return nil, errors.New("widget registry not wired")
	}
	a.reg.maybeReload()
	a.reg.mu.RLock()
	defer a.reg.mu.RUnlock()
	out := make([]webui.WidgetView, 0, len(a.reg.snap.order))
	for _, id := range a.reg.snap.order {
		rec := a.reg.snap.widgets[id]
		view := webui.WidgetView{
			ID:        rec.envelope.ID,
			Type:      rec.envelope.Type,
			Title:     rec.envelope.Title,
			UpdatedAt: rec.envelope.UpdatedAt,
			SecretRef: rec.envelope.SecretRef,
		}
		if len(rec.envelope.Payload) > 0 {
			view.Payload = append([]byte(nil), rec.envelope.Payload...)
		}
		out = append(out, view)
	}
	return out, nil
}

// webuiNotifyService implements webui.NotifyService through the shared
// pipeline: the form fields map onto notifyRequestJSON, which flows through
// requestToMessage (validation + defaults, same as the API) and the shared
// fanoutNotify core (target resolution, per-device send, history). No
// fan-out logic is duplicated here.
type webuiNotifyService struct {
	db     *sql.DB
	sender notify.Sender
}

func (s webuiNotifyService) SendNotify(ctx context.Context, req webui.NotifyRequest) (webui.NotifyResult, error) {
	fail := func(code string, status int) (webui.NotifyResult, error) {
		return webui.NotifyResult{}, &webui.NotifyError{Code: code, Status: status}
	}
	if s.db == nil {
		return fail("internal", http.StatusInternalServerError)
	}
	var target notifyTargetJSON
	if req.All {
		all := true
		target.All = &all
	} else {
		target.DeviceIDs = []string{req.DeviceID}
	}
	msg, err := requestToMessage(notifyRequestJSON{
		Target:   target,
		Kind:     req.Kind,
		Title:    req.Title,
		Body:     req.Body,
		Priority: req.Priority,
		Secret:   req.Secret,
	})
	if err != nil {
		var verr *notify.ValidationError
		if errors.As(err, &verr) {
			return fail(verr.Code, http.StatusUnprocessableEntity)
		}
		return fail("invalid_request", http.StatusBadRequest)
	}
	id, accepted, status, code := fanoutNotify(ctx, s.db, s.sender, msg)
	if status != http.StatusAccepted {
		return fail(code, status)
	}
	return webui.NotifyResult{NotificationID: id, Accepted: accepted}, nil
}

// RegisterNotifyWebUIRoutes wires the operator test-notification form:
//
//	GET  /webui/notify → the form (target dropdown from the live registry)
//	POST /webui/notify → session-CSRF-guarded fan-out, 202 confirmation page
//
// Both sit behind webui.RequireAdmin like the pairing pages. Called from
// NewMux (which owns db+sender) and from main.go's base mux; the reg-only
// NewMuxWithWidgets constructor intentionally has no notify surface (no db
// or sender to fan out with).
func RegisterNotifyWebUIRoutes(mux *http.ServeMux, db *sql.DB, sender notify.Sender, sessions webui.SessionStore, csrf *webui.CSRFProtection) {
	if sessions == nil {
		sessions = denyAllSessions{}
	}
	store := webuiStoreAdapter{}
	svc := webuiNotifyService{db: db, sender: sender}
	mux.Handle("GET /webui/notify", webui.RequireAdmin(sessions, webui.HandleNotifyGet(store, csrf)))
	mux.Handle("POST /webui/notify", webui.RequireAdmin(sessions, webui.HandleNotifyPost(store, csrf, svc)))
}
