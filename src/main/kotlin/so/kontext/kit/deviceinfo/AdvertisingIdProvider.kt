package so.kontext.kit.deviceinfo

import android.content.Context
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Retrieves the Google Advertising ID (GAID) from Play Services.
 *
 * Mirrors iOS's IDFA path. The GAID is the Android-side personalised-ad
 * identifier — Google's policy and IAB SPI require that callers respect
 * the per-user "Limit Ad Tracking" (LAT) flag: when LAT is on, the GAID
 * must not be transmitted off-device. [getAdvertisingId] enforces that
 * contract by returning `null` when [Result.isLimitAdTrackingEnabled];
 * use that helper from publisher code unless you have a specific reason
 * to look at the raw `Result`.
 *
 * Raw IDs are normalised through [normalize] before reaching callers —
 * empty strings, whitespace-only strings, and the zero UUID
 * (`00000000-0000-0000-0000-000000000000`) all decay to `null`. The
 * zero UUID is what Play Services returns when the user enabled
 * "Delete advertising ID" on Android 12+ or during a reset; without
 * normalisation it would otherwise leak through to the ad server as
 * a real-looking but useless string.
 *
 * `AdvertisingIdClient.getAdvertisingIdInfo` is a blocking IPC call to
 * Play Services and must run off the main thread (Google enforces this
 * with a runtime exception). [collect] hops to `Dispatchers.IO` for
 * coroutine callers; [getAdvertisingId] is the sync entry for non-
 * coroutine bridges that have their own thread management.
 *
 * iOS's `AdvertisingIdProvider` exposes `resolveIds(manualAdvertisingId:
 * manualVendorId:)`; the Kotlin equivalent is [resolveId] (singular —
 * Android has no IDFV / `getVendorId` equivalent). Use [resolveId] from
 * sdk-kotlin's Session so a publisher-set override is normalised the same
 * way as the Play Services GAID instead of being forwarded blindly.
 */
public object AdvertisingIdProvider {

    private const val ZERO_UUID = "00000000-0000-0000-0000-000000000000"

    public data class Result(
        val advertisingId: String?,
        val isLimitAdTrackingEnabled: Boolean,
    )

    /**
     * Coroutine-friendly entry point. Hops to `Dispatchers.IO` so the
     * caller doesn't have to think about which thread invokes Play
     * Services.
     */
    public suspend fun collect(context: Context): Result = withContext(Dispatchers.IO) {
        collectSync(context)
    }

    /**
     * Returns the GAID only when the user has not opted into LAT.
     *
     * This is the helper publisher code should call: it bakes in the
     * privacy contract (LAT on → no GAID off-device), so consumers can't
     * accidentally leak the ID by reading `Result.advertisingId` directly.
     * Must run off the main thread (Play Services IPC); use [collect] from
     * coroutine code or call this from your own background dispatcher.
     */
    public fun getAdvertisingId(context: Context): String? = applyLatFilter(collectSync(context))

    /**
     * Resolves the advertising ID, preferring a normalised publisher-supplied
     * override over the Play Services GAID. Mirrors iOS `resolveIds` minus
     * the IDFV (no Android equivalent).
     *
     * `manualAdvertisingId` runs through [normalize] first — empty,
     * whitespace-only, or zero-UUID overrides decay to `null` and fall back
     * to the system GAID. So a buggy `SessionOptions(advertisingId = "")`
     * doesn't quietly forward an empty string to the ad server.
     *
     * `context` may be `null` (Session can be constructed before the host
     * app's Context is wired). In that case only the manual-override path
     * is consulted; if it normalises to `null`, the result is `null`.
     *
     * Same off-main-thread requirement as [getAdvertisingId] — Play Services
     * IPC. Callers wrap in `withContext(Dispatchers.IO)`.
     */
    public fun resolveId(context: Context?, manualAdvertisingId: String? = null): String? =
        normalize(manualAdvertisingId) ?: context?.let { getAdvertisingId(it) }

    /**
     * Calls Play Services directly. Failures (Play Services unavailable,
     * network down at first lookup, etc.) collapse to a "no ID, LAT off"
     * result so callers don't need to distinguish the failure modes.
     * Private — public callers go through [collect] (suspend) or
     * [getAdvertisingId] (sync, LAT-filtered).
     */
    private fun collectSync(context: Context): Result = try {
        val info = AdvertisingIdClient.getAdvertisingIdInfo(context)
        Result(
            advertisingId = normalize(info.id),
            isLimitAdTrackingEnabled = info.isLimitAdTrackingEnabled,
        )
    } catch (_: Exception) {
        Result(advertisingId = null, isLimitAdTrackingEnabled = false)
    }

    /**
     * Applies the LAT privacy contract to a [Result]: if Limit Ad Tracking
     * is enabled, the ID must not leave the device. Extracted from
     * [getAdvertisingId] so it can be tested without mocking Play
     * Services — Play's `AdvertisingIdClient` is hard to stub without
     * MockK, but this rule is the part actually worth covering.
     */
    internal fun applyLatFilter(result: Result): String? =
        if (result.isLimitAdTrackingEnabled) null else result.advertisingId

    /**
     * Normalises a raw advertising ID: null, empty, whitespace-only, or
     * the all-zero UUID all decay to `null`. The zero UUID is the value
     * Play Services returns after a user reset or "Delete advertising ID"
     * (Android 12+); without this filter we'd forward a string that looks
     * real but identifies nobody. Mirrors iOS `normalize`. Internal so
     * tests in the same module can verify the rules directly.
     */
    internal fun normalize(id: String?): String? {
        if (id.isNullOrBlank() || id.lowercase() == ZERO_UUID) return null
        return id
    }
}
