# Vectra backend

PocketBase with Vectra's migrations and `/api/vectra/*` routes. `make test` runs `go vet` and the
tests; `docker compose up --build` runs a local instance with placeholder secrets.

## Environments

| | Production | Dev |
|---|---|---|
| URL | `https://apivectra.southstar21.dev` | `https://apivectra-dev.southstar21.dev` |
| Coolify branch | `master` (auto-deploy) | `dev` (auto-deploy) |
| Used by | release builds of the apps, the web PWA | debug builds of the apps (`dev.southstar21.vectra.debug`, "Vectra Dev") |
| Data | real users | the test account only |

The native apps choose by build type (`native/androidApp/build.gradle.kts`,
`native/shared/build.gradle.kts`); `-Pvectra.pocketbaseUrl=…` overrides both. New backend code
reaches dev on every push to `dev`, so migrations run there before they reach production.

## Environment variables (the reference)

`internal/config` holds the same list (`config.Vars`) and checks it when the server starts: a missing
required variable is logged as `config: ERROR ...`, an optional one as a warning, and a variable with
one of our prefixes that nothing reads (for example `WEBAUTHN_RP_ORIGIN`) as a probable typo. Set
`VECTRA_STRICT_CONFIG=1` to make a missing required variable abort the start instead of only logging.
Add a new setting to `config.Vars` and to this table together. Without a variable, the route that
needs it answers 500 and `/api/health` stays green.

| Variable | Required | Used by | Production value | Dev value (Coolify) |
|---|---|---|---|---|
| `NEPTUN_ENCRYPTION_KEY` | yes | `/crypto`, `/neptun/replace`, old `vc2:` data upgrade | the production secret (`openssl rand -hex 32`) | a new random value, never production's |
| `FUTAR_API_KEY` | yes | `/futar`, the transit catalog builder | the BKK Futár key | production's key is fine |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | yes | `/push/send` | the production pair | a new pair |
| `VAPID_SUBJECT` | no | `/push/send` | a `mailto:` address; defaults to a placeholder | `mailto:support@southstar21.dev` |
| `WEBAUTHN_RP_ID` | yes | passkeys | `vectra.southstar21.dev` | `vectra.southstar21.dev` |
| `WEBAUTHN_RP_ORIGINS` | yes | passkeys, comma-separated | the web origin plus the `android:apk-key-hash:` origin of the release signing key | web origin plus the debug key hash |
| `WEBAUTHN_RP_DISPLAY_NAME` | yes | passkeys | the app name shown in the passkey prompt | `Vectra Dev` |
| `CATALOG_BUILD` | no | the transit catalog builder | unset (builds daily); `0` turns the builder off | unset |
| `TRUSTED_PROXY` | no | client IP for rate limits | `cloudflare` | `cloudflare` |
| `VECTRA_STRICT_CONFIG` | no | boot check above | `1` once the list is verified | unset |

`docker-compose.yml` sets every variable to a dev placeholder for a local instance.

## Transit catalog (`/catalog/*.json`)

The backend builds and serves the BKK line and stop catalog itself (`internal/catalog`); there is no CI
job and nothing is committed to git. Since VEC-131 this replaces the daily `refresh-catalog` workflow
and `web/scripts/build-catalog.mjs`.

- **Files:** `GET /catalog/routes.json`, `/catalog/stops.json`, `/catalog/route-variants.json`
  (same names and content as the old web host files). Public, no auth, outside `/api/`, so the app
  version gate and PocketBase's rate limiter do not apply. `ETag` and `Last-Modified` (conditional
  requests answer 304), `Cache-Control: public, max-age=3600, stale-while-revalidate=86400`, gzip when
  the client asks. `503` with `Retry-After` only while no copy has ever been built (first boot).
- **Builder:** the Go port of the old generator. It tiles Budapest and the HÉV corridors with
  `stops-for-location` circles, then calls `route-details` once per route (about 730 calls). It paces
  itself to roughly 300 calls a minute and counts every call against the same `FutarUpstream` budget as
  the `/futar` proxy, so it cannot starve live arrivals. Responses are streamed into small structs, so
  memory stays low.
- **Schedule:** on boot it builds when there is no copy or the copy is older than 24 hours, then
  rebuilds every 24 hours after the last success. A failed build keeps serving the last good copy and
  is retried after 5 minutes, doubling up to 2 hours. A degraded result (fewer than 100 routes or 500
  stop areas, or more than 5% below the current copy) is refused; routes or circles that fail are
  filled in from the current copy.
- **Data dir:** `<pb_data>/catalog/`. Each build is written to `set-<unix time>/` and then made live by
  atomically replacing `current`, so a restart serves the last good copy immediately and a crash
  mid-write cannot leave a mixed set. In Coolify `/app/pb_data` is already the persistent volume, so
  nothing else has to be mounted. A new environment needs a valid `FUTAR_API_KEY` and takes a few
  minutes to produce its first copy (503 until then); `docker compose` placeholders cannot build, which
  only logs.
- **Variables:** `FUTAR_API_KEY` (required to build) and `CATALOG_BUILD=0` (optional, disables the
  builder; the route still serves what is on disk).
- **Apps:** the native apps read the catalog from the same backend as the API (`vectra.pocketbaseUrl.*`
  in `native/gradle.properties`: debug builds dev, release builds production);
  `-Pvectra.catalogOrigin=…` overrides it.
- **Frozen copy:** `web/public/catalog/*.json` is no longer refreshed. It stays only so app versions
  released before VEC-131, which download the catalog from `https://vectra.southstar21.dev/catalog/`,
  and the web PWA keep working (with aging data). Delete the three files, the `/catalog/` location in
  `web/nginx.conf` and the web catalog loaders once the minimum app version in `app_config` (see
  "Forcing an update") is raised past the first release that reads the catalog from the backend, and
  web/ is retired.
- **Tests:** `go test ./internal/catalog` runs the builder against a fixture Futár server and checks the
  exact file format, persistence, the HTTP semantics and the retry loop. When the generator was ported,
  the old Node script and the Go code were run against the same fake Futár built from the committed
  files and produced byte-identical output.

## Setting up the dev resource in Coolify

Same as production: build `backend/Dockerfile` with base directory `/backend`, a persistent volume
on `/app/pb_data`, port 8090, but branch `dev` and the domain above. Environment:

| Variable | Dev value |
|---|---|
| `NEPTUN_ENCRYPTION_KEY` | a new random value (`openssl rand -hex 32`), **never production's** |
| `FUTAR_API_KEY` | production's key is fine (it only reads public BKK data); the dev backend builds its own catalog with it |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | a new pair (`npx web-push generate-vapid-keys`) |
| `VAPID_SUBJECT` | `mailto:support@southstar21.dev` |
| `WEBAUTHN_RP_ID` | `vectra.southstar21.dev` |
| `WEBAUTHN_RP_ORIGINS` | `https://vectra.southstar21.dev` (web-only: remove with web/), `android:apk-key-hash:M9YYBpGvvIdH0GaiNQ1OCuk5ZeyNXjRvYENq2JfWpfE` (the debug signing key) |
| `WEBAUTHN_RP_DISPLAY_NAME` | `Vectra Dev` |
| `TRUSTED_PROXY` | `cloudflare` (see below) |

Passkeys keep production's relying-party ID: `vectra.southstar21.dev/.well-known/` lists the debug
app IDs as well (`web/public/.well-known/`), so they work once that web build is deployed. A
passkey made on dev only exists on dev.

After the first deploy, create a superuser (`su-exec vectra /app/pb superuser create <email>
<password>` in the container's terminal, or the `/_/` installer link in the log). In the dashboard's settings, leave
SMTP off, or point it at a mailbox that isn't users', so dev never mails anyone; set the
application URL to the dev domain.

The test account is copied from production, re-encrypted with the dev key, by a one-off script
(record ids are kept; passkeys and push subscriptions are not copied).

## Client IP and the reverse proxy (`TRUSTED_PROXY`)

Every per-IP rate limit (PocketBase's auth limiter, password-reset emails, passkey login, the Futár
proxy, the re-auth checks) keys on the client IP. Whether that IP comes from `X-Forwarded-For` is set
by the environment, not by the database:

- **`TRUSTED_PROXY=cloudflare`**: trust `CF-Connecting-IP`, which Cloudflare sets to the client it
  saw and overwrites if the client sent one. **Required on production and dev in Coolify**, which sit
  behind Cloudflare. There `X-Forwarded-For` ends with Cloudflare's edge address, which changes per
  request, so `1` would give every request a fresh bucket (checked on dev, 2026-11-11: five bad logins
  in a row, no 429). The header can only be forged by bypassing Cloudflare and reaching the server
  directly, so the server should accept Cloudflare's addresses only.
- **`TRUSTED_PROXY=1`** (also `true`/`yes`): trust `X-Forwarded-For` and take its right-most entry,
  the one the proxy appended. For a reverse proxy with nothing in front of it.
- **unset**: ignore both headers and use the socket address. Right for `docker compose up` and
  `make run`, where nothing sits in front. Behind a proxy, every user would share its address.

The environment wins over the "Trusted proxy" setting in the dashboard (a saved value is replaced on
the next settings reload). Never publish the container port to the internet: reached directly, the
header is client-controlled. The compose file binds it to `127.0.0.1` for that reason.

## Rate limits

Two layers, both keyed on the client IP (see above) unless a user or identity is named.

**In-process limiters.** Every value is in one table, `internal/limits/limits.go`; each package builds
its limiter from it. State is in memory, so it resets on every restart or deploy.

| Limit | Applies to | Key |
|---|---|---|
| `ReauthUser` / `ReauthIP` | wrong current-password checks (account delete, email change, passkey enrolment, `users` password change) | user, IP |
| `AuthIdentityFailures` / `AuthIPFailures` | failed password logins | identity, IP (v6 /64) |
| `AuthSignups` / `AuthResets` | sign-ups, reset mails | IP (v6 /64) |
| `CryptoValues` | `/crypto` values per window | user |
| `NeptunReplace` | `/neptun/replace` | user |
| `PasskeyLoginStart` | public passkey login start | IP |
| `PushSend` | `/push/send` (superusers exempt) | user |
| `FutarPerIP` / `FutarUpstream` | Futár proxy per IP / calls to the Futár API overall | IP, process |

**PocketBase's own limiter** (dashboard, Settings > Application > Rate limiting). Migrations 9 and 12
seed it once and never overwrite an operator's edit, so the dashboard is the source of truth. Expected
state: enabled, with these rules:

| Label | Max requests | Window (s) |
|---|---|---|
| `*:auth` | 2 | 3 |
| `*:create` | 20 | 5 |
| `/api/batch` | 3 | 1 |
| `/api/` | 300 | 10 |
| `*:requestPasswordReset` | 3 | 60 |
| `*:confirmPasswordReset` | 10 | 60 |

## Container user

The image (`Dockerfile`, Alpine 3.24) runs the server as the unprivileged `vectra` user (uid/gid
10001). `docker-entrypoint.sh` starts as root only to re-own `/app/pb_data` (`chown -R`), then drops
to `vectra` with `su-exec`. A volume created by an older image, which ran as root, is therefore fixed
on the first start; no manual `chown` is needed. In the container's terminal (which is root), run
`pb` commands as the app user, `su-exec vectra /app/pb …`; a file a root command creates in
`pb_data` is re-owned on the next restart anyway.

## Forcing an update

`internal/versiongate` answers API requests from outdated app builds with `426 Upgrade Required`;
builds that know about it then lock behind a full-screen "update required" screen until they are
updated. The lock is stored on the device, so it holds offline and across restarts; lowering the
minimum unlocks it on the app's next foreground. The owner controls it from
the one record in the `app_config` collection (superuser only):

| Field | Meaning |
|---|---|
| `minIos` | Lowest iOS (and Mac) version still served, such as `1.9.0`. Empty: no minimum. |
| `minAndroid` | The same for Android. |
| `blockLegacy` | Refuse every build from before the app sent its version (iOS and Android together). |

Changes apply to the next request, with no restart. Only the native apps send `X-App-Version` and
`X-App-Platform`, so the web PWA, the dashboard, curl and the health check are never refused. Any
error reading the record lets the request through.

Builds from before this feature have no update screen and can't be locked: they keep opening and
working offline, and only show the 426's message wherever they show a server error (their unsynced
changes stay queued until they're updated). So:

1. Deploy the backend (empty fields: the gate is off).
2. Release the app build with the update screen and wait until it's on every channel (GitHub
   release, AltStore/SideStore source, TestFlight).
3. Tell testers, then tick `blockLegacy`.
4. Later, raise `minIos` or `minAndroid` whenever a build should be retired.
