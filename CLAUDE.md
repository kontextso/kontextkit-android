# KontextKit (Android) — Project Context for Claude

## What this repo is

KontextKit is a **shared internal library**, not a public SDK. It's consumed by Kontext's Android-targeting SDKs — `sdk-kotlin`, `sdk-react-native` (Android half), and `sdk-flutter` (Android half) — so that platform-utility code is written once and shared, not re-derived three times.

Distributed via Maven Central as `so.kontext.kit:kontext-kit-android`.

## Inclusion rule (high bar)

Code belongs in KontextKit only if **one** of the following holds:

1. **It touches Android system APIs only callable from native code.** The JS/Dart layers in `sdk-react-native` and `sdk-flutter` can't reach these directly, so the native wrapper has to live somewhere.
   Examples: Play Services Ads Identifier (GAID), `WifiManager`/`ConnectivityManager`/`TelephonyManager` (device info), `AudioManager`, `BatteryManager`, `SharedPreferences` reads for TCF consent, Chrome Custom Tabs, the IAB OMID native SDK.

2. **It has hidden complexity that's risky to re-derive across SDKs.** IAB OMID is the canonical example: correct event ordering, lifecycle, and the reflective AAR loading pattern (the AAR isn't a normal Gradle dep — see `OmManager`). Three SDKs writing this independently means three slightly-different implementations.

If neither bar is met, default to per-SDK code (Kotlin `data class`es in sdk-kotlin, etc).

**Does NOT belong here:** domain models (`Bid`, `Message`, `AdEvent`), networking flows (`Preload`, `HttpRetry`, `ErrorCapture`), the public-API surface (`Session`, `Ad`, entry-point classes), UI components.

## Naming convention

Two suffixes carry meaning. Pick whichever fits the code's *shape*, not its domain.

- **`*Provider`** — stateless, read-only. Object/companion functions that return an immutable snapshot via `fun collect(...): XxxInfo`. No singletons holding mutable state, no observers, no side effects.
- **`*Manager`** — stateful and/or side-effecting. Holds observers, lifecycle, or owns system-property reads+writes (`BrightnessManager` style).

`OmManager` reflectively loads the OMID AAR — kept as a `Manager` because lifecycle and state belong to it.

## Layout

```
src/main/kotlin/so/kontext/kit/
  deviceinfo/   AdvertisingIdProvider, App/HW/OS/Screen/Battery/Audio/NetworkInfoProvider, BrightnessManager
  omsdk/        OmManager, OmSession, OmPartner, OmCreativeType
  privacy/      TCFDataProvider
  ui/           InAppBrowserManager
src/main/res/raw/omsdk_v1.js     bundled OMID JS for WebView injection
src/main/AndroidManifest.xml     declares ACCESS_NETWORK_STATE + AD_ID perms
src/test/kotlin/                 mirrors main/ layout (JUnit 4 + Robolectric)
local-maven/iab/omsdk-android/   vendored IAB OMID AAR (1.6.4), loaded reflectively at runtime
```

Subset of iOS — no StoreKit (`SKAdNetworkManager`, `SKOverlayManager`, etc.) and no ATT (`TrackingAuthorizationManager`). Android attribution flows differ (Play Install Referrer, not SKAN); GAID prompts are inlined in consumers, not promoted to KontextKit.

## Build / test / lint

```sh
# Build
./gradlew build

# Run unit tests
./gradlew test

# Lint
./gradlew spotlessCheck detekt

# Auto-fix style
./gradlew spotlessApply
```

CI runs `test` on `ubuntu-latest` (JDK 17). See `.github/workflows/ci.yml`.

## Release

See [RELEASING.md](./RELEASING.md) for the Maven Central + git-tag flow.

Versioning: semver. The published version is set via `gradle/libs.versions.toml` (`kit = "..."`) and can be overridden at publish time with `-PkitVersion=X.Y.Z`. The OMID AAR version is independent of KontextKit's semver — bumping the AAR is a regular KontextKit minor/major bump per semver rules on the public Kotlin API.

## Conventions

- **Kotlin 1.9.22**, JDK 17, AGP 8.2.0, `compileSdk = 34`, `minSdk = 26`. AndroidX-only (Jetifier off).
- **`explicitApi()` mode is on** — every top-level and class member must spell out its visibility (`public` / `internal` / `private`) and public functions need an explicit return type. Catches accidentally-public helpers before they become committed surface area.
- **No comments** unless the why is non-obvious (hidden constraint, subtle invariant, workaround for a specific bug, behavior that would surprise a reader). Don't ref PRs or tickets in code.
- **OMID AAR is loaded reflectively** by `OmManager` — it isn't declared as a Gradle dep because AAR transform fails in CI unit tests. Consuming apps wire the AAR in their own build.

## Related repos

- [sdk-kotlin](https://github.com/kontextso/sdk-kotlin) — primary consumer; depends on KontextKit transitively
- [sdk-v4](https://github.com/kontextso/sdk-v4) — monorepo; KontextKit was historically developed here before extraction
- [kontextkit-ios](https://github.com/kontextso/kontextkit-ios) — iOS counterpart
