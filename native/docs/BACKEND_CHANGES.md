# Changes outside `native/` that the native apps need

The native app was built without touching `backend/`, `web/` or `.github/`.
Everything works against today's backend except **native push** and **passkeys**,
which need the changes below. The transit catalog (section 3) needs a new home before the
web host is retired.

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

The server sends Web Push only (`backend/internal/push/push.go`), and migration `…06` removed the
old `transport='fcm'` rows. The app registers rows as:

```json
{ "user": "<id>", "transport": "fcm", "device_name": "Mobile", "subscription": { "token": "<FCM registration token>" } }
```

**a. Validation** — in `RegisterSubscriptionValidation`, branch on transport before the
endpoint allowlist:

```go
if e.Record.GetString("transport") == "fcm" {
    var sub struct{ Token string `json:"token"` }
    if err := json.Unmarshal(subscriptionOf(e.Record), &sub); err != nil || len(sub.Token) < 32 {
        return e.BadRequestError("Invalid push subscription", nil)
    }
    return e.Next()
}
```

`pruneSubscriptions`/the per-endpoint dedupe should key FCM rows by token.

**b. Delivery** — `delivery.send` dispatches by transport:

```go
func (d *delivery) send(rec *core.Record) map[string]any {
    if rec.GetString("transport") == "fcm" {
        return d.sendFCM(rec)
    }
    return d.sendWebPush(rec)
}
```

`sendFCM` uses `firebase.google.com/go/v4/messaging` with a service account from a new env
var (e.g. `FIREBASE_SERVICE_ACCOUNT_JSON`), sending
`messaging.Message{Token, Notification{Title, Body}, Data{"url", "tag"}, Android{Notification{Tag, ChannelID: "vectra"}}}`,
and deletes the row on `messaging.IsUnregistered(err)` (the equivalent of 404/410 pruning).
Add tests beside the existing push tests.

**c. App side** — create a Firebase project for `dev.southstar21.vectra`, drop
`google-services.json` into `native/androidApp/`, add the `com.google.gms.google-services`
plugin + `firebase-messaging` (BoM 35.x), and replace `NoPush` in `VectraApplication` with a
`PushRegistrar` returning `FirebaseMessaging.getInstance().token.await()` plus a
`FirebaseMessagingService` that posts through `AndroidNotifier`. The settings toggle and the
"Test" button switch to server push automatically once `isConfigured` is true.

## 3. CI

GitHub only reads `.github/workflows/`. A job for the native build:

```yaml
  native:
    runs-on: ubuntu-latest
    env:
      TZ: Europe/Budapest
    defaults:
      run:
        working-directory: native
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v5
      - run: ./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:lintDebug
```

## 3. Transit catalog (before retiring the web host)

The app downloads its BKK catalog (`routes.json`, `stops.json`, `route-variants.json`) from
`<catalogOrigin>/catalog/` at runtime (`features/transit/Catalog.kt`) and caches it on disk.
Today that origin is the web host, refreshed by the "refresh BKK transit catalog" workflow.
When the web app goes away, keep serving those three files from the same path (a static
bucket, nginx location or a PocketBase route all work) or point `-Pvectra.catalogOrigin` at
their new home. The Digital Asset Links file from section 1 must also stay on
`vectra.southstar21.dev` for passkeys to keep working.
