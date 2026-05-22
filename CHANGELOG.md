# Changelog

## 0.0.5

OMID compliance + Kotlin 2.1 baseline.

* `OmSession`: switch HTML display impression owner from `Owner.NATIVE` to `Owner.JAVASCRIPT` (was wrongly assumed to suppress JS geometry polling — IAB validator flagged the resulting impression events). Now matches v3 sdk-kotlin, kontextkit-ios, and the IAB OMID Android v1.6.4 reference demo: `Owner.JAVASCRIPT` for impression on both display and video; `mediaEventsOwner` stays NONE for display, JAVASCRIPT for video. Native-side `AdEvents` construction dropped — JS verification scripts now own `loaded()` + `impressionOccurred()` for both creative types. `OmSession.loaded()` / `impressionOccurred()` kept as no-ops for SDK API compatibility (consuming SDKs still reference them).
* Toolchain: Kotlin 1.9.22 → **2.1.0**, AGP 8.6.1 → 8.7.3, Gradle 8.7 → 8.9, detekt 1.23.4 → 1.23.8, spotless 6.25.0 → 7.2.1. Aligns with sdk-kotlin 2.1 baseline so consuming apps already on Kotlin 2.x stop hitting compiler-output / metadata mismatches.

## 0.0.4

* `InstallIdProvider`: new `deviceinfo/InstallIdProvider.kt`. Returns a UUID v7 per-app-install identifier persisted in a dedicated `kontextso` `SharedPreferences` file under the `installId` key. Generated on first call, survives launches, resets only on uninstall / app-data clear. Mirrors iOS `InstallIdProvider` (UserDefaults-backed) so consumer SDKs can thread the same `installId` field through `/init`, `/preload`, `/error`, and `/debug` request payloads.

## 0.0.3

Bug fixes and test coverage.

* `HardwareInfoProvider.sdCardAvailable`: replace the `getExternalFilesDirs(null).size > 1` heuristic (false-positives on devices with secondary emulated volumes) with `StorageManager.storageVolumes` filtered on `isRemovable && state in [MEDIA_MOUNTED, MEDIA_MOUNTED_READ_ONLY]`.
* `NetworkInfoProvider.cellularDetail`: cover additional radio types — `NETWORK_TYPE_GSM`, `NETWORK_TYPE_TD_SCDMA`, `NETWORK_TYPE_LTE_CA` (numeric constant 19). Extracted the mapping into `internal fun mapCellularDetail(...)` for direct testing. Unrecognised types (including `NETWORK_TYPE_IWLAN`) keep emitting `null`; the server collapses both `null` and unknown strings to OpenRTB `CELLULAR_UNKNOWN` downstream.
* `InAppBrowserManager.open`: wrap the Custom Tabs launch in `runCatching` so `ActivityNotFoundException` from an absent browser flows through the documented `Result<Unit>` contract instead of escaping.
* Dokka: rewrote `OmSession.finish()` KDoc so `javaDocReleaseGeneration` no longer raises `Cannot cast ContentGroup` on the multi-line blockquote.
* `NetworkInfoProvider.cellularDetail`: silence the `NETWORK_TYPE_IDEN` deprecation warning with a function-scope `@Suppress("DEPRECATION")` (the constant value is still returned by `dataNetworkType` on old devices).
* Test coverage: +40 tests across `OmSession`, `OmManager` polling, `NetworkInfoProvider`, `BatteryInfoProvider`, `TCFDataProvider`, `InAppBrowserManager` auto-dismiss state machine, plus the new `OmPartner` data class.

## 0.0.2

OMID lifecycle fixes — `OmManager` / `OmSession` were not producing IAB-compliant validator output. Required for consuming SDKs targeting IAB OM SDK certification.

* Fix reflective OMID class paths and factory APIs — `Partner.createPartner`, `AdSessionContext.createHtmlAdSessionContext`, `AdSessionConfiguration.createAdSessionConfiguration`, `AdSession.createAdSession`, `AdSession.error` (not `logError`), correct `Owner` enum field names (`JAVASCRIPT` / `NATIVE` / `NONE`). The wrapper now actually hits the IAB OMID Android surface; before this, almost every reflection failed silently and no session was created.
* Move the 50 ms `registerAdView → start()` delay so it runs **after** session construction (was happening before, giving the JS layer no time to settle).
* Display sessions now use `Owner.NATIVE` as impressionOwner with `Owner.NONE` for media events. The SDK fires `loaded()` + `impressionOccurred()` via the new `AdEvents` reflective wrapper. This eliminates the spurious `geometryChange notFound` event that JS-owned sessions emitted between the last valid `geometryChange` and `sessionFinish`.
* Video sessions remain JS-owned with the `DEFINED_BY_JAVASCRIPT` triplet per the IAB `#webview-video` guidance.
* Video sessions adaptively wait for the inner `<video>` element to reach `readyState >= 1` (HAVE_METADATA) before calling `session.start()` — polls every 25 ms, up to 500 ms. Warm sessions return immediately; cold sessions wait only as long as metadata takes. Without this, OMID measures the videoEl as 0×0 at impression time on cold-start WebViews and reports `reasons: ["hidden"]` (IAB compliance failure).
* Detailed `KontextKit/OM` `Log.d` at every reflective step and lifecycle transition for consumer diagnostics.
* AGP 8.2.0 → 8.6.1 and Gradle wrapper 8.5 → 8.7 (required for composite-build parity with consuming SDKs).

## 0.0.1
Initial release. Extracted from the `kontextso/sdk-v4` monorepo as a standalone Gradle module published to Maven Central as `so.kontext.kit:kontext-kit-android`.

* Device-info providers — `AppInfoProvider`, `HardwareInfoProvider`, `OSInfoProvider`, `ScreenInfoProvider`, `BatteryInfoProvider`, `AudioInfoProvider`, `NetworkInfoProvider`.
* GAID access via `AdvertisingIdProvider` (Play Services Ads Identifier).
* IAB OMID integration via the bundled `omsdk-android-1.6.4.aar` and `OmManager` lifecycle (loaded reflectively at runtime).
* IAB TCF consent reader (`TCFDataProvider`).
* Brightness control (`BrightnessManager`) and Chrome Custom Tabs in-app browser (`InAppBrowserManager`).
* Bundled `omsdk_v1.js` (raw resource) for WebView injection.
