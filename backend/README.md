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

## Setting up the dev resource in Coolify

Same as production: build `backend/Dockerfile` with base directory `/backend`, a persistent volume
on `/app/pb_data`, port 8090, but branch `dev` and the domain above. Environment:

| Variable | Dev value |
|---|---|
| `NEPTUN_ENCRYPTION_KEY` | a new random value (`openssl rand -hex 32`), **never production's** |
| `FUTAR_API_KEY` | production's key is fine (it only reads public BKK data) |
| `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` | a new pair (`npx web-push generate-vapid-keys`) |
| `VAPID_SUBJECT` | `mailto:support@southstar21.dev` |
| `WEBAUTHN_RP_ID` | `vectra.southstar21.dev` |
| `WEBAUTHN_RP_ORIGINS` | `https://vectra.southstar21.dev,android:apk-key-hash:M9YYBpGvvIdH0GaiNQ1OCuk5ZeyNXjRvYENq2JfWpfE` (the debug signing key) |
| `WEBAUTHN_RP_DISPLAY_NAME` | `Vectra Dev` |
| `TRUSTED_PROXY` | `1` (see below) |

Passkeys keep production's relying-party ID: `vectra.southstar21.dev/.well-known/` lists the debug
app IDs as well (`web/public/.well-known/`), so they work once that web build is deployed. A
passkey made on dev only exists on dev.

After the first deploy, create a superuser (`/app/pb superuser create <email> <password>` in the
container's terminal, or the `/_/` installer link in the log). In the dashboard's settings, leave
SMTP off, or point it at a mailbox that isn't users', so dev never mails anyone; set the
application URL to the dev domain.

The test account is copied from production, re-encrypted with the dev key, by a one-off script
(record ids are kept; passkeys and push subscriptions are not copied).

## Client IP and the reverse proxy (`TRUSTED_PROXY`)

Every per-IP rate limit (PocketBase's auth limiter, password-reset emails, passkey login, the Futár
proxy, the re-auth checks) keys on the client IP. Whether that IP comes from `X-Forwarded-For` is set
by the environment, not by the database:

- **`TRUSTED_PROXY=1`** (also `true`/`yes`): trust `X-Forwarded-For` and take its right-most entry,
  the one Traefik appended. **Required on production and dev in Coolify.** Without it every user
  shares the proxy's address, so one busy client rate-limits everybody.
- **unset**: ignore the header and use the socket address. Right for `docker compose up` and
  `make run`, where nothing sits in front.

The environment wins over the "Trusted proxy" setting in the dashboard (a saved value is replaced on
the next settings reload). Never publish the container port to the internet: reached directly, the
header is client-controlled. The compose file binds it to `127.0.0.1` for that reason. If a CDN is
ever put in front of Traefik, the right-most entry becomes the CDN edge; revisit this then.
