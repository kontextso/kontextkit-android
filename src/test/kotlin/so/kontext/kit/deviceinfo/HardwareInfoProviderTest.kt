package so.kontext.kit.deviceinfo

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class HardwareInfoProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `collect returns non-empty brand`() {
        assert(HardwareInfoProvider.collect(context).brand.isNotEmpty())
    }

    @Test
    fun `collect returns non-empty model`() {
        assert(HardwareInfoProvider.collect(context).model.isNotEmpty())
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun `collect classifies sw600dp width as tablet`() {
        // AOSP's sw≥600dp resource-qualifier threshold — same one used to
        // pick values-sw600dp/ tablet layouts.
        assertEquals("tablet", HardwareInfoProvider.collect(context).type)
    }

    @Test
    @Config(qualifiers = "sw360dp")
    fun `collect classifies sw360dp width as phone`() {
        // The OpenRTB-derived set iOS uses: a phone, not a handset.
        assertEquals("phone", HardwareInfoProvider.collect(context).type)
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun `collect classifies a pc as desktop, before tablet`() {
        shadowOf(context.packageManager).setSystemFeature("android.hardware.type.pc", true)
        assertEquals("desktop", HardwareInfoProvider.collect(context).type)
    }

    @Test
    fun `collect classifies a watch, a car, an appliance and a vr headset as other`() {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        for (mode in listOf(
            Configuration.UI_MODE_TYPE_WATCH,
            Configuration.UI_MODE_TYPE_CAR,
            Configuration.UI_MODE_TYPE_APPLIANCE,
            Configuration.UI_MODE_TYPE_VR_HEADSET,
        )) {
            shadowOf(uiModeManager).setCurrentModeType(mode)
            assertEquals("mode $mode", "other", HardwareInfoProvider.collect(context).type)
        }
    }

    @Test
    fun `every type is one of the OpenRTB-derived set`() {
        assert(HardwareInfoProvider.collect(context).type in setOf("phone", "tablet", "tv", "desktop", "other"))
    }

    @Test
    fun `collect returns tv when UiModeManager reports television`() {
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        assertEquals("tv", HardwareInfoProvider.collect(context).type)
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun `collect prioritises tv over tablet when UiModeManager reports television`() {
        // Pins the if/else ordering: TV detection wins even when sw≥600dp.
        // Without this test, swapping the branch order would silently mis-
        // classify large-screen Android TVs as "tablet".
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        assertEquals("tv", HardwareInfoProvider.collect(context).type)
    }

    @Test
    fun `collect returns plausible bootTime`() {
        val info = HardwareInfoProvider.collect(context)
        // bootTime must be in the past and not absurdly old (epoch ms).
        // Robolectric clock is the host clock, so 2010-01-01 is a safe
        // lower bound and "now" the upper.
        val year2010 = 1_262_304_000_000L
        assert(info.bootTime in year2010..System.currentTimeMillis())
    }

    @Test
    fun `collect returns sdCardAvailable as false on default Robolectric env`() {
        val info = HardwareInfoProvider.collect(context)
        // Robolectric exposes only primary emulated storage by default,
        // so no removable SD card is "mounted".
        assert(!info.sdCardAvailable)
    }
}
