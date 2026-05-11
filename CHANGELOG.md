# Changelog

## 0.0.1
Initial release. Extracted from the `kontextso/sdk-v4` monorepo as a standalone Gradle module published to Maven Central as `so.kontext.kit:kontext-kit-android`.

* Device-info providers — `AppInfoProvider`, `HardwareInfoProvider`, `OSInfoProvider`, `ScreenInfoProvider`, `BatteryInfoProvider`, `AudioInfoProvider`, `NetworkInfoProvider`.
* GAID access via `AdvertisingIdProvider` (Play Services Ads Identifier).
* IAB OMID integration via the bundled `omsdk-android-1.6.4.aar` and `OmManager` lifecycle (loaded reflectively at runtime).
* IAB TCF consent reader (`TCFDataProvider`).
* Brightness control (`BrightnessManager`) and Chrome Custom Tabs in-app browser (`InAppBrowserManager`).
* Bundled `omsdk_v1.js` (raw resource) for WebView injection.
