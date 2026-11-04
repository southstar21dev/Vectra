# Working on the Vectra app

Vectra is a Kotlin Multiplatform + Compose Multiplatform app for Android and iOS from one
codebase: UI and logic live in `commonMain`, and `androidApp`/`iosApp` only host it. A new
platform seam needs both an `androidMain` and an `iosMain` actual (or implementation passed
into `AppGraph.Factory` from each shell), so neither app falls behind. It has its own design system: glass surfaces,
Inter, an accent colour the user picks, and native behaviour throughout. Don't use
Material components; build on the primitives below.

## Layout
- `shared/src/commonMain/kotlin/dev/southstar21/vectra/`
  - `core/designsystem/theme/`: tokens. `Vectra.colors`, `Vectra.haptics`, `Vectra.reduceMotion`,
    `VectraSprings` / `VectraEasing` (motion), `vtext(...)` / `VType` (type), `Radius` (corner shapes),
    `Palette` (fixed hues), surfaces (`glass`, `pillDepth`, `panelGlass`) and modifiers (`tappable`,
    `consumeTouches`, `pressScale`).
  - `core/designsystem/components/`: primitives (see "Surfaces" below).
  - `core/network/`: `PocketBaseClient`, `CryptoClient` (field encryption), the stored session.
  - `core/auth/`: `AuthRepository`, `SessionListener`, `SessionWatchdog`, `Profile`.
  - `core/data/`: `KeyValueStore` (large values go to `BlobStore`, one file each), `OfflineCache` + `offlineFirst(...)`, `LiveQuery`.
  - `core/maps/`: the basemap (`TransitMap`), camera, GeoJSON and route geometry, radar tile recolouring.
  - `core/preferences/`: `AppearancePrefs` and the keys it holds (accent, background, time format).
  - `core/time/`: Budapest wall-clock helpers. Always use `nowInBudapest()`, never device-local time.
  - `core/platform/`: expect/actual seams (permissions, system UI, location, launcher).
  - `core/util/`: small helpers with no better home (`runSuspendCatching`, number formatting).
  - `core/config/`: `VectraConfig`, the values the shell passes in at build time.
  - `navigation/`: `Route` (tabs), `Screen` (pushed screens), `AppNavigator`, `Destinations.kt`.
  - `features/<feature>/`: one package per feature. `core` never imports a feature, and features
    don't form cycles: `coursetracker` builds on `schedule`, never the reverse.
- `androidApp/`: the Android shell (Activity, application graph, manifest, resources).
- `baselineprofile/`: the baseline profile generator and `StartupBenchmark` (Macrobenchmark, run on a device).
- `tools/`: icon and logo generators (`cd tools && npm install && npm run icons`).

## Surfaces: screen, sheet or dialog?
- **Pushed screen**: anything with its own task (editors, details, maps, multi-step flows).
  Add a `@Serializable` entry to `Screen` carrying only ids, render it in `PushedScreen`, and
  open it with `LocalNavigator.current.push(Screen.X(id))`. The screen loads its own data from
  repositories and pops itself when done. Build it from `ScreenColumn { TopBar(…, BarLeading.Back, onBack); ScrollBody { … }; BottomBar { … } }`.
  Steps inside a flow get real back with `VBackHandler(enabled = step != first) { previous() }`.
- **Sheet** (`VSheet`): short, focused input that keeps the page behind it in context
  (pickers, option lists, small forms). `SheetSize.Fit` for short content, `Expandable` for
  long lists, `Full` for tall forms. Sheets drag, fling, follow predictive back and stay
  above the keyboard.
- **Dialog** (`ConfirmDialog`, centred `VDialog`): only for confirmations and alerts.

## Interaction
- **Taps**: use `Modifier.tappable(shape, …)` for a press highlight clipped to the shape,
  optional press scale and an optional haptic. Rows that answer with `pressScale` pass `highlight = false`.
  Scrims and panels that only swallow touches use `Modifier.consumeTouches()`. Bare `clickable` fails detekt.
- **Haptics**: `Vectra.haptics.tick()` for selection changes, `confirm()` after a save,
  `reject()` when arming something destructive, `longPress()` for lifts and context menus,
  `threshold()` when a gesture passes its commit point.
- **Motion**: springs (`VectraSprings`) for anything the finger can touch, tweens
  (`VectraEasing`) for fades. Branch on `Vectra.reduceMotion` (it follows the OS setting):
  replace movement with a short fade.
- **Lists**: long or unbounded lists are `LazyColumn` with stable `key`s; on a pushed screen use `LazyScrollBody`.
- **Tab pages** use `VectraPage(onRefresh = …)` for pull-to-refresh and wrap top-level
  cards in `Reveal(index)` (plays once per session).
- **Text** sizes are `sp` and grow with the system font scale. Give tappable controls
  minimum heights (`heightIn(min = …)`), not fixed ones.

## Conventions
- **Wiring**: repositories are app-lifetime singletons on `AppGraph`: a `val xRepository` plus a
  `@SingleIn(AppScope::class) @Provides` function. They are built on first read, e.g. `graph.scheduleRepository`.
  `graph` stays at the composition root (`navigation/Destinations.kt`, and the Dashboard and Settings tabs,
  which gather several features); pass everything below it the repositories it uses.
- **Sign-out**: anything holding per-user state in memory implements `SessionListener` and joins
  `AppGraph.provideSessionListeners`. Listeners don't register themselves, so one no screen has opened still runs.
- **Reaching up a dependency**: when a lower feature needs a higher one, it declares an interface, the higher
  feature implements it and `AppGraph` binds them (`ScheduleCourseTrackerHooks`, `NeptunSyncListener`).
- **Startup**: once-per-process work goes in `AppStartup.run`, not in a composable.
- **State**: plain classes exposing `StateFlow`s; collect with `collectAsStateWithLifecycle()`.
- **Neptun ICS** must be fetched through the Google Apps Script proxy (`features/schedule/IcsParser.kt`):
  the university blocks datacenter IPs. Never fetch Neptun directly or via the backend.
- **Colours**: themed colours come from `Vectra.colors`, fixed hues from `Palette`, map data colours from `MapColors`.
  Don't redeclare a hue or a corner shape locally; repeated `.dp` spacing stays literal.
- **Icons**: `RemixIcons.<Name>`. To add one, append its `Ri…` name to `tools/icons.txt` and run the generator.
- Comments explain *why*, sparsely.

## Build and test
```
./gradlew :shared:testAndroidHostTest      # shared unit tests
./gradlew :androidApp:assembleDebug        # debug APK
./gradlew detekt                           # static analysis
./gradlew :androidApp:lint                 # Android lint
```
Kotlin warnings are errors, in the Android and iOS compiles alike. Suppress one only when it can't be removed,
narrowly and with a comment. detekt checks new code against `config/detekt/baseline.xml`;
`./gradlew detektBaseline` regenerates it (do that to record a deliberate exception, not to clear a finding).
`androidApp/lint-baseline.xml` works the same way for Android lint (`:androidApp:updateLintBaseline`).
CI runs tests, detekt, the debug build, lint and the iOS compile on every change under `native/`.
The baseline profile is generated and checked in; the README has the commands.
Check UI changes on a device or emulator as well; unit tests don't cover interaction.
