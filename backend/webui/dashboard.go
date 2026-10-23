package webui

import (
	"html/template"
	"net/http"
	"strings"
)

// dashboardBody renders the operator home: registry counts plus shortcuts
// to the pairing and notify-test flows.
var dashboardBody = template.Must(template.New("dashboardBody").Parse(`<h1>Operator dashboard</h1>
{{if .Error}}<p class="error">{{.Error}}</p>{{end}}
<div class="cards">
<section class="card" aria-label="Devices">
<h2>Devices</h2>
<p><span class="count">{{.DeviceCount}}</span> {{if eq .DeviceCount 1}}device{{else}}devices{{end}} paired</p>
<p><a class="btn" href="/webui/pair">Manage devices</a></p>
</section>
<section class="card" aria-label="Widgets">
<h2>Widgets</h2>
<p><span class="count">{{.WidgetCount}}</span> {{if eq .WidgetCount 1}}widget{{else}}widgets{{end}} configured</p>
<p><a class="btn" href="/webui/widgets">Preview widgets</a></p>
</section>
<section class="card" aria-label="Pairing">
<h2>Pair a device</h2>
<p>Issue a single-use enroll token and scan it with the Phone Companion app.</p>
<p><a class="btn" href="/webui/pair">Pair a new device</a></p>
</section>
<section class="card" aria-label="Notify">
<h2>Send a test notification</h2>
<p>Fan out a notification to all devices or one device.</p>
<p><a class="btn" href="/webui/notify">Test notify</a></p>
</section>
</div>`))

type dashboardPageData struct {
	DeviceCount int
	WidgetCount int
	Error       string
}

// HandleDashboard serves GET /: registry counts plus pairing/notify
// shortcuts. Exact-path only — anything else under the "/" pattern falls
// through to 404 so the catch-all never masks unknown routes.
func HandleDashboard(store Store, widgets WidgetLister, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/" {
			w.Header().Set("Cache-Control", "no-store")
			http.NotFound(w, r)
			return
		}
		if store == nil || widgets == nil {
			renderDashboardError(w, "internal error")
			return
		}
		devices, err := store.ListDevices(r.Context())
		if err != nil {
			renderDashboardError(w, "internal error")
			return
		}
		views, err := widgets.ListWidgets(r.Context())
		if err != nil {
			renderDashboardError(w, "internal error")
			return
		}
		var body strings.Builder
		_ = dashboardBody.Execute(&body, dashboardPageData{
			DeviceCount: len(devices),
			WidgetCount: len(views),
		})
		renderLayout(w, http.StatusOK, layoutData{
			Title:     "Dashboard",
			Active:    "home",
			SignedIn:  true,
			CSRFToken: layoutCSRFToken(csrf, r),
			Body:      template.HTML(body.String()),
		})
	}
}

func renderDashboardError(w http.ResponseWriter, msg string) {
	var body strings.Builder
	_ = dashboardBody.Execute(&body, dashboardPageData{Error: msg})
	renderLayout(w, http.StatusInternalServerError, layoutData{
		Title:    "Dashboard",
		Active:   "home",
		SignedIn: true,
		Body:     template.HTML(body.String()),
	})
}
