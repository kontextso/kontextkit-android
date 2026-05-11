package so.kontext.kit.deviceinfo

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/**
 * Reports battery + power-saver state for ad targeting. Mirrors iOS
 * `BatteryInfoProvider`.
 *
 * `batteryState` collapses Android's raw status enum into the four values
 * the server's `powerSchema` accepts (`charging`, `full`, `unplugged`,
 * `unknown`) — the platform distinguishes `DISCHARGING` (on battery) from
 * `NOT_CHARGING` (plugged in but not pulling current), but the server
 * doesn't, so both map to `unplugged`. `batteryLevel` is nullable to
 * encode "BatteryManager unavailable" rather than emitting a misleading
 * sentinel like `-1` or `0`.
 */
public object BatteryInfoProvider {

    public data class BatteryInfo(
        val batteryLevel: Double?,
        val batteryState: String,
        /**
         * Whether the user-facing power-saving toggle is on — Android's
         * "Battery Saver" (Settings → Battery → Battery Saver), the
         * semantic equivalent of iOS Low Power Mode. NOT the deeper Doze /
         * idle states or the API 33+ "low power standby" feature.
         */
        val lowPowerMode: Boolean,
    )

    public fun collect(context: Context): BatteryInfo {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val batteryLevel = if (level != null && level >= 0) level.toDouble() else null

        val status = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
        val batteryState = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            -> "unplugged"
            else -> "unknown"
        }

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        return BatteryInfo(
            batteryLevel = batteryLevel,
            batteryState = batteryState,
            // PowerManager always exists in practice; the `?: false` is
            // defensive against stripped AOSP forks. iOS can't hit this
            // branch — `ProcessInfo` always exists. `false` is the safer
            // default: server gets normal targeting rather than a degraded
            // variant when we can't be sure of the user's setting.
            lowPowerMode = powerManager?.isPowerSaveMode ?: false,
        )
    }

    /** Dictionary representation for bridge layers (RN, Flutter). Mirrors iOS. */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        val dict = mutableMapOf<String, Any>(
            "batteryState" to info.batteryState,
            "lowPowerMode" to info.lowPowerMode,
        )
        info.batteryLevel?.let { dict["batteryLevel"] = it }
        return dict
    }
}
