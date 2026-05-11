package so.kontext.kit.deviceinfo

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock

/**
 * Reports device manufacturer + model + form-factor + boot time +
 * SD-card presence for ad targeting. Mirrors iOS `HardwareInfoProvider`,
 * which omits `sdCardAvailable` (no SD card concept on iOS) and always
 * returns `bootTime = nil` per Apple's required-reason API rules.
 *
 * `type` is one of `"handset"`, `"tablet"`, `"tv"` — the values the
 * server's `hardwareSchema` accepts. Form-factor classification uses
 * `UiModeManager` for TV (correct because Android TV identifies itself
 * via the UI mode) and `smallestScreenWidthDp` for tablet (the same
 * threshold AOSP uses to pick `values-sw600dp/` resources).
 */
public object HardwareInfoProvider {

    /**
     * `sw600dp` is Android's standard "tablet" cutoff — devices reporting a
     * smallest screen width ≥ 600 dp use tablet layout resources. Same
     * threshold AOSP uses to pick `values-sw600dp/`.
     *
     * On foldables the classification intentionally flips when the device
     * is folded (`sw < 600dp`, "handset") vs unfolded (`sw ≥ 600dp`,
     * "tablet"). The user is interacting with it as a phone vs a tablet
     * respectively, so ad targeting follows the actual interaction shape.
     */
    private const val TABLET_MIN_SW_DP = 600

    public data class HardwareInfo(
        val brand: String,
        val model: String,
        val type: String,
        /** Epoch milliseconds at which the OS booted. */
        val bootTime: Long,
        /** True iff a removable SD card is currently mounted. */
        val sdCardAvailable: Boolean,
    )

    public fun collect(context: Context): HardwareInfo {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        val type = if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            "tv"
        } else if (context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SW_DP) {
            "tablet"
        } else {
            "handset"
        }
        return HardwareInfo(
            brand = Build.BRAND,
            model = Build.MODEL,
            type = type,
            bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            sdCardAvailable = sdCardAvailable(context),
        )
    }

    /**
     * `getExternalFilesDirs(null)` returns one entry per app-visible
     * external storage volume — index 0 is always primary emulated
     * internal storage; later entries are removable SD cards (or null
     * when a slot exists but no card is inserted). Filtering nulls and
     * checking for size > 1 reliably detects a mounted removable card
     * without needing any runtime permission.
     */
    private fun sdCardAvailable(context: Context): Boolean =
        context.getExternalFilesDirs(null).filterNotNull().size > 1

    /** Dictionary representation for bridge layers (RN, Flutter). Mirrors iOS. */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        return mapOf(
            "brand" to info.brand,
            "model" to info.model,
            "type" to info.type,
            "bootTime" to info.bootTime,
            "sdCardAvailable" to info.sdCardAvailable,
        )
    }
}
