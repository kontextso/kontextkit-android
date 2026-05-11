package so.kontext.kit.deviceinfo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Reports audio state for ad targeting + OMID viewability. Mirrors iOS
 * `AudioInfoProvider`.
 *
 * Unlike iOS — which has `ensureSessionActive` to prime
 * `AVAudioSession.outputVolume` for KVO observation in OMID video —
 * Android's `AudioManager.getStreamVolume` is reliable without any
 * session-priming step, so no equivalent activator exists here.
 *
 * `volume` is normalised to 0–100 so all platforms agree on the shape
 * (Android's raw `STREAM_MUSIC` scale is device-dependent — usually 0..15
 * but not guaranteed). `outputPluggedIn` deliberately ignores the
 * built-in earpiece, speaker, and telephony devices: the OMID spec's
 * "plugged in" flag means an *external* output is connected (headphones,
 * Bluetooth, HDMI, USB), not just "any output device exists" — built-ins
 * are always present and would always be true otherwise.
 */
public object AudioInfoProvider {

    /** AudioManager rejected the system call — fall back to AOSP's default media-stream cap. */
    private const val FALLBACK_MAX_VOLUME = 15

    /** Public scale exposed to callers — matches `screen.brightness` and `power.batteryLevel`. */
    private const val PERCENT_MAX = 100

    public data class AudioInfo(
        val volume: Int,
        val muted: Boolean,
        val outputPluggedIn: Boolean,
        val outputType: List<String>,
    )

    /**
     * Returns `true` when the music stream's volume is above zero.
     * Mirrors iOS `AudioInfoProvider.isSoundOn()`.
     */
    public fun isSoundOn(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return volume > 0
    }

    public fun collect(context: Context): AudioInfo {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVolume = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: FALLBACK_MAX_VOLUME
        val currentVolume = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
        val volumePercent = if (maxVolume > 0) (currentVolume * PERCENT_MAX) / maxVolume else 0

        val outputTypes = mutableSetOf<String>()
        var hasExternalOutput = false
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                -> {
                    outputTypes.add("wired")
                    hasExternalOutput = true
                }
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                -> {
                    outputTypes.add("bluetooth")
                    hasExternalOutput = true
                }
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC,
                -> {
                    outputTypes.add("hdmi")
                    hasExternalOutput = true
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                -> {
                    outputTypes.add("usb")
                    hasExternalOutput = true
                }
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                AudioDeviceInfo.TYPE_TELEPHONY,
                -> { /* ignore built-in */ }
                else -> if (device.isSink) {
                    outputTypes.add("other")
                    hasExternalOutput = true
                }
            }
        }

        return AudioInfo(
            volume = volumePercent,
            // `|| volumePercent == 0` matches iOS's `volume < 0.01` semantic —
            // catches the "slider at 0, stream not system-muted" case that v3
            // missed (v3 only checked `isStreamMute`, which doesn't toggle
            // when the user simply drags volume to zero).
            muted = audioManager?.isStreamMute(AudioManager.STREAM_MUSIC) == true || volumePercent == 0,
            outputPluggedIn = hasExternalOutput,
            outputType = outputTypes.toList(),
        )
    }

    /** Dictionary representation for bridge layers (RN, Flutter). Mirrors iOS. */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        return mapOf(
            "volume" to info.volume,
            "muted" to info.muted,
            "outputPluggedIn" to info.outputPluggedIn,
            "outputType" to info.outputType,
        )
    }
}
