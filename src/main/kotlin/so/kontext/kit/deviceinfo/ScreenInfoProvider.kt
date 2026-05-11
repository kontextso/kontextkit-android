package so.kontext.kit.deviceinfo

import android.content.Context
import android.content.res.Configuration

/**
 * Reports screen geometry, dark-mode state, and brightness for ad targeting
 * and creative sizing. Mirrors iOS `ScreenInfoProvider`.
 *
 * `width` / `height` are physical pixels (matching iOS's
 * `UIScreen.nativeBounds`), not density-independent — the ad server scales
 * creatives using `dpr` (the density multiplier) on its end. `darkMode`
 * follows the *system* night mode (`UI_MODE_NIGHT_YES`), not the app's
 * theme, because creative variants are picked based on what the user is
 * actually seeing across their device. `brightness` is read via
 * [BrightnessManager] (0–100, matching `audio.volume` and
 * `power.batteryLevel`) so callers see one cohesive screen-state object.
 */
public object ScreenInfoProvider {

    public data class ScreenInfo(
        val width: Int,
        val height: Int,
        val dpr: Double,
        val orientation: String,
        val darkMode: Boolean,
        val brightness: Double,
    )

    public fun collect(context: Context): ScreenInfo {
        val displayMetrics = context.resources.displayMetrics
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return ScreenInfo(
            width = displayMetrics.widthPixels,
            height = displayMetrics.heightPixels,
            dpr = displayMetrics.density.toDouble(),
            orientation = if (displayMetrics.widthPixels > displayMetrics.heightPixels) "landscape" else "portrait",
            darkMode = uiMode == Configuration.UI_MODE_NIGHT_YES,
            // Read via BrightnessManager so the 0–100 normalisation lives in
            // one place; otherwise this and BrightnessManager.get() could
            // drift on units (raw 0–255 vs 0–100).
            brightness = BrightnessManager.get(context),
        )
    }

    /** Dictionary representation for bridge layers (RN, Flutter). Mirrors iOS. */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        return mapOf(
            "width" to info.width,
            "height" to info.height,
            "dpr" to info.dpr,
            "orientation" to info.orientation,
            "darkMode" to info.darkMode,
            "brightness" to info.brightness,
        )
    }
}
