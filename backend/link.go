package main

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/notify"
	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// The fast path: a persistent, device-initiated stream instead of the
// poll/FCM outbox.
//
// The app holds GET /api/v1/device/link open; the server writes one NDJSON
// command down it and the device answers with a raw-body POST to
// /api/v1/device/result/{op}. The operator side is a single blocking call:
// POST /api/v1/exec returns the bytes. That is one HTTP round trip for the
// agent and one for the device, with no FCM wake, no 64 KB text cap and no
// base64.
//
// Results are held in memory only, and only until the waiting caller
// collects them (or the op expires), because they are usually screenshots
// and photos that nobody wants duplicated on disk.

const (
	linkPingInterval = 15 * time.Second
	linkWriteBuffer  = 64
	execWaitDefault  = 15 * time.Second
	execWaitMax      = 120 * time.Second
	resultMaxBytes   = 256 << 20 // safety valve, not a product limit
	resultTTL        = 60 * time.Second
)

type opResult struct {
	contentType string
	body        []byte
	status      int
}

type pendingOp struct {
	ch       chan opResult
	expires  time.Time
	deviceID string
}

type deviceLink struct {
	send chan []byte
	done chan struct{}
}

type linkHub struct {
	mu      sync.Mutex
	links   map[string]*deviceLink
	pending map[string]*pendingOp
}

func newLinkHub() *linkHub {
	return &linkHub{
		links:   make(map[string]*deviceLink),
		pending: make(map[string]*pendingOp),
	}
}

func newOpID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(b[:]), nil
}

// attach registers a device's stream, replacing any previous one (a
// reconnect wins; the old stream is closed so the app stops reading it).
func (h *linkHub) attach(deviceID string) *deviceLink {
	l := &deviceLink{
		send: make(chan []byte, linkWriteBuffer),
		done: make(chan struct{}),
	}
	h.mu.Lock()
	old := h.links[deviceID]
	h.links[deviceID] = l
	h.mu.Unlock()
	if old != nil {
		close(old.done)
	}
	return l
}

func (h *linkHub) detach(deviceID string, l *deviceLink) {
	h.mu.Lock()
	if h.links[deviceID] == l {
		delete(h.links, deviceID)
	}
	h.mu.Unlock()
	close(l.done)
}

func (h *linkHub) isOnline(deviceID string) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	_, ok := h.links[deviceID]
	return ok
}

// dispatch queues one command for a device. False when no link is up, so
// the caller can answer "offline" instead of waiting for the timeout.
func (h *linkHub) dispatch(deviceID string, payload []byte) bool {
	h.mu.Lock()
	l := h.links[deviceID]
	h.mu.Unlock()
	if l == nil {
		return false
	}
	select {
	case l.send <- payload:
		return true
	case <-time.After(2 * time.Second):
		return false
	}
}

func (h *linkHub) addPending(opID, deviceID string) *pendingOp {
	p := &pendingOp{ch: make(chan opResult, 1), expires: time.Now().Add(resultTTL), deviceID: deviceID}
	h.mu.Lock()
	h.pending[opID] = p
	h.mu.Unlock()
	return p
}

func (h *linkHub) deliver(opID, deviceID string, r opResult) bool {
	h.mu.Lock()
	p := h.pending[opID]
	if p != nil && p.deviceID != deviceID {
		p = nil
	}
	delete(h.pending, opID)
	h.mu.Unlock()
	if p == nil {
		return false
	}
	select {
	case p.ch <- r:
		return true
	default:
		return false
	}
}

func (h *linkHub) dropPending(opID string) {
	h.mu.Lock()
	delete(h.pending, opID)
	h.mu.Unlock()
}

// registerLinkRoutes wires the device stream/result endpoints (device
// bearer) and the operator-side exec call (operator gate).
func registerLinkRoutes(mux *http.ServeMux, db *sql.DB, sessions webui.SessionStore, csrf *webui.CSRFProtection, sender notify.Sender, hub *linkHub) {
	mux.HandleFunc("GET /api/v1/device/link", handleDeviceLink(db, hub))
	mux.HandleFunc("POST /api/v1/device/result/{op_id}", handleDeviceResult(db, hub))
	mux.Handle("POST /api/v1/exec", operatorAdminChain(sessions, csrf, handleExec(db, hub)))
	mux.Handle("POST /api/v1/link/start", operatorAdminChain(sessions, csrf, handleLinkStart(db, sender, hub)))
	mux.Handle("POST /api/v1/link/stop", operatorAdminChain(sessions, csrf, handleLinkStop(db, hub)))
}

// handleLinkStart opens a session lease: the device holds the fast link
// for ttl_s seconds even if the user's always-on toggle is off. The only
// reliable way to reach a sleeping app is the FCM wake, so that is the
// supported path; no sender means 503, not a silent no-op.
func handleLinkStart(db *sql.DB, sender notify.Sender, hub *linkHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			DeviceID string `json:"device_id"`
			TTLS     int    `json:"ttl_s"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		req.DeviceID = strings.TrimSpace(req.DeviceID)
		if req.DeviceID == "" {
			writeAPIError(w, http.StatusUnprocessableEntity, "missing_device_id")
			return
		}
		if _, ok, err := lookupActiveDevice(r, db, req.DeviceID); err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		} else if !ok {
			writeAPIError(w, http.StatusNotFound, "device_not_found")
			return
		}
		ttl := req.TTLS
		if ttl <= 0 {
			ttl = 900
		}
		if ttl < 30 {
			ttl = 30
		}
		if ttl > 3600 {
			ttl = 3600
		}
		if hub.isOnline(req.DeviceID) {
			// Already warm: just extend the lease server-side view.
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"online": true, "ttl_s": ttl})
			return
		}
		if _, isFCM := sender.(*notify.FCMSender); !isFCM {
			writeAPIError(w, http.StatusServiceUnavailable, "wake_unavailable")
			return
		}
		var token string
		if err := db.QueryRow(`SELECT COALESCE(fcm_token,'') FROM devices WHERE id = ?`, req.DeviceID).Scan(&token); err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		if strings.TrimSpace(token) == "" {
			writeAPIError(w, http.StatusServiceUnavailable, "wake_unavailable")
			return
		}
		payload := map[string]string{"pc_link_ttl": fmt.Sprint(ttl), "type": "link_start"}
		if err := sender.Send(r.Context(), notify.Device{ID: req.DeviceID, FCMToken: token}, payload); err != nil {
			log.Printf("link: start wake failed device=%s: %v", deviceLogID(req.DeviceID), err)
			writeAPIError(w, http.StatusBadGateway, "wake_failed")
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"online": false, "ttl_s": ttl, "wake_sent": true})
	}
}

// handleLinkStop closes the session now instead of waiting for the lease.
// It goes down the link itself when the device is online.
func handleLinkStop(db *sql.DB, hub *linkHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			DeviceID string `json:"device_id"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 8<<10)
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		req.DeviceID = strings.TrimSpace(req.DeviceID)
		if req.DeviceID == "" {
			writeAPIError(w, http.StatusUnprocessableEntity, "missing_device_id")
			return
		}
		if !hub.isOnline(req.DeviceID) {
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{"online": false})
			return
		}
		opID, _ := newOpID()
		payload, _ := json.Marshal(map[string]any{"op_id": opID, "kind": "link_stop", "args": map[string]any{}})
		hub.dispatch(req.DeviceID, payload)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"online": true, "stopping": true})
	}
}

func handleDeviceLink(db *sql.DB, hub *linkHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := authenticateDeviceCaller(w, r, db)
		if !ok {
			return
		}
		flusher, ok := w.(http.Flusher)
		if !ok {
			writeAPIError(w, http.StatusInternalServerError, "streaming_unsupported")
			return
		}
		w.Header().Set("Content-Type", "application/x-ndjson")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusOK)
		flusher.Flush()

		link := hub.attach(dev.ID)
		defer hub.detach(dev.ID, link)
		log.Printf("link: attached device=%s", deviceLogID(dev.ID))

		ticker := time.NewTicker(linkPingInterval)
		defer ticker.Stop()
		ctx := r.Context()
		for {
			select {
			case <-ctx.Done():
				return
			case <-link.done:
				return
			case payload := <-link.send:
				if _, err := w.Write(append(payload, '\n')); err != nil {
					return
				}
				flusher.Flush()
			case <-ticker.C:
				if _, err := w.Write([]byte("{\"t\":\"ping\"}\n")); err != nil {
					return
				}
				flusher.Flush()
			}
		}
	}
}

func handleDeviceResult(db *sql.DB, hub *linkHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		dev, ok := authenticateDeviceCaller(w, r, db)
		if !ok {
			return
		}
		opID := strings.TrimSpace(r.PathValue("op_id"))
		if opID == "" {
			writeAPIError(w, http.StatusBadRequest, "missing_op")
			return
		}
		body, err := io.ReadAll(io.LimitReader(r.Body, resultMaxBytes+1))
		if err != nil {
			writeAPIError(w, http.StatusBadRequest, "read_failed")
			return
		}
		if len(body) > resultMaxBytes {
			writeAPIError(w, http.StatusRequestEntityTooLarge, "result_too_large")
			return
		}
		ct := strings.TrimSpace(r.Header.Get("X-Result-Type"))
		if ct == "" {
			ct = "application/octet-stream"
		}
		status := http.StatusOK
		if s := strings.TrimSpace(r.Header.Get("X-Result-Status")); s != "" {
			if n, perr := atoiSafe(s); perr == nil && n >= 400 && n <= 599 {
				status = n
			}
		}
		delivered := hub.deliver(opID, dev.ID, opResult{contentType: ct, body: body, status: status})
		if !delivered {
			// Unknown, expired, or already collected: 200 so the device can
			// move on without retrying forever.
			log.Printf("link: result dropped op=%s device=%s bytes=%d", opID[:8], deviceLogID(dev.ID), len(body))
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{"stored": delivered})
	}
}

func handleExec(db *sql.DB, hub *linkHub) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			DeviceID string          `json:"device_id"`
			Kind     string          `json:"kind"`
			Args     json.RawMessage `json:"args"`
			WaitMS   int             `json:"wait_ms"`
		}
		r.Body = http.MaxBytesReader(w, r.Body, 64<<10)
		if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
			writeAPIError(w, http.StatusBadRequest, "invalid_json")
			return
		}
		req.DeviceID = strings.TrimSpace(req.DeviceID)
		req.Kind = strings.TrimSpace(req.Kind)
		switch req.Kind {
		case "screenshot", "file", "logs", "shell":
		default:
			writeAPIError(w, http.StatusUnprocessableEntity, "unknown_kind")
			return
		}
		if _, ok, lerr := lookupActiveDevice(r, db, req.DeviceID); lerr != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		} else if !ok {
			writeAPIError(w, http.StatusNotFound, "device_not_found")
			return
		}
		if !hub.isOnline(req.DeviceID) {
			writeAPIError(w, http.StatusServiceUnavailable, "device_offline")
			return
		}
		opID, err := newOpID()
		if err != nil {
			writeAPIError(w, http.StatusInternalServerError, "internal")
			return
		}
		wait := execWaitDefault
		if req.WaitMS > 0 {
			ms := req.WaitMS
			if ms > int(execWaitMax/time.Millisecond) {
				ms = int(execWaitMax / time.Millisecond)
			}
			wait = time.Duration(ms) * time.Millisecond
		}
		args := req.Args
		if len(args) == 0 {
			args = json.RawMessage(`{}`)
		}
		payload, _ := json.Marshal(map[string]any{
			"op_id": opID,
			"kind":  req.Kind,
			"args":  args,
		})
		pending := hub.addPending(opID, req.DeviceID)
		defer hub.dropPending(opID)
		if !hub.dispatch(req.DeviceID, payload) {
			writeAPIError(w, http.StatusServiceUnavailable, "device_offline")
			return
		}
		select {
		case res := <-pending.ch:
			w.Header().Set("Content-Type", res.contentType)
			w.Header().Set("Cache-Control", "no-store")
			w.WriteHeader(res.status)
			_, _ = w.Write(res.body)
		case <-time.After(wait):
			writeAPIError(w, http.StatusGatewayTimeout, "device_timeout")
		case <-r.Context().Done():
		}
	}
}

// atoiSafe avoids strconv in the hot path's error handling.
func atoiSafe(s string) (int, error) {
	if s == "" {
		return 0, errors.New("empty")
	}
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, errors.New("not a number")
		}
		n = n*10 + int(c-'0')
	}
	return n, nil
}
