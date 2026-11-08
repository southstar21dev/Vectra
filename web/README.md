# Vectra — web app

Vectra is a personal companion app: your timetable, the buses and trams around you, the weather, and notifications that actually arrive, all in one place. It runs as a PWA in the browser.

## What's inside

- **Timetable**: import your Neptun schedule (ICS or CSV) and swipe through your week, with day and week views.
- **Live transit**: real-time vehicle positions and arrival times for Budapest public transport, with nearby stops and route maps.
- **Weather**: current conditions and alerts for where you are.
- **Notifications**: live departure alerts that work in the browser and on the app, even when it's closed.
- **Biometric sign-in**: fingerprint, Face ID, or Windows Hello via passkeys, with email/password as the fallback.
- **Private by default**: sensitive data is encrypted at rest with a key that only ever lives on the server. The one exception in transit: your Neptun calendar link is fetched through a Google Apps Script proxy (Corvinus blocks cloud servers), so the link itself passes through Google when your timetable syncs.

## Built with

React 19, TypeScript, Vite, Tailwind CSS, Framer Motion, TanStack Query, and PocketBase.

## Development

The web app is the Vite client in this folder; it talks to the Go PocketBase backend in `../backend/`. Run both.

```bash
# backend (terminal 1) — builds and serves on 127.0.0.1:8090
cd backend && make run

# frontend (terminal 2)
cd web
npm install
npm run dev
```

Point the client at the local backend with `VITE_POCKETBASE_URL=http://127.0.0.1:8090` in `.env.local`. In production the backend is served at **`https://apivectra.southstar21.dev`**; the PWA stays at `https://vectra.southstar21.dev`.

Add the vars below to `.env.local`. Without them the app won't work: you won't be able to log in, live transit will error, and push notifications won't arrive.

| Variable | Needed for | Notes |
| --- | --- | --- |
| `VITE_POCKETBASE_URL` | Auth, database, custom routes | Required; the PocketBase backend URL (e.g. `http://127.0.0.1:8090` in dev) |
| `VITE_VAPID_PUBLIC_KEY` | Web Push notifications in the browser/PWA | Required for push; pair of `VAPID_PRIVATE_KEY` on the backend |
| `VITE_CARTO_API_KEY` | CARTO map tiles (Leaflet basemaps) | Optional; removes "API KEY REQUIRED" watermark (free tier at carto.com/basemaps/apikey) |
| `VITE_APP_VERSION` | Version shown in Settings → About | Optional; defaults to `package.json` |

The PocketBase backend (`../backend/`) reads its own secrets from the environment: `FUTAR_API_KEY` and `NEPTUN_ENCRYPTION_KEY`, `VAPID_PUBLIC_KEY` + `VAPID_PRIVATE_KEY` (+ `VAPID_SUBJECT`) for Web Push, and the WebAuthn RP settings.

## Checks

```bash
npm run lint        # oxlint
npm run typecheck   # tsc -b (build also runs this)
npm test            # vitest, 962 tests
npm run test:coverage
cd ../backend && go test ./...   # Go backend tests (`make -C backend test` adds `go vet`)
```
CI (`.github/workflows/ci.yml`) runs all of these on every PR and before every release (see the root README).

## Architecture

- `src/features/<feature>/` — one folder per product area (auth, schedule, transit, trips, weather, …). Server data goes through TanStack Query hooks; `api/`/`utils/` modules hold pure logic with unit tests next to them.
- `src/lib/` — cross-cutting helpers: `pocketbase.ts` (the typed client), `encryption.ts` (AES-GCM via the `crypto` backend route), `offline-cache.ts` (per-user localStorage caches), `map-tiles.ts`, `query-client.ts`.
- `src/components/ui/` — shared primitives (Button, Dialog, Select, …).
- `../backend/` — the Go PocketBase backend: schema migrations (`migrations/`), custom `/api/vectra/*` routes (`internal/`), and the embedded PocketBase server.

## Backend deploy (Coolify)

The backend runs as a Coolify/Docker resource that builds `backend/Dockerfile` (base directory `/backend`) and auto-deploys on every push to `master`, served at **`https://apivectra.southstar21.dev`**. The SQLite database and uploads live on a persistent `/app/pb_data` volume; secrets are env-only.

## Web deploy (Docker)

The Vite client vars are compile-time constants and **must** be passed as build args; the Dockerfile fails fast if `VITE_POCKETBASE_URL` is missing:

```bash
docker build \
  --build-arg VITE_POCKETBASE_URL="$VITE_POCKETBASE_URL" \
  --build-arg VITE_CARTO_API_KEY="$VITE_CARTO_API_KEY" \
  --build-arg VITE_VAPID_PUBLIC_KEY="$VITE_VAPID_PUBLIC_KEY" \
  -t vectra web/
```

The image is nginx serving `dist/` with SPA fallback, gzip and baseline security headers (`nginx.conf`). Rollback = redeploy the previous image tag; no database state is shipped with it.

## Database migrations

Schema lives in `backend/migrations/*.go` and is applied automatically when the PocketBase binary starts (each migration is idempotent). The client types are generated from the running schema:

```bash
npx pocketbase-typegen --db ../backend/pb_data/data.db --out src/types/pocketbase-types.ts
```

Run the server once so `backend/pb_data/data.db` exists before regenerating.

## Backend

The backend is a single Go binary in `backend/` that embeds PocketBase and registers the `/api/vectra/*` custom routes (crypto, futar, push, account, neptun, passkeys). Build and run it locally with `make -C backend run` (no Docker required). Deployment ships the binary; there is no separate edge-function runtime.

## Transit catalog

The full BKK line/stop catalog lives in `public/catalog/*.json`
(`routes.json`, `stops.json`, `route-variants.json`), generated from the BKK
Futár feed by `scripts/build-catalog.mjs` and refreshed daily by
`.github/workflows/refresh-catalog.yml` (on master and dev). The app fetches it at runtime (it is
not bundled and not precached), so daily data refreshes never change the JS
bundle or the service worker — installed PWAs pick up the current catalog
without an update reload. Do not hand-edit the JSON. Regenerate locally with
`FUTAR_API_KEY=… node scripts/build-catalog.mjs`; the workflow keeps the
previous catalog when the upstream feed degrades.

