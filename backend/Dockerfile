# syntax=docker/dockerfile:1
# Build context is backend/ itself (Coolify base directory: /backend).
FROM golang:1.27-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" -o /out/pb .

FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata wget
WORKDIR /app
COPY --from=build /out/pb /app/pb
# pb_data is the persistent volume mount point
VOLUME ["/app/pb_data"]
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s \
  CMD wget -qO- http://127.0.0.1:8090/api/health || exit 1
ENTRYPOINT ["/app/pb"]
CMD ["serve", "--http=0.0.0.0:8090", "--dir=/app/pb_data"]
