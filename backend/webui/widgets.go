package webui

import (
	"context"
	"encoding/json"
	"html/template"
	"net/http"
	"strings"
)

// WidgetView is one operator-preview row. Payload carries the stored bytes
// for text/list/status widgets; for secret widgets the snapshot holds no
// payload bytes at all (see widgets_load.go: payload is nil, plaintext lives
// only in the serve-time secretPlaintext), so the preview physically cannot
// leak secret material — and the renderer below never touches secret
// payloads regardless.
type WidgetView struct {
	ID        string
	Type      string
	Title     string
	UpdatedAt string
	Payload   json.RawMessage
	SecretRef string
}

// WidgetLister abstracts the widget registry for the operator preview (and
// the dashboard count). The main-package adapter snapshots the process
// registry; tests inject fakes.
type WidgetLister interface {
	ListWidgets(ctx context.Context) ([]WidgetView, error)
}

// widgetRow is one rendered table row. Only one of the value fields is set
// per type; Secret rows carry no value at all — just the masked note.
type widgetRow struct {
	ID          string
	Type        string
	Title       string
	UpdatedAt   string
	SecretRef   string
	Secret      bool
	TextBody    string
	ListItems   []widgetListItem
	StatusState string
	StatusDet   string
	Broken      bool
}

type widgetListItem struct {
	Text string
	Done bool
}

var widgetsBody = template.Must(template.New("widgetsBody").Parse(`<h1>Widgets</h1>
{{if .Error}}<p class="error">{{.Error}}</p>{{end}}
{{if .Rows}}
<table>
<tr><th>Widget</th><th>Type</th><th>Updated</th><th>Value</th></tr>
{{range .Rows}}
<tr>
<td>{{.Title}}<br><code>{{.ID}}</code></td>
<td>{{.Type}}</td>
<td>{{.UpdatedAt}}</td>
<td>
{{if .Secret}}
<span class="masked" aria-label="hidden secret value">&#8226;&#8226;&#8226;&#8226;&#8226;&#8226;</span>
<p class="muted">Secret value hidden. Served encrypted per device only{{if .SecretRef}} (device <code>{{.SecretRef}}</code>){{end}}.</p>
{{else if .TextBody}}<pre>{{.TextBody}}</pre>
{{else if .ListItems}}<ul>{{range .ListItems}}<li>{{if .Done}}[x] {{else}}[ ] {{end}}{{.Text}}</li>{{end}}</ul>
{{else if .StatusState}}<p>State: <strong>{{.StatusState}}</strong></p>{{if .StatusDet}}<p>{{.StatusDet}}</p>{{end}}
{{else}}<p class="muted">No preview available for this widget.</p>
{{end}}
</td>
</tr>
{{end}}
</table>
{{else}}
{{if not .Error}}<p>No widgets configured.</p>{{end}}
{{end}}`))

type widgetsPageData struct {
	Rows  []widgetRow
	Error string
}

// HandleWidgetsPage serves GET /webui/widgets: a live operator preview of
// every widget (type/title/updated_at plus rendered values for
// text/list/status). Secret rows render masked with an explanatory note and
// NEVER include secret plaintext — the snapshot carries no secret payload
// bytes for them, and the renderer does not consult any secret source.
func HandleWidgetsPage(lister WidgetLister, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if lister == nil {
			renderWidgetsError(w, "internal error")
			return
		}
		views, err := lister.ListWidgets(r.Context())
		if err != nil {
			renderWidgetsError(w, "internal error")
			return
		}
		rows := make([]widgetRow, 0, len(views))
		for _, v := range views {
			rows = append(rows, renderWidgetRow(v))
		}
		var body strings.Builder
		_ = widgetsBody.Execute(&body, widgetsPageData{Rows: rows})
		renderLayout(w, http.StatusOK, layoutData{
			Title:     "Widgets",
			Active:    "widgets",
			SignedIn:  true,
			CSRFToken: layoutCSRFToken(csrf, r),
			Body:      template.HTML(body.String()),
		})
	}
}

func renderWidgetsError(w http.ResponseWriter, msg string) {
	var body strings.Builder
	_ = widgetsBody.Execute(&body, widgetsPageData{Error: msg})
	renderLayout(w, http.StatusInternalServerError, layoutData{
		Title:    "Widgets",
		Active:   "widgets",
		SignedIn: true,
		Body:     template.HTML(body.String()),
	})
}

// renderWidgetRow maps one view onto display values. Secrets short-circuit
// before any payload handling: no secret bytes are read, parsed, or copied.
func renderWidgetRow(v WidgetView) widgetRow {
	row := widgetRow{
		ID:        v.ID,
		Type:      v.Type,
		Title:     v.Title,
		UpdatedAt: v.UpdatedAt,
		SecretRef: v.SecretRef,
	}
	if v.Type == "secret" {
		row.Secret = true
		return row
	}
	switch v.Type {
	case "text":
		var p struct {
			Body string `json:"body"`
		}
		if err := json.Unmarshal(v.Payload, &p); err != nil || p.Body == "" {
			row.Broken = true
			break
		}
		row.TextBody = p.Body
	case "list":
		var p struct {
			Items []struct {
				Text string `json:"text"`
				Done bool   `json:"done"`
			} `json:"items"`
		}
		if err := json.Unmarshal(v.Payload, &p); err != nil || p.Items == nil {
			row.Broken = true
			break
		}
		for _, it := range p.Items {
			row.ListItems = append(row.ListItems, widgetListItem{Text: it.Text, Done: it.Done})
		}
	case "status":
		var p struct {
			State  string `json:"state"`
			Detail string `json:"detail"`
		}
		if err := json.Unmarshal(v.Payload, &p); err != nil || p.State == "" {
			row.Broken = true
			break
		}
		row.StatusState, row.StatusDet = p.State, p.Detail
	default:
		row.Broken = true
	}
	return row
}
