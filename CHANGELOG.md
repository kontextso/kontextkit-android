# Changelog

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
