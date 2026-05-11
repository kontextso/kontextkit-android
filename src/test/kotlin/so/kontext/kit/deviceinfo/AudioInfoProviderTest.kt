package so.kontext.kit.deviceinfo

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
class AudioInfoProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // -- volume --

    @Test
    fun `collect returns volume in 0 to 100 range`() {
        assert(AudioInfoProvider.collect(context).volume in 0..100)
    }

    // -- muted (pins both branches of the OR explicitly) --

    @Test
    fun `muted is true when volume is zero (pins the volumePercent==0 branch)`() {
        // Even with isStreamMute=false, volume=0 should mark muted=true.
        // Catches the case v3 missed (volume slider at 0, system stream-mute
        // not set).
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        assertTrue(AudioInfoProvider.collect(context).muted)
    }

    @Test
    fun `muted is false when volume is non-zero and stream not muted`() {
        // Negative case — neither branch of the OR fires.
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        assertFalse(AudioInfoProvider.collect(context).muted)
    }

    // -- outputPluggedIn / outputType (real shadow-driven assertions) --

    @Test
    fun `outputPluggedIn is true and outputType contains wired when a wired headset is connected`() {
        val wiredHeadset = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            .build()
        shadowOf(audioManager).setOutputDevices(listOf(wiredHeadset))

        val info = AudioInfoProvider.collect(context)
        assertTrue("Expected outputPluggedIn=true with wired headset", info.outputPluggedIn)
        assertTrue("Expected 'wired' in outputType, got: ${info.outputType}", "wired" in info.outputType)
    }

    @Test
    fun `outputPluggedIn ignores builtin speaker (would otherwise be permanently true)`() {
        // The whole point of the built-in ignore list — without it the flag
        // is useless because BUILTIN_SPEAKER is always present.
        val speaker = AudioDeviceInfoBuilder.newBuilder()
            .setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            .build()
        shadowOf(audioManager).setOutputDevices(listOf(speaker))

        val info = AudioInfoProvider.collect(context)
        assertFalse(info.outputPluggedIn)
        assertTrue(info.outputType.isEmpty())
    }

    // -- isSoundOn (preserved from earlier coverage) --

    @Test
    fun `isSoundOn returns true when music volume is above zero`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 5, 0)
        assertTrue(AudioInfoProvider.isSoundOn(context))
    }

    @Test
    fun `isSoundOn returns false when music volume is zero`() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        assertFalse(AudioInfoProvider.isSoundOn(context))
    }
}
