package main

import (
	"context"
	"net/http"
	"time"

	"github.com/aaronroquefonseca/phone-companion/backend/webui"
)

// RegisterPairingRoutes wires the P2 pairing surfaces. POST /api/v1/enroll
// is authorized by the single-use enroll token (QR payload); every other
// route here is admin-only (currently requireAdminPairingStub — see
// pair_admin_stub.go for the Wave-2 P8 reconciliation TODO).
func RegisterPairingRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/v1/enroll", HandleEnroll)
	mux.HandleFunc("POST /api/v1/enroll-tokens", requireAdminPairingStub(http.HandlerFunc(HandleIssueEnrollToken)).ServeHTTP)
	mux.HandleFunc("GET /api/v1/devices", requireAdminPairingStub(http.HandlerFunc(HandleListDevices)).ServeHTTP)
	mux.HandleFunc("DELETE /api/v1/devices/{id}", requireAdminPairingStub(http.HandlerFunc(HandleRevokeDevice)).ServeHTTP)

	store := webuiStoreAdapter{}
	mux.HandleFunc("GET /webui/pair", requireAdminPairingStub(webui.HandlePairPage(store)).ServeHTTP)
	mux.HandleFunc("POST /webui/pair/revoke", requireAdminPairingStub(webui.HandlePairRevoke(store)).ServeHTTP)
}

// webuiStoreAdapter implements webui.Store against the main package's
// pairing store. It lives here (not in devices.go) to keep the webui glue
// in one place.
type webuiStoreAdapter struct{}

func (webuiStoreAdapter) IssueEnrollToken(_ context.Context, ttl time.Duration) (string, time.Time, error) {
	return IssueEnrollToken(deviceDB, ttl)
}

func (webuiStoreAdapter) ListDevices(_ context.Context) ([]webui.DeviceInfo, error) {
	devices, err := ListDevices(deviceDB)
	if err != nil {
		return nil, err
	}
	out := make([]webui.DeviceInfo, 0, len(devices))
	for _, d := range devices {
		out = append(out, webui.DeviceInfo{
			ID:        d.ID,
			Name:      d.Name,
			CreatedAt: d.CreatedAt,
			Revoked:   d.Revoked,
			RevokedAt: d.RevokedAt,
		})
	}
	return out, nil
}

func (webuiStoreAdapter) RevokeDevice(_ context.Context, id string) (bool, error) {
	return RevokeDevice(deviceDB, id)
}
