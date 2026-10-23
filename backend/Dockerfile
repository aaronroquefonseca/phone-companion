# Phone Companion backend — multi-stage build.
#
# Builder: full Go toolchain. Final: distroless static (non-root user
# built in) carrying only the static binary. No shell, no package manager.

FROM golang:1.26-bookworm AS builder

WORKDIR /src

# Module deps first for layer caching.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
# Static binary (CGO off; sqlite driver is pure Go).
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/phone-companion .
# Writable state dirs. Copied --chown below so the non-root runtime user can
# write; named volumes inherit this ownership on first mount.
RUN mkdir -p /out/data /out/widgets

# --- Runtime ---------------------------------------------------------------
FROM gcr.io/distroless/static-debian12:nonroot

# $PORT with 8080 default (see config.go). Distroless :nonroot already runs
# as a non-root user; do not switch back to root.
ENV PORT=8080 \
    DB_PATH=/data/phone-companion.db \
    WIDGET_DIR=/widgets

VOLUME ["/data", "/widgets"]

# EXPOSE documents the default container port only; it is not a publish
# rule. The app listens on $PORT (default 8080, see config.go). If PORT is
# overridden, publish that port instead (see docker-compose.example.yml).
EXPOSE 8080

# No HEALTHCHECK: the distroless runtime has no shell, so CMD-SHELL probes
# cannot run. Probe GET /healthz from the orchestrator or reverse proxy
# instead (see backend/README.md).

COPY --from=builder /out/phone-companion /phone-companion
COPY --from=builder --chown=65532:65532 /out/data /data
COPY --from=builder --chown=65532:65532 /out/widgets /widgets

ENTRYPOINT ["/phone-companion"]
