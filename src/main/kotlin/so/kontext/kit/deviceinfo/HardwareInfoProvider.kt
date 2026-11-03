package so.kontext.kit.deviceinfo

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/**
 * Reports device manufacturer + model + form-factor + boot time +
 * SD-card presence for ad targeting. Mirrors iOS `HardwareInfoProvider`,
 * which omits `sdCardAvailable` (no SD card concept on iOS) and always
 * returns `bootTime = nil` per Apple's required-reason API rules.
 *
 * `type` is the OpenRTB-derived form-factor set iOS's `HardwareType` uses: `"phone"`, `"tablet"`, `"tv"`,
 * `"desktop"`, `"other"`. A consumer whose server uses other words (`handset`) maps it. Classification:
 * `UiModeManager` for TV (Android TV identifies itself via the UI mode) and for watches, cars, appliances
 * and VR headsets (`other`); the `android.hardware.type.pc` feature for desktops (ChromeOS); otherwise
 * `smallestScreenWidthDp` for tablet (the threshold AOSP uses to pick `values-sw600dp/` resources), else phone.
 */
public object HardwareInfoProvider {

    /**
     * `sw600dp` is Android's standard "tablet" cutoff — devices reporting a
     * smallest screen width ≥ 600 dp use tablet layout resources. Same
     * threshold AOSP uses to pick `values-sw600dp/`.
     *
     * On foldables the classification intentionally flips when the device
     * is folded (`sw < 600dp`, "phone") vs unfolded (`sw ≥ 600dp`,
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
        return HardwareInfo(
            brand = Build.BRAND,
            model = Build.MODEL,
            type = formFactor(context),
            bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            sdCardAvailable = sdCardAvailable(context),
        )
    }

    private fun formFactor(context: Context): String {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        return when (uiModeManager?.currentModeType) {
            Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            Configuration.UI_MODE_TYPE_WATCH,
            Configuration.UI_MODE_TYPE_CAR,
            Configuration.UI_MODE_TYPE_APPLIANCE,
            Configuration.UI_MODE_TYPE_VR_HEADSET,
            -> "other"
            else -> when {
                context.packageManager.hasSystemFeature(FEATURE_PC) -> "desktop"
                context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_SW_DP -> "tablet"
                else -> "phone"
            }
        }
    }

    /** `PackageManager.FEATURE_PC`: a desktop or laptop, such as a Chromebook. */
    private const val FEATURE_PC = "android.hardware.type.pc"

    /**
     * Checks StorageManager's volume metadata instead of counting
     * `getExternalFilesDirs(null)` entries, because secondary app-visible
     * external volumes are not necessarily removable SD cards.
     */
    private fun sdCardAvailable(context: Context): Boolean {
        val storageManager = context.getSystemService(StorageManager::class.java) ?: return false
        return storageManager.storageVolumes.any(::isMountedRemovableVolume)
    }

    private fun isMountedRemovableVolume(volume: StorageVolume): Boolean {
        val state = runCatching { volume.state }.getOrNull()
        return volume.isRemovable &&
            (state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY)
    }

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
