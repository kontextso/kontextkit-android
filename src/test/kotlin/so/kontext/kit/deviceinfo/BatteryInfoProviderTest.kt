package so.kontext.kit.deviceinfo

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BatteryInfoProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `collect returns valid battery state`() {
        val info = BatteryInfoProvider.collect(context)
        assert(info.batteryState in listOf("charging", "full", "unplugged", "unknown"))
    }

    @Test
    fun `collect returns battery level in range or null`() {
        val info = BatteryInfoProvider.collect(context)
        if (info.batteryLevel != null) {
            assert(info.batteryLevel!! in 0.0..100.0)
        }
    }

    @Test
    fun `collect reflects PowerManager isPowerSaveMode true`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIsPowerSaveMode(true)
        assertEquals(true, BatteryInfoProvider.collect(context).lowPowerMode)
    }

    @Test
    fun `collect reflects PowerManager isPowerSaveMode false`() {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIsPowerSaveMode(false)
        assertEquals(false, BatteryInfoProvider.collect(context).lowPowerMode)
    }

    @Test
    fun `collect maps BATTERY_STATUS_CHARGING to charging`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(
            BatteryManager.BATTERY_PROPERTY_STATUS,
            BatteryManager.BATTERY_STATUS_CHARGING,
        )
        assertEquals("charging", BatteryInfoProvider.collect(context).batteryState)
    }
}
