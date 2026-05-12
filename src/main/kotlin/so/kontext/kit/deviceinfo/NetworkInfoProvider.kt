package so.kontext.kit.deviceinfo

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import android.webkit.WebSettings

/**
 * Reports network transport + carrier + WebView UA for ad targeting.
 * Mirrors iOS `NetworkInfoProvider`.
 *
 * `type` collapses to four values matching the server's `networkSchema`
 * (`wifi` / `cellular` / `ethernet` / `other`); `detail` further narrows
 * cellular to the radio generation (`gprs` / `edge` / `2g` / `3g` /
 * `hspa` / `lte` / `5g`) so the ad server can adapt creative weight.
 *
 * `userAgent` is computed once via `WebSettings.getDefaultUserAgent` and
 * cached for the SDK's lifetime — matches v3 sdk-kotlin and iOS, both of
 * which capture the value once. The first call initialises WebView
 * internals (documented as expensive), so caching saves real time on the
 * preload hot path. Failures (Play Services updating WebView) are NOT
 * cached: the next call retries so transient conditions self-heal.
 *
 * `cellularDetail` carries `@SuppressLint("MissingPermission")` because
 * `TelephonyManager.dataNetworkType` requires `READ_PHONE_STATE` on
 * API 30+. KontextKit deliberately doesn't declare that permission to
 * spare host apps a Play Store review hassle they don't actually need;
 * the `SecurityException` is caught and degrades to `null`.
 */
public object NetworkInfoProvider {

    /**
     * Cached WebView user agent. `@Volatile` for cross-thread visibility
     * (preload runs on `Dispatchers.IO`); the race window is benign —
     * concurrent first-callers compute the same string. Failures are
     * deliberately NOT cached so transient WebView-update conditions
     * self-heal on the next `collect()`.
     */
    @Volatile
    private var cachedUserAgent: String? = null

    public data class NetworkInfo(
        val type: String,
        val carrier: String?,
        val detail: String?,
        val userAgent: String?,
    )

    public fun collect(context: Context): NetworkInfo {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }

        val type = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            else -> "other"
        }

        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val carrier = telephonyManager?.networkOperatorName
            ?.takeIf { type == "cellular" && it.isNotEmpty() }
        val detail = if (type == "cellular") cellularDetail(telephonyManager) else null

        return NetworkInfo(
            type = type,
            carrier = carrier,
            detail = detail,
            userAgent = userAgent(context),
        )
    }

    /** Dictionary representation for bridge layers (RN, Flutter). Mirrors iOS. */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        val dict = mutableMapOf<String, Any>("type" to info.type)
        info.carrier?.let { dict["carrier"] = it }
        info.detail?.let { dict["detail"] = it }
        info.userAgent?.let { dict["userAgent"] = it }
        return dict
    }

    /**
     * Returns the WebView's default user-agent string, cached after the
     * first successful read. Mirrors iOS's `cachedUserAgent` and v3's
     * `staticInfo.userAgent` (lazy-once). On failure (transient
     * Play-Services WebView update) returns `null` *without caching* so
     * the next call retries.
     */
    private fun userAgent(context: Context): String? = cachedUserAgent ?: try {
        WebSettings.getDefaultUserAgent(context).also { cachedUserAgent = it }
    } catch (_: RuntimeException) {
        null
    }

    /**
     * Maps `TelephonyManager.dataNetworkType` codes to the short names the
     * server's `networkSchema` expects. `@SuppressLint("MissingPermission")`
     * scoped to this helper because `dataNetworkType` is the only
     * permission-gated call in the file (see class KDoc). Broken out of
     * `collect()` to keep cyclomatic complexity below detekt's default
     * threshold.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun cellularDetail(telephonyManager: TelephonyManager?): String? = try {
        mapCellularDetail(telephonyManager?.dataNetworkType)
    } catch (_: SecurityException) {
        null
    }

    // `NETWORK_TYPE_IDEN` is deprecated (legacy Nextel/Boost push-to-talk
    // networks, defunct since ~2013) but the constant value can still be
    // returned by `dataNetworkType` on old devices, so we still classify
    // it as 2G rather than dropping the branch.
    @Suppress("DEPRECATION")
    internal fun mapCellularDetail(networkType: Int?): String? = when (networkType) {
        null -> null
        TelephonyManager.NETWORK_TYPE_GPRS -> "gprs"
        TelephonyManager.NETWORK_TYPE_EDGE -> "edge"
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        -> "2g"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA,
        -> "3g"
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        -> "hspa"
        TelephonyManager.NETWORK_TYPE_LTE,
        NETWORK_TYPE_LTE_CA,
        -> "lte"
        TelephonyManager.NETWORK_TYPE_NR -> "5g"
        TelephonyManager.NETWORK_TYPE_IWLAN -> "other"
        else -> "cellular"
    }

    private const val NETWORK_TYPE_LTE_CA = 19
}
