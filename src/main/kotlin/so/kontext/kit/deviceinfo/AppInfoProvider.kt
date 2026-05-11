package so.kontext.kit.deviceinfo

import android.content.Context
import android.content.pm.PackageManager

/**
 * Provides host-app identity (package name, version, install timestamps,
 * SDK process start time) for ad targeting. Mirrors iOS `AppInfoProvider`
 * so every Kontext SDK reports the same shape on the wire.
 *
 * `version` falls back to `"0.0.0"` (matching iOS) when the manifest omits
 * `versionName` — clear sentinel for analytics, distinct from any real
 * release ("1.0.0" would silently look like a normal v1 app). `firstInstallTime` and `lastUpdateTime` are nullable
 * rather than zero because the server's `appSchema` documents zero as
 * "not available" — mapping it to null here keeps the contract explicit
 * at the type level instead of relying on downstream code to interpret
 * a sentinel.
 *
 * [processStartMs] approximates "when the SDK was first loaded into the
 * host process" — captured at companion-object initialisation, which
 * happens on first reference to `AppInfoProvider`. Lives here (not in
 * each consuming SDK's `AppCollector`) so the cross-platform definition
 * doesn't drift between sdk-kotlin / sdk-swift and so consumers don't
 * each have to rebuild the same one-liner.
 */
public object AppInfoProvider {

    public data class AppInfo(
        val bundleId: String,
        val version: String,
        /** First-install time as Unix epoch milliseconds; `null` if unknown. */
        val firstInstallTime: Long?,
        /**
         * Last app-update time as Unix epoch milliseconds; `null` if unknown.
         * Android-only — iOS doesn't expose an equivalent so iOS's
         * `AppInfoProvider` always returns `nil` here.
         */
        val lastUpdateTime: Long?,
    )

    /**
     * Epoch ms when [AppInfoProvider] was first referenced — proxy for
     * "when the SDK loaded into the process." Captured at object init,
     * shared across all Kontext consumer SDKs.
     */
    public val processStartMs: Long = System.currentTimeMillis()

    public fun collect(context: Context): AppInfo {
        val packageName = context.packageName
        return try {
            val packageInfo = context.packageManager.getPackageInfo(packageName, 0)
            AppInfo(
                bundleId = packageName,
                version = packageInfo.versionName ?: "0.0.0",
                firstInstallTime = packageInfo.firstInstallTime.takeIf { it > 0 },
                lastUpdateTime = packageInfo.lastUpdateTime.takeIf { it > 0 },
            )
        } catch (_: PackageManager.NameNotFoundException) {
            // The host app's own package not being resolvable is theoretically
            // impossible, but PackageManager's API forces us to handle it.
            AppInfo(
                bundleId = packageName,
                version = "0.0.0",
                firstInstallTime = null,
                lastUpdateTime = null,
            )
        }
    }

    /** Dictionary representation for bridge layers (RN, Flutter). Mirrors iOS. */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        val dict = mutableMapOf<String, Any>(
            "bundleId" to info.bundleId,
            "version" to info.version,
            "processStartMs" to processStartMs,
        )
        info.firstInstallTime?.let { dict["firstInstallTime"] = it }
        info.lastUpdateTime?.let { dict["lastUpdateTime"] = it }
        return dict
    }
}
