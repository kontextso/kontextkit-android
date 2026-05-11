package so.kontext.kit.deviceinfo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreenInfoProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `collect returns positive dimensions`() {
        val info = ScreenInfoProvider.collect(context)
        assert(info.width > 0)
        assert(info.height > 0)
    }

    @Test
    fun `collect returns positive dpr`() {
        val info = ScreenInfoProvider.collect(context)
        assert(info.dpr > 0)
    }

    @Test
    fun `collect returns valid orientation`() {
        val info = ScreenInfoProvider.collect(context)
        assert(info.orientation in listOf("portrait", "landscape"))
    }

    @Test
    fun `collect returns boolean darkMode`() {
        val info = ScreenInfoProvider.collect(context)
        // Just verify it doesn't crash — value depends on system config
        assert(info.darkMode || !info.darkMode)
    }

    @Test
    fun `collect returns brightness in 0-100 range`() {
        // BrightnessManager normalises raw 0–255 to 0–100; falls back to 50.0
        // when SCREEN_BRIGHTNESS isn't readable (Robolectric default).
        val info = ScreenInfoProvider.collect(context)
        assert(info.brightness in 0.0..100.0)
    }
}
