package so.kontext.kit.deviceinfo

import android.content.Context
import android.provider.Settings

/**
 * Owns the device's screen brightness — read and write.
 *
 * Mirrors iOS `BrightnessManager` so every KontextKit consumer
 * (sdk-kotlin, sdk-react-native, sdk-flutter) sees the same 0–100 scale
 * (matches `audio.volume` and `power.batteryLevel`).
 *
 * Note: `set` requires the host app to hold `WRITE_SETTINGS` permission;
 * without it Android silently no-ops (different from iOS, which has no
 * permission gate).
 */
public object BrightnessManager {

    /** Android's `SCREEN_BRIGHTNESS` setting is stored as an int 0..255. */
    private const val MAX_RAW_BRIGHTNESS = 255.0

    /** Public scale exposed to callers — matches `audio.volume` and `power.batteryLevel`. */
    private const val PERCENT_MAX = 100.0

    /** Mid-scale fallback returned when the system setting can't be read. */
    private const val FALLBACK_PERCENT = 50.0

    /**
     * Returns the current screen brightness as a percentage, 0–100.
     * Falls back to `50.0` if `SCREEN_BRIGHTNESS` is not readable.
     */
    public fun get(context: Context): Double = try {
        rawToPercent(
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
        )
    } catch (_: Settings.SettingNotFoundException) {
        FALLBACK_PERCENT
    }

    /**
     * Sets the screen brightness. Value is clamped to 0..100. Returns the
     * actual value applied (post-clamp).
     *
     * **Permission required.** Writing `Settings.System.SCREEN_BRIGHTNESS`
     * needs `android.permission.WRITE_SETTINGS`, which is a *Special
     * permission* — the host app must declare it in its manifest AND
     * request user grant via `Settings.ACTION_MANAGE_WRITE_SETTINGS`
     * (a settings-app screen, not a runtime prompt). KontextKit
     * deliberately does not declare this permission so apps that don't
     * need ad-driven brightness aren't forced to justify it at Play
     * Store review.
     *
     * When the permission isn't granted, this method silently no-ops
     * (returns the clamped value but doesn't write). That's intentional
     * graceful degradation — callers can use the returned value
     * uniformly without per-call permission checks.
     */
    public fun set(context: Context, value: Double): Double {
        val clamped = value.coerceIn(0.0, PERCENT_MAX)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                percentToRaw(clamped),
            )
        } catch (_: SecurityException) {
            // Host app didn't declare/grant WRITE_SETTINGS — no-op by design.
        }
        return clamped
    }

    /**
     * Maps a 0–100 percentage to Android's raw 0–255 SCREEN_BRIGHTNESS scale.
     * `.toInt()` truncates toward zero (e.g. `50.0` → `127`, since
     * `0.5 * 255 = 127.5`); the rounding is asymmetric but consistent.
     * Internal so the test source set can verify the rounding edges directly.
     */
    internal fun percentToRaw(percent: Double): Int =
        ((percent / PERCENT_MAX) * MAX_RAW_BRIGHTNESS).toInt()

    /**
     * Maps Android's raw 0–255 SCREEN_BRIGHTNESS scale to a 0–100 percentage.
     * Inverse of [percentToRaw], modulo `.toInt()` truncation in the other
     * direction (so a round-trip is within ~0.4 of the original).
     */
    internal fun rawToPercent(raw: Int): Double =
        (raw / MAX_RAW_BRIGHTNESS) * PERCENT_MAX
}
