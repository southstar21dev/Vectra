# Dockerfile (multi-stage)
#
# The Vite client vars are compile-time constants, so they must arrive as build
# args — passing them only as runtime env would leave the bundle pointing at
# the local-dev fallback URL. Required:
#   --build-arg VITE_POCKETBASE_URL=...
# Optional: VITE_CARTO_API_KEY, VITE_VAPID_PUBLIC_KEY, VITE_APP_VERSION.
FROM node:22-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .

ARG VITE_POCKETBASE_URL
ARG VITE_CARTO_API_KEY
ARG VITE_VAPID_PUBLIC_KEY
ARG VITE_APP_VERSION

# Fail fast instead of shipping a build that silently falls back to the
# local-dev URL http://127.0.0.1:8090 (see src/lib/pocketbase.ts).
RUN test -n "$VITE_POCKETBASE_URL" || \
    (echo "ERROR: VITE_POCKETBASE_URL build arg is required." >&2 && exit 1)

ENV VITE_POCKETBASE_URL=$VITE_POCKETBASE_URL \
    VITE_CARTO_API_KEY=$VITE_CARTO_API_KEY \
    VITE_VAPID_PUBLIC_KEY=$VITE_VAPID_PUBLIC_KEY \
    VITE_APP_VERSION=$VITE_APP_VERSION

RUN npm run build

FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
