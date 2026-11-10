# syntax=docker/dockerfile:1
# Build context is backend/ itself (Coolify base directory: /backend).
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/pb .

FROM alpine:3.24
# su-exec lets the entrypoint drop from root to the app user after it has
# re-owned the data volume.
RUN apk add --no-cache ca-certificates tzdata wget su-exec \
  && addgroup -S -g 10001 vectra \
  && adduser -S -D -H -u 10001 -G vectra -h /app -s /sbin/nologin vectra
WORKDIR /app
COPY --from=build /out/pb /app/pb
COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod 0755 /app/pb /app/docker-entrypoint.sh \
  && mkdir -p /app/pb_data \
  && chown vectra:vectra /app/pb_data
# pb_data is the persistent volume mount point
VOLUME ["/app/pb_data"]
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget -qO- http://127.0.0.1:8090/api/health || exit 1
# The entrypoint starts as root, fixes pb_data ownership, then runs the server
# as `vectra` (uid 10001). Nothing else in the container runs as root.
ENTRYPOINT ["/app/docker-entrypoint.sh"]
CMD ["serve", "--http=0.0.0.0:8090", "--dir=/app/pb_data"]
