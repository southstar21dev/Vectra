# Changes outside `native/` that the native apps need

Open items only. Everything else works against the current backend. Native **push** (section 2) is
still not implemented server-side; passkeys (section 1) need the three pieces below to be live on the
host; the transit catalog (section 3) already moved into the backend.
CI for the native build already exists (`.github/workflows/ci.yml`), so it is no longer listed here.

## 1. Passkeys (Credential Manager, AuthenticationServices)

Credential Manager only runs a WebAuthn ceremony for `rpId = vectra.southstar21.dev`
when that domain vouches for the app, and go-webauthn only accepts the assertion when
the app's origin is allowed.

**a. Digital Asset Links** — serve `https://vectra.southstar21.dev/.well-known/assetlinks.json`
(add it as `public/.well-known/assetlinks.json`; nginx must serve it as `application/json`
and must not rewrite it to the SPA fallback):

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls", "delegate_permission/common.get_login_creds"],
  "target": {
    "namespace": "android_app",
    "package_name": "dev.southstar21.vectra",
    "sha256_cert_fingerprints": ["<SHA-256 of the signing cert, AA:BB:… form>"]
  }
}]
```

Get the fingerprint with `./gradlew :androidApp:signingReport` (debug) or from Play Console
(App signing key) for release. List both while testing.

**b. Allowed origins** — append the app origin to `WEBAUTHN_RP_ORIGINS` (the comment in
`backend/internal/passkeys` already anticipates it):

```
WEBAUTHN_RP_ORIGINS=https://vectra.southstar21.dev,android:apk-key-hash:<base64url(SHA-256 of signing cert), no padding>
```

`android:apk-key-hash:` takes the same digest as above, but base64url-encoded:
`echo <hex-without-colons> | xxd -r -p | base64 | tr '+/' '-_' | tr -d '='`.

**c. Apple app site association (iOS)**: AuthenticationServices only runs the ceremony when
`https://vectra.southstar21.dev/.well-known/apple-app-site-association` (no extension, served as
`application/json` and not rewritten to the SPA fallback) lists the app:

```json
{ "webcredentials": { "apps": ["<TeamID>.dev.southstar21.vectra"] } }
```

iOS sends the web origin (`https://vectra.southstar21.dev`), so `WEBAUTHN_RP_ORIGINS` needs no
iOS entry. The app side is done: `iosApp/iosApp/iosApp.entitlements` claims
`webcredentials:vectra.southstar21.dev`. Device builds need `DEVELOPMENT_TEAM` set in
`iosApp/project.yml`.

## 2. Native push (FCM)

The backend no longer has any push code: the Web Push package, its route and the browser-endpoint
validation hook were removed, and migration `…06` removed the old `transport='fcm'` rows. The
`push_subscriptions` collection stays (10 rows per user, set in `schema.RowQuotas`) so the app can
register rows as:

```json
{ "user": "<id>", "transport": "fcm", "device_name": "Mobile", "subscription": { "token": "<FCM registration token>" } }
```

**a. Validation** — add a hook on `push_subscriptions` that checks the token:

```go
if e.Record.GetString("transport") == "fcm" {
    var sub struct{ Token string `json:"token"` }
    if err := json.Unmarshal(subscriptionOf(e.Record), &sub); err != nil || len(sub.Token) < 32 {
        return e.BadRequestError("Invalid push subscription", nil)
    }
    return e.Next()
}
```

Dedupe rows by token.

**b. Delivery** — add a `/push/send` route and an FCM sender (design it fresh with a transport
interface; nothing exists to extend).

The FCM sender uses `firebase.google.com/go/v4/messaging` with a service account from a new env
var (e.g. `FIREBASE_SERVICE_ACCOUNT_JSON`), sending
`messaging.Message{Token, Notification{Title, Body}, Data{"url", "tag"}, Android{Notification{Tag, ChannelID: "vectra"}}}`,
and deletes the row on `messaging.IsUnregistered(err)` (the equivalent of 404/410 pruning).
Add tests with it.

**c. App side** — create a Firebase project for `dev.southstar21.vectra`, drop
`google-services.json` into `native/androidApp/`, add the `com.google.gms.google-services`
plugin + `firebase-messaging` (BoM 35.x), and replace `NoPush` in `VectraApplication` with a
`PushRegistrar` returning `FirebaseMessaging.getInstance().token.await()` plus a
`FirebaseMessagingService` that posts through `AndroidNotifier`. The settings toggle and the
"Test" button switch to server push automatically once `isConfigured` is true.

## 3. Transit catalog (moved into the backend)

The app downloads its BKK catalog (`routes.json`, `stops.json`, `route-variants.json`) from
`<catalogOrigin>/catalog/` at runtime (`features/transit/data/Catalog.kt`) and caches it on disk.
The backend now builds it itself and serves it at `/catalog/*.json` on the API host (see
`../../backend/README.md`, "Transit catalog"), so `catalogOrigin` defaults to the backend URL
(`vectra.pocketbaseUrl.debug` for debug builds, `.release` for release builds). The daily CI workflow
and the generator in `web/scripts/` are gone.

`web/public/catalog/*.json` is a frozen copy that only serves app versions released before this
change (they read it from `https://vectra.southstar21.dev/catalog/`). It can be removed, together with
the web host's `/catalog/` location, once the minimum supported app version is raised past the first
release that reads the catalog from the backend.

The Digital Asset Links and apple-app-site-association files from section 1 (today in
`web/public/.well-known/`) must also stay on
`vectra.southstar21.dev` for passkeys to keep working.
