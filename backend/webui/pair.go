package webui

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"html/template"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// enrollPayload is the QR contents scanned by the phone: where to POST and
// the single-use token authorizing that POST. The keys enroll_url +
// enroll_token are canonical — the Android app (P6 PairingQr) parses them —
// and must not be renamed.
type enrollPayload struct {
	EnrollURL   string `json:"enroll_url"`
	EnrollToken string `json:"enroll_token"`
}

// ExternalBaseURL is the configured public origin (EXTERNAL_BASE_URL,
// e.g. "https://example.com") used to build enroll_url. Set once at boot
// from Config. When empty the builder falls back to the request Host +
// X-Forwarded-Proto, which is dev-only: Host headers are
// client-controlled and spoofable behind a misconfigured proxy, so
// production must set EXTERNAL_BASE_URL (see docs/threat-model.md).
var ExternalBaseURL string

// EnrollPayloadForRequest builds the canonical QR payload
// {"enroll_url":..., "enroll_token":...}. With ExternalBaseURL set the URL
// is ExternalBaseURL + "/api/v1/enroll"; otherwise it reflects the request
// (X-Forwarded-Proto, then TLS state) and Host — dev-only fallback.
func EnrollPayloadForRequest(r *http.Request, rawToken string) (string, error) {
	base := strings.TrimSuffix(strings.TrimSpace(ExternalBaseURL), "/")
	if base == "" {
		scheme := r.Header.Get("X-Forwarded-Proto")
		if scheme == "" {
			if r.TLS != nil {
				scheme = "https"
			} else {
				scheme = "http"
			}
		}
		host := r.Host
		if host == "" {
			host = "localhost"
		}
		base = scheme + "://" + host
	}
	raw, err := json.Marshal(enrollPayload{
		EnrollURL:   base + "/api/v1/enroll",
		EnrollToken: rawToken,
	})
	if err != nil {
		return "", err
	}
	return string(raw), nil
}

// originFallback reports whether the QR was built from the request Host
// (ExternalBaseURL unset). Callers surface an in-page warning: a phone
// outside this network may not reach a Host-derived origin.
func originFallback() bool {
	return strings.TrimSpace(ExternalBaseURL) == ""
}

var pairBodyTmpl = template.Must(template.New("pairBody").Parse(`<h1>Pair a device</h1>
{{if .Notice}}<p class="error" role="alert">{{.Notice}}</p>{{end}}
{{if .RevokedFlash}}<p class="success" role="status">Revoked {{.RevokedFlash}}.</p>{{end}}
{{if .RenamedFlash}}<p class="success" role="status">Renamed to {{.RenamedFlash}}.</p>{{end}}
{{if .PurgedFlash}}<p class="success" role="status">Purged {{.PurgedFlash}} — its record is gone. Its old credentials already stopped working at revoke time.</p>{{end}}
{{if .ConfirmRevokeID}}
<div class="notice" aria-live="assertive">
<p>Revoke <strong>{{.ConfirmRevokeName}}</strong> (<code>{{.ConfirmRevokeID}}</code>)? It will stop working immediately.</p>
<form method="post" action="/webui/pair/revoke"><input type="hidden" name="device_id" value="{{.ConfirmRevokeID}}"><input type="hidden" name="confirm" value="yes"><input type="hidden" name="csrf_token" value="{{.CSRFToken}}"><button type="submit">Confirm revoke</button> <a href="/webui/pair">Cancel</a></form>
</div>
{{else if .ConfirmPurgeID}}
<div class="notice" aria-live="assertive">
<p>Permanently delete the revoked record for <strong>{{.ConfirmPurgeName}}</strong> (<code>{{.ConfirmPurgeID}}</code>)? This cannot be undone. Its credentials already stopped working at revoke time; purging only removes the registry row.</p>
<form method="post" action="/webui/pair/purge"><input type="hidden" name="device_id" value="{{.ConfirmPurgeID}}"><input type="hidden" name="confirm" value="yes"><input type="hidden" name="csrf_token" value="{{.CSRFToken}}"><button type="submit">Confirm purge</button> <a href="/webui/pair?show=revoked">Cancel</a></form>
</div>
{{else if .TokenPresent}}
<div class="notice" id="pair-qr-panel" data-token-hash="{{.TokenHash}}" aria-live="polite">
<p><strong>Scan with the Phone Companion app to enroll.</strong> Single-use token — <span class="countdown" data-expires-epoch="{{.ExpiresEpoch}}" title="Expires at {{.ExpiresAt}} (UTC)">expires in {{.ExpiresHuman}}</span>.</p>
{{if .OriginFallback}}<p class="error" role="alert">Warning: EXTERNAL_BASE_URL is not set, so this QR encodes the request host ({{.OriginHost}}). A phone outside this network may not reach it — set EXTERNAL_BASE_URL to the public origin.</p>{{end}}
<img src="{{.QRDataURI}}" alt="Pairing QR code" width="256" height="256">
<p>Token (copy if needed):<br><code id="pair-token">{{.RawToken}}</code> <button type="button" data-copy="pair-token">Copy token</button></p>
<form method="post" action="/webui/pair/mint"><input type="hidden" name="csrf_token" value="{{.CSRFToken}}"><button type="submit" class="secondary">Issue a fresh token</button></form>
<p class="muted" id="pair-refresh-note"></p>
</div>
<script>
(function(){
var btn=document.querySelector('[data-copy]');
if(btn){btn.addEventListener('click',function(){
var el=document.getElementById(btn.getAttribute('data-copy'));
if(!el)return;
var t=el.textContent;
function done(){var o=btn.textContent;btn.textContent='Copied';setTimeout(function(){btn.textContent=o;},1500);}
if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(t).then(done,done);}else{var ta=document.createElement('textarea');ta.value=t;document.body.appendChild(ta);ta.select();try{document.execCommand('copy');}catch(e){}document.body.removeChild(ta);done();}
});}
var cd=document.querySelector('.countdown');
if(cd){var exp=parseInt(cd.getAttribute('data-expires-epoch'),10)*1000;
var tick=function(){var ms=exp-Date.now();if(ms<0)ms=0;var s=Math.floor(ms/1000);var m=Math.floor(s/60);var label=m>0?('expires in '+m+' min'+(m===1?'':'s')):('expires in '+s+' s');cd.textContent=label;};
tick();var iv=setInterval(function(){tick();if(Date.now()>exp){clearInterval(iv);}},1000);}
if(cd&&!window.__pairAutoRefreshScheduled){window.__pairAutoRefreshScheduled=true;
var expMs=parseInt(cd.getAttribute('data-expires-epoch'),10)*1000;
var wait=expMs-Date.now()-60000;
if(wait>0&&wait<14*60*1000){setTimeout(function(){
if(window.__pairLiveDone)return;
var meta=document.querySelector('meta[name="csrf-token"]');
var tok=meta?meta.getAttribute('content'):'';
fetch('/webui/pair/mint',{method:'POST',headers:{'Accept':'application/json','X-CSRF-Token':tok},credentials:'same-origin'}).then(function(r){
if(!r.ok){throw new Error('refresh failed: '+r.status);}
return r.json();
}).then(function(j){
var img=document.querySelector('img[alt="Pairing QR code"]');
if(img&&j.qr_data_uri)img.src=j.qr_data_uri;
var code=document.getElementById('pair-token');
if(code&&j.raw_token)code.textContent=j.raw_token;
if(j.token_hash){var qrPanel=document.getElementById('pair-qr-panel');if(qrPanel)qrPanel.setAttribute('data-token-hash',j.token_hash);}
if(j.expires_at&&cd){cd.setAttribute('data-expires-epoch',j.expires_epoch);cd.setAttribute('title','Expires at '+j.expires_at+' (UTC)');window.__pairAutoRefreshScheduled=false;}
var note=document.getElementById('pair-refresh-note');
if(note)note.textContent='Token refreshed automatically.';
}).catch(function(){var note=document.getElementById('pair-refresh-note');if(note)note.textContent='Auto-refresh failed (rate limit or network) — use “Issue a fresh token”.';});
},wait);}
})();
(function(){
var panel=document.getElementById('pair-qr-panel');
if(!panel||window.__pairLiveDone)return;
if(!panel.getAttribute('data-token-hash'))return;
var delay=3000,fails=0,timer=null,done=false;
function say(t){var n=document.getElementById('pair-refresh-note');if(n)n.textContent=t;}
function stop(){done=true;if(timer){clearTimeout(timer);timer=null;}}
function schedule(ms){if(done||window.__pairLiveDone)return;timer=setTimeout(poll,ms);}
function onPaired(id,name){
window.__pairLiveDone=true;stop();
panel.style.display='none';
var p=document.createElement('p');
p.className='success';p.setAttribute('role','status');
p.appendChild(document.createTextNode('Paired '));
var b=document.createElement('strong');b.textContent=name||id;p.appendChild(b);
p.appendChild(document.createTextNode(' '));
var c=document.createElement('code');c.textContent=id;p.appendChild(c);
p.appendChild(document.createTextNode(' — no need to scan again.'));
panel.parentNode.insertBefore(p,panel.nextSibling);
fetch('/webui/pair',{headers:{'Accept':'text/html'},credentials:'same-origin'}).then(function(r){
if(!r.ok)throw new Error('list refresh failed: '+r.status);
return r.text();
}).then(function(html){
var fresh=new DOMParser().parseFromString(html,'text/html').getElementById('device-list-wrap');
var cur=document.getElementById('device-list-wrap');
if(fresh&&cur)cur.innerHTML=fresh.innerHTML;
else say('Paired — reload the page to see the updated device list.');
}).catch(function(){say('Paired — reload the page to see the updated device list.');});
}
function onGone(kind){
stop();
panel.style.display='none';
var p=document.createElement('p');
p.className='notice';p.setAttribute('role','status');
p.appendChild(document.createTextNode(kind==='expired'?'Token expired — ':'Token is no longer valid — '));
var a=document.createElement('a');a.href='/webui/pair';a.textContent='generate a new token';p.appendChild(a);
p.appendChild(document.createTextNode('.'));
panel.parentNode.insertBefore(p,panel.nextSibling);
}
function poll(){
if(done||window.__pairLiveDone)return;
var h=panel.getAttribute('data-token-hash')||'';
if(!h){stop();return;}
fetch('/webui/pair/token-status?token_hash='+encodeURIComponent(h),{headers:{'Accept':'application/json'},credentials:'same-origin'}).then(function(r){
if(r.status===429){
var retry=parseInt(r.headers.get('Retry-After')||'',10);
delay=Math.min(Math.max(isNaN(retry)?delay*2:retry*1000,5000),30000);
say('Pairing check throttled — retrying…');
schedule(delay);return null;
}
if(r.status===401){stop();say('Session expired — sign in again to keep watching for new devices.');return null;}
if(!r.ok){stop();say('Pairing check unavailable — reload the page to refresh.');return null;}
return r.json();
}).then(function(j){
if(!j)return;
if(j.status==='used'){onPaired(j.device_id,j.device_name);}
else if(j.status==='live'){delay=3000;fails=0;schedule(delay);}
else{onGone(j.status);}
}).catch(function(){
fails++;
if(fails>5){stop();say('Pairing check unavailable — reload the page to refresh.');return;}
delay=Math.min(delay*2,30000);schedule(delay);
});
}
schedule(delay);
})();
</script>
{{else}}
{{if .Error}}<p class="error" role="alert">Could not issue a pairing token: {{.Error}}</p>{{end}}
<div class="notice">
<p>No token on screen. Tokens are single-use and short-lived, so this page mints one only when you ask.</p>
<form method="post" action="/webui/pair/mint"><input type="hidden" name="csrf_token" value="{{.CSRFToken}}"><button type="submit">Generate pairing token</button></form>
</div>
{{end}}
<h2>Devices</h2>
<div id="device-list-wrap">
{{if .Devices}}
<table>
<tr><th>Name</th><th>ID</th><th>Paired</th><th>Status</th><th></th></tr>
{{range .Devices}}
<tr>
<td>{{.Name}}</td>
<td><code>{{.ID}}</code></td>
<td>{{.CreatedAt}}</td>
<td>active</td>
<td><form method="post" action="/webui/pair/revoke"><input type="hidden" name="device_id" value="{{.ID}}"><input type="hidden" name="csrf_token" value="{{$.CSRFToken}}"><button type="submit">Revoke</button></form>
<details><summary>Rename</summary>
<form method="post" action="/webui/pair/rename"><input type="hidden" name="device_id" value="{{.ID}}"><input type="hidden" name="csrf_token" value="{{$.CSRFToken}}"><input type="text" name="new_name" value="{{.Name}}" maxlength="200" required aria-label="New name"> <button type="submit" class="secondary">Save</button></form>
</details></td>
</tr>
{{end}}
</table>
{{else}}
<p>No active devices. <a href="/webui/pair">Generate a pairing token</a> above to pair your first phone.</p>
{{end}}
{{if .ShowRevoked}}
<h2>Revoked devices ({{.RevokedCount}})</h2>
{{if .Revoked}}
<table>
<tr><th>Name</th><th>ID</th><th>Paired</th><th>Status</th><th></th></tr>
{{range .Revoked}}
<tr class="revoked">
<td>{{.Name}}</td>
<td><code>{{.ID}}</code></td>
<td>{{.CreatedAt}}</td>
<td>revoked</td>
<td><form method="post" action="/webui/pair/purge"><input type="hidden" name="device_id" value="{{.ID}}"><input type="hidden" name="csrf_token" value="{{$.CSRFToken}}"><button type="submit" class="secondary">Purge</button></form>
<details><summary>Rename</summary>
<form method="post" action="/webui/pair/rename"><input type="hidden" name="device_id" value="{{.ID}}"><input type="hidden" name="csrf_token" value="{{$.CSRFToken}}"><input type="text" name="new_name" value="{{.Name}}" maxlength="200" required aria-label="New name"> <button type="submit" class="secondary">Save</button></form>
</details></td>
</tr>
{{end}}
</table>
<p><a href="/webui/pair">Hide revoked</a></p>
{{else}}
<p>No revoked devices. <a href="/webui/pair">Hide this section</a></p>
{{end}}
{{else}}
{{if .RevokedCount}}<p class="muted"><a href="/webui/pair?show=revoked">Show revoked ({{.RevokedCount}})</a></p>{{end}}
{{end}}
</div>`))

type pairPageData struct {
	// QRDataURI is a server-generated PNG data URI (template.URL: the
	// bytes come from our own QRPNG encoder, never from user input).
	QRDataURI template.URL
	RawToken  string
	// TokenHash is the SHA-256 hex of RawToken (the stored form, never the
	// raw secret in a URL). The live-pairing poll reads it from the panel's
	// data-token-hash attribute.
	TokenHash string
	// TokenPresent selects the QR panel vs. the generate-button empty
	// state. GET /webui/pair never mints, so it renders the empty state.
	TokenPresent bool
	// ExpiresAt is RFC 3339, rendered ONLY in the title tooltip (and the
	// machine-readable data attribute as epoch seconds) — never as body
	// text. Body text carries the human countdown (ExpiresHuman).
	ExpiresAt    string
	ExpiresEpoch int64
	ExpiresHuman string
	// OriginFallback warns when the QR encodes the request Host because
	// EXTERNAL_BASE_URL is unset; OriginHost names that host.
	OriginFallback bool
	OriginHost     string
	Devices        []DeviceInfo
	Revoked        []DeviceInfo
	ShowRevoked    bool
	RevokedCount   int
	Error          string
	// Notice is a one-shot operator message sourced only from a
	// whitelisted ?error= value — never raw query. RevokedFlash/PurgedFlash
	// are success flashes from server-issued redirects (?revoked=/?purged=
	// carrying the device name); template auto-escape keeps them safe and
	// they are length-capped on parse. RenamedFlash is the same shape for
	// ?renamed= after a successful rename.
	Notice       string
	RevokedFlash string
	PurgedFlash  string
	RenamedFlash string
	// ConfirmRevokeID/Name render the two-step revoke interstitial;
	// ConfirmPurgeID/Name the purge interstitial. Names come from the
	// registry (never raw query).
	ConfirmRevokeID   string
	ConfirmRevokeName string
	ConfirmPurgeID    string
	ConfirmPurgeName  string
	// CSRFToken is the session-bound token embedded in the mint, revoke,
	// and purge forms.
	CSRFToken string
}

// revokeNotice maps whitelisted ?error= values to operator text. Anything
// else renders no notice, so query input can never inject page content.
func revokeNotice(v string) string {
	switch v {
	case "device_not_found":
		return "Device not found — it may already have been revoked or purged, or the ID is stale."
	case "not_revoked":
		return "Only revoked devices can be purged — revoke it first."
	case "invalid_name":
		return "Device name must be 1–200 bytes of single-line text — please try again."
	case "csrf":
		return "Security token mismatch — please try again."
	default:
		return ""
	}
}

// flashName validates a server-issued ?revoked=/?purged= name flash:
// non-empty, capped at 200 chars (mirrors maxDeviceNameBytes). Template
// auto-escape neutralizes markup; the cap bounds layout abuse.
func flashName(v string) string {
	if v == "" || len(v) > 200 {
		return ""
	}
	return v
}

// humanExpiry renders a countdown label for the body text: whole minutes
// above 90s, seconds below. The exact timestamp stays in the title
// tooltip (ExpiresAt) — body text never carries raw UTC.
func humanExpiry(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	s := int64(d / time.Second)
	if s >= 90 {
		m := (s + 30) / 60
		if m == 1 {
			return "1 min"
		}
		return strconv.FormatInt(m, 10) + " min"
	}
	return strconv.FormatInt(s, 10) + " s"
}

// renderPairPage executes the body template inside the shared layout on
// every path (including errors), so browsers never sniff a missing type.
// Cache-Control is no-store throughout: the page embeds single-use enroll
// tokens and the operator device registry — both must never sit in a cache.
func renderPairPage(w http.ResponseWriter, status int, data pairPageData) {
	var body strings.Builder
	_ = pairBodyTmpl.Execute(&body, data)
	renderLayout(w, status, layoutData{
		Title:     "Pair a device",
		Active:    "pairing",
		SignedIn:  true,
		CSRFToken: data.CSRFToken,
		Body:      template.HTML(body.String()),
	})
}

// splitDevices partitions the registry into active rows (default view) and
// revoked rows (shown only with ?show=revoked).
func splitDevices(devices []DeviceInfo) (active, revoked []DeviceInfo) {
	for _, d := range devices {
		if d.Revoked {
			revoked = append(revoked, d)
		} else {
			active = append(active, d)
		}
	}
	return active, revoked
}

// pairBaseData lists the registry and folds the whitelisted query notices
// (?error=, ?revoked=, ?purged=, ?show=revoked) into the page data. Mint
// failures and confirm interstitials layer on top per-handler.
func pairBaseData(r *http.Request, store Store, csrf *CSRFProtection) pairPageData {
	data := pairPageData{
		Notice:       revokeNotice(r.URL.Query().Get("error")),
		RevokedFlash: flashName(r.URL.Query().Get("revoked")),
		PurgedFlash:  flashName(r.URL.Query().Get("purged")),
		RenamedFlash: flashName(r.URL.Query().Get("renamed")),
		ShowRevoked:  r.URL.Query().Get("show") == "revoked",
	}
	if store == nil {
		data.Error = "internal error"
	} else if devices, err := store.ListDevices(r.Context()); err == nil {
		data.Devices, data.Revoked = splitDevices(devices)
		data.RevokedCount = len(data.Revoked)
		if !data.ShowRevoked {
			data.Revoked = nil
		}
	} else {
		data.Error = "internal error"
	}
	if token, ok := csrfTokenForPage(csrf, r); ok {
		data.CSRFToken = token
	}
	return data
}

// HandlePairPage serves GET /webui/pair: the device registry with generate,
// revoke (two-step), and purge controls. It NEVER mints a token: the QR
// panel appears only after POST /webui/pair/mint, so plain page loads and
// refreshes cost nothing from the 30/min issuance bucket.
func HandlePairPage(store Store, ttl time.Duration, csrf *CSRFProtection) http.HandlerFunc {
	_ = ttl // TTL belongs to the mint endpoint now; kept for signature compat.
	return func(w http.ResponseWriter, r *http.Request) {
		if store == nil {
			renderPairPage(w, http.StatusInternalServerError, pairPageData{
				Error:       "internal error",
				CSRFToken:   layoutCSRFToken(csrf, r),
				ShowRevoked: r.URL.Query().Get("show") == "revoked",
			})
			return
		}
		data := pairBaseData(r, store, csrf)
		renderPairPage(w, http.StatusOK, data)
	}
}

// mintToken renders one fresh token as QR + countdown fields. Used by the
// HTML mint response and the JSON auto-refresh response alike.
func mintToken(r *http.Request, store Store, ttl time.Duration) (qrDataURI, rawToken, expiresAt string, expiresEpoch int64, err error) {
	raw, exp, ierr := store.IssueEnrollToken(r.Context(), ttl)
	if ierr != nil {
		return "", "", "", 0, ierr
	}
	payload, perr := EnrollPayloadForRequest(r, raw)
	if perr != nil {
		return "", "", "", 0, perr
	}
	png, perr := QRPNG(payload)
	if perr != nil {
		return "", "", "", 0, perr
	}
	return "data:image/png;base64," + base64.StdEncoding.EncodeToString(png),
		raw, exp.UTC().Format(time.RFC3339), exp.Unix(), nil
}

// HandlePairMint serves POST /webui/pair/mint (CSRF-guarded): mints one
// single-use enroll token (TTL supplied by the caller so there is exactly
// one TTL constant — main.EnrollTokenTTL) and renders it as a QR PNG
// (embedded data URI, no extra endpoint, no token in URLs/logs) with a
// human countdown, Host-fallback warning, copy button, and auto-refresh.
// With Accept: application/json it answers the page's auto-refresh fetch
// instead (same CSRF via X-CSRF-Token header).
func HandlePairMint(store Store, ttl time.Duration, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if store == nil {
			data := pairBaseData(r, store, csrf)
			data.Error = "internal error"
			renderPairPage(w, http.StatusInternalServerError, data)
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		if err := r.ParseForm(); err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — please try generating a token again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusForbidden,
				"Pair a device", "Security token mismatch — please reload the pairing page and try again (csrf_mismatch).",
				"/webui/pair", "Back to pairing")
			return
		}
		qr, raw, expAt, expEpoch, err := mintToken(r, store, ttl)
		if err != nil {
			data := pairBaseData(r, store, csrf)
			data.Error = "internal error"
			renderPairPage(w, http.StatusInternalServerError, data)
			return
		}
		if strings.Contains(r.Header.Get("Accept"), "application/json") {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"qr_data_uri":     qr,
				"raw_token":       raw,
				"token_hash":      tokenHashHex(raw),
				"expires_at":      expAt,
				"expires_epoch":   expEpoch,
				"origin_fallback": originFallback(),
			})
			return
		}
		data := pairBaseData(r, store, csrf)
		exp, _ := time.Parse(time.RFC3339, expAt)
		data.QRDataURI = template.URL(qr)
		data.RawToken = raw
		data.TokenHash = tokenHashHex(raw)
		data.TokenPresent = true
		data.ExpiresAt = expAt
		data.ExpiresEpoch = expEpoch
		data.ExpiresHuman = humanExpiry(time.Until(exp))
		data.OriginFallback = originFallback()
		data.OriginHost = r.Host
		renderPairPage(w, http.StatusOK, data)
	}
}

// tokenHashHex is the SHA-256 hex of a raw enroll token — the same digest
// the server stores (main.sha256Hex, unexported there). Embedded in the QR
// panel so the live-pairing poll can reference the token without ever
// putting the raw secret in a URL or log line.
func tokenHashHex(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

// isHexTokenHash reports whether s looks like a SHA-256 hex digest (64
// lowercase hex chars). The poll handler lowercases input first, so only
// the lowercase range is accepted here.
func isHexTokenHash(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

// HandleTokenStatus serves GET /webui/pair/token-status?token_hash=<hex>:
// the lightweight live-pairing poll behind the QR panel. Read-only, so no
// CSRF token is required (same as GET /api/v1/devices); session gating is
// applied by the caller (RequireAdmin: anonymous callers get 303 to
// /login, never JSON). Cache-Control is no-store: token state is
// single-use and credential-adjacent.
//
// Malformed hashes answer 400 JSON; unknown hashes answer 200
// {"status":"unknown"} so a pruned row simply stops the page poll instead
// of erroring it.
func HandleTokenStatus(store Store) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		h := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("token_hash")))
		if !isHexTokenHash(h) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusBadRequest)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "invalid_request"})
			return
		}
		if store == nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusInternalServerError)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "internal"})
			return
		}
		st, err := store.TokenStatus(r.Context(), h)
		if err != nil {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusInternalServerError)
			_ = json.NewEncoder(w).Encode(map[string]string{"error": "internal"})
			return
		}
		if st.Status != TokenLive && st.Status != TokenUsed && st.Status != TokenExpired {
			st.Status = TokenUnknown
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(st)
	}
}

// HandlePairRename serves POST /webui/pair/rename (CSRF-guarded, same
// pattern as revoke/purge): renames one registry row (active or revoked)
// and redirects with ?renamed=<name>. Validation failures redirect with
// ?error=invalid_name and unknown IDs with ?error=device_not_found, so the
// follow-up GET renders every outcome in-page with nav intact (PRG: no
// resubmit, no dead ends). Idempotent: renaming to the current name
// succeeds.
func HandlePairRename(store Store, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Renaming changes operator state; never cache the outcome.
		w.Header().Set("Cache-Control", "no-store")
		if store == nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		if err := r.ParseForm(); err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusForbidden,
				"Pair a device", "Security token mismatch — please reload the pairing page and try again (csrf_mismatch).",
				"/webui/pair", "Back to pairing")
			return
		}
		id := strings.TrimSpace(r.FormValue("device_id"))
		if id == "" {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — missing device id.",
				"/webui/pair", "Back to pairing")
			return
		}
		name, err := ValidateDeviceName(r.FormValue("new_name"))
		if err != nil {
			http.Redirect(w, r, "/webui/pair?error=invalid_name", http.StatusSeeOther)
			return
		}
		found, err := store.RenameDevice(r.Context(), id, name)
		if err != nil {
			// The store revalidates defensively: an invalid name from any
			// future caller maps back to the operator error, not a 500.
			if errors.Is(err, ErrInvalidDeviceName) {
				http.Redirect(w, r, "/webui/pair?error=invalid_name", http.StatusSeeOther)
				return
			}
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if !found {
			http.Redirect(w, r, "/webui/pair?error=device_not_found", http.StatusSeeOther)
			return
		}
		http.Redirect(w, r, "/webui/pair?renamed="+url.QueryEscape(name), http.StatusSeeOther)
	}
}

func csrfTokenForPage(csrf *CSRFProtection, r *http.Request) (string, bool) {
	if csrf == nil {
		return "", false
	}
	return csrf.TokenForRequest(r)
}

// lookupDeviceName resolves id against the registry for confirm pages and
// success flashes. Reports (name, revoked, found, err).
func lookupDeviceName(store Store, r *http.Request, id string) (string, bool, bool, error) {
	devices, err := store.ListDevices(r.Context())
	if err != nil {
		return "", false, false, err
	}
	for _, d := range devices {
		if d.ID == id {
			name := d.Name
			if name == "" {
				name = id
			}
			return name, d.Revoked, true, nil
		}
	}
	return "", false, false, nil
}

// HandlePairRevoke serves POST /webui/pair/revoke as a two-step confirm:
// the first POST (device_id only) renders an interstitial naming the
// device; the second POST (device_id + confirm=yes) revokes and redirects
// with ?revoked=<name>. Unknown IDs redirect with ?error=device_not_found
// instead of a silent no-op.
func HandlePairRevoke(store Store, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Revocation changes operator state; never cache the outcome.
		w.Header().Set("Cache-Control", "no-store")
		if store == nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		if err := r.ParseForm(); err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusForbidden,
				"Pair a device", "Security token mismatch — please reload the pairing page and try again (csrf_mismatch).",
				"/webui/pair", "Back to pairing")
			return
		}
		id := strings.TrimSpace(r.FormValue("device_id"))
		if id == "" {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — missing device id.",
				"/webui/pair", "Back to pairing")
			return
		}
		if strings.TrimSpace(r.FormValue("confirm")) != "yes" {
			name, _, found, err := lookupDeviceName(store, r, id)
			if err != nil {
				renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
					"Pair a device", "Internal error — please try again.",
					"/webui/pair", "Back to pairing")
				return
			}
			if !found {
				http.Redirect(w, r, "/webui/pair?error=device_not_found", http.StatusSeeOther)
				return
			}
			data := pairBaseData(r, store, csrf)
			data.ConfirmRevokeID, data.ConfirmRevokeName = id, name
			renderPairPage(w, http.StatusOK, data)
			return
		}
		name, _, found, err := lookupDeviceName(store, r, id)
		if err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if !found {
			http.Redirect(w, r, "/webui/pair?error=device_not_found", http.StatusSeeOther)
			return
		}
		found, err = store.RevokeDevice(r.Context(), id)
		if err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if !found {
			http.Redirect(w, r, "/webui/pair?error=device_not_found", http.StatusSeeOther)
			return
		}
		http.Redirect(w, r, "/webui/pair?revoked="+url.QueryEscape(name), http.StatusSeeOther)
	}
}

// HandlePairPurge serves POST /webui/pair/purge as a two-step confirm for
// REVOKED rows only: the first POST renders an interstitial, the second
// hard-deletes the row and redirects with ?purged=<name>. Active rows are
// refused (?error=not_revoked); unknown IDs report ?error=device_not_found.
// Purging only removes the registry row — the device's credentials already
// stopped working at revoke time, and unknown tokens 401 exactly like
// revoked ones, so purge is safe and revoke stays idempotent.
func HandlePairPurge(store Store, csrf *CSRFProtection) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		if store == nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		if err := r.ParseForm(); err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if csrf == nil || !csrf.VerifyRequest(r) {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusForbidden,
				"Pair a device", "Security token mismatch — please reload the pairing page and try again (csrf_mismatch).",
				"/webui/pair", "Back to pairing")
			return
		}
		id := strings.TrimSpace(r.FormValue("device_id"))
		if id == "" {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusBadRequest,
				"Pair a device", "Bad request — missing device id.",
				"/webui/pair", "Back to pairing")
			return
		}
		name, revoked, found, err := lookupDeviceName(store, r, id)
		if err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if !found {
			http.Redirect(w, r, "/webui/pair?show=revoked&error=device_not_found", http.StatusSeeOther)
			return
		}
		if !revoked {
			http.Redirect(w, r, "/webui/pair?error=not_revoked", http.StatusSeeOther)
			return
		}
		if strings.TrimSpace(r.FormValue("confirm")) != "yes" {
			data := pairBaseData(r, store, csrf)
			data.ShowRevoked = true
			if _, revokedRows := splitDevices(mustListDevices(store, r)); revokedRows != nil {
				data.Revoked = revokedRows
				data.RevokedCount = len(revokedRows)
			}
			data.ConfirmPurgeID, data.ConfirmPurgeName = id, name
			renderPairPage(w, http.StatusOK, data)
			return
		}
		found, err = store.PurgeDevice(r.Context(), id)
		if err != nil {
			renderOperatorError(w, r, csrf, true, "pairing", http.StatusInternalServerError,
				"Pair a device", "Internal error — please try again.",
				"/webui/pair", "Back to pairing")
			return
		}
		if !found {
			http.Redirect(w, r, "/webui/pair?show=revoked&error=device_not_found", http.StatusSeeOther)
			return
		}
		http.Redirect(w, r, "/webui/pair?show=revoked&purged="+url.QueryEscape(name), http.StatusSeeOther)
	}
}

// mustListDevices returns the registry for confirm-page rendering, tolerating
// lookup failure with an empty slice (the confirm form carries no device
// table state that must be exact).
func mustListDevices(store Store, r *http.Request) []DeviceInfo {
	if store == nil {
		return nil
	}
	devices, err := store.ListDevices(r.Context())
	if err != nil {
		return nil
	}
	return devices
}
