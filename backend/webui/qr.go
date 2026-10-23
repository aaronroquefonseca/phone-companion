package webui

import (
	"fmt"

	qrcode "github.com/skip2/go-qrcode"
)

// QRPNG renders payload as a PNG QR code. The qrcode dependency is pure Go
// (no cgo), keeping the static distroless build intact.
func QRPNG(payload string) ([]byte, error) {
	if payload == "" {
		return nil, fmt.Errorf("empty QR payload")
	}
	return qrcode.Encode(payload, qrcode.Medium, 256)
}
