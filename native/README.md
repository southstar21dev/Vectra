# Vectra — native app

Vectra's mobile app: Kotlin Multiplatform + Compose Multiplatform against the PocketBase
backend. Android and iOS run the same app: every screen, feature and fix lives in `shared/`,
so a change there reaches both. The platform apps are thin shells (`androidApp/`, `iosApp/`)
plus the platform seams in `shared/src/androidMain` and `shared/src/iosMain`.

## Layout
- `shared/` — all UI, logic and data (`commonMain`), platform bits in `androidMain` / `iosMain`.
  - `core/designsystem` — tokens (colour, type, radii, motion, haptics) and primitives (screens, sheets, pickers, toasts).
  - `core/network` — PocketBase client, auth store, `/api/vectra/crypto` batching.
  - `core/auth` — auth repository, profile, session watchdog.
  - `core/data` — key-value and blob storage, the offline cache, `LiveQuery`.
  - `core/maps` — the basemap, camera, geometry, and MapLibre's resource requests through the app's HTTP client.
  - `core/preferences`, `core/time`, `core/util`, `core/config`, `core/platform` — appearance prefs, Budapest
    wall-clock helpers, small shared helpers, build-time config, and the expect/actual seams.
  - `navigation` — tabs, the pushed-screen stack (Navigation 3) and deep links.
  - `features/*` — one package per feature (auth, dashboard, schedule, transit,
    weather, holidays, coursetracker, settings, notifications, navbar, shell, splash).
- `androidApp/` — the Android shell (Activity, application graph, manifest, resources).
- `iosApp/` — the iOS shell: a SwiftUI scene hosting `MainViewController()`, Info.plist, icon. The
  Xcode project is generated from `iosApp/project.yml` (`cd iosApp && xcodegen`); its build phase
  compiles `shared` through Gradle, so Xcode always builds the current shared code.
- `tools/` — icon (`icons.txt` → `RemixIcons.kt`) and logo generators; `cd tools && npm install` first.
- `docs/CONTRIBUTING.md` — how the app is put together and its conventions;
  `docs/BACKEND_CHANGES.md` — server-side changes native push, passkeys and the transit
  catalog need.

## Build
```
./gradlew :shared:testAndroidHostTest     # shared tests (TZ-independent, Budapest wall clock)
./gradlew :androidApp:assembleDebug       # debug APKs: arm64 and universal
./gradlew :androidApp:assembleRelease     # R8 release APKs: arm64 and universal
./gradlew :androidApp:bundleRelease       # App Bundle (Play splits it per ABI itself); run it on its own
./gradlew :androidApp:lint                # Android lint (baseline in androidApp/lint-baseline.xml)
./gradlew :shared:compileKotlinIosSimulatorArm64   # the iOS compile check (macOS only)
open iosApp/iosApp.xcodeproj              # iOS: run the iosApp scheme (macOS + Xcode)
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' build   # iOS simulator build from the CLI
./gradlew detekt                          # static analysis (baseline in config/detekt)
```
Kotlin warnings are errors in every compile. CI runs all of the above on PRs that touch `native/`,
except the release builds.

Version: `vectra.versionName` in `gradle.properties`; `versionCode` is derived from it. Both apps read it:
iOS gets it as `CFBundleShortVersionString`, with the same derived number as `CFBundleVersion`.
The backend overrides (`-Pvectra.pocketbaseUrl`, `-Pvectra.catalogOrigin`) apply to both apps as well.

Release signing: `-Pvectra.keystore=… -Pvectra.storePassword=… -Pvectra.keyAlias=… -Pvectra.keyPassword=…`.
Without a keystore a local release build is signed with the debug key so it installs; when `CI` is set or
with `-Pvectra.distribution=true` that fails the build instead.

Baseline profile: the checked-in profile lives under `androidApp/src/release/generated/baselineProfiles/`.
Regenerate it after large UI changes, with one device attached and the test account's credentials:
```
./gradlew :androidApp:generateBaselineProfile -Pvectra.testEmail=… -Pvectra.testPassword=…
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.southstar21.vectra.baselineprofile.StartupBenchmark \
  -Pvectra.testEmail=… -Pvectra.testPassword=…
```
Both pass the credentials because Gradle uninstalls the app after each connected run, so every run starts signed out.
Backend: `-Pvectra.pocketbaseUrl=…` (default `https://apivectra.southstar21.dev`) and
`-Pvectra.catalogOrigin=…` (default `https://vectra.southstar21.dev`, where the transit
catalog `/catalog/*.json` is served).

## Stack
Kotlin 2.4.21 · AGP 9.5 · Gradle 9.8.2 · Compose Multiplatform 1.12.2 · Navigation 3 ·
Metro DI · Ktor 3.6 · kotlinx.serialization / datetime · DataStore 1.3 ·
maplibre-compose 0.20 · Kyant backdrop 2.0 (Liquid Glass navbar) · Credential Manager 1.6 ·
detekt 2.0 alpha with compose-rules.
