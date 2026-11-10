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

Passkeys keep production's relying-party ID: `vectra.southstar21.dev/.well-known/` lists the debug
app IDs as well (`web/public/.well-known/`), so they work once that web build is deployed. A
passkey made on dev only exists on dev.

After the first deploy, create a superuser (`/app/pb superuser create <email> <password>` in the
container's terminal, or the `/_/` installer link in the log). In the dashboard's settings, leave
SMTP off, or point it at a mailbox that isn't users', so dev never mails anyone; set the
application URL to the dev domain.

The test account is copied from production, re-encrypted with the dev key, by a one-off script
(record ids are kept; passkeys and push subscriptions are not copied).
