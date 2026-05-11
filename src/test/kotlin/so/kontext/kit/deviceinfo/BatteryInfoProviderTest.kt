package so.kontext.kit.deviceinfo

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `collect maps BATTERY_STATUS_FULL to full`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(
            BatteryManager.BATTERY_PROPERTY_STATUS,
            BatteryManager.BATTERY_STATUS_FULL,
        )
        assertEquals("full", BatteryInfoProvider.collect(context).batteryState)
    }

    @Test
    fun `collect maps BATTERY_STATUS_DISCHARGING to unplugged`() {
        // Android distinguishes DISCHARGING (on battery) from NOT_CHARGING
        // (plugged in but not pulling current), but the server's powerSchema
        // doesn't — both collapse to `unplugged`.
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(
            BatteryManager.BATTERY_PROPERTY_STATUS,
            BatteryManager.BATTERY_STATUS_DISCHARGING,
        )
        assertEquals("unplugged", BatteryInfoProvider.collect(context).batteryState)
    }

    @Test
    fun `collect maps BATTERY_STATUS_NOT_CHARGING to unplugged`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(
            BatteryManager.BATTERY_PROPERTY_STATUS,
            BatteryManager.BATTERY_STATUS_NOT_CHARGING,
        )
        assertEquals("unplugged", BatteryInfoProvider.collect(context).batteryState)
    }

    @Test
    fun `collect maps BATTERY_STATUS_UNKNOWN to unknown`() {
        // Robolectric defaults this property to `BATTERY_STATUS_UNKNOWN`
        // (= 1) when the test hasn't set it. The when-block's `else` branch
        // also covers any future status enum we don't know about.
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(
            BatteryManager.BATTERY_PROPERTY_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        )
        assertEquals("unknown", BatteryInfoProvider.collect(context).batteryState)
    }

    @Test
    fun `collect maps BATTERY_PROPERTY_CAPACITY -1 to null batteryLevel`() {
        // -1 is BatteryManager's sentinel for "property unavailable". We
        // surface that as null rather than emitting a misleading -1% level.
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, -1)
        assertNull(BatteryInfoProvider.collect(context).batteryLevel)
    }

    @Test
    fun `collect returns valid level when capacity is reported`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 73)
        assertEquals(73.0, BatteryInfoProvider.collect(context).batteryLevel!!, 0.0)
    }

    @Test
    fun `collectAsDict omits batteryLevel when null and includes when set`() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        // Null level → key absent. Letting the bridge layer infer "unknown"
        // from the missing key avoids forwarding a sentinel value (-1 / 0)
        // that the ad server would interpret literally.
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, -1)
        val dictWithoutLevel = BatteryInfoProvider.collectAsDict(context)
        assert(!dictWithoutLevel.containsKey("batteryLevel"))
        assert(dictWithoutLevel.containsKey("batteryState"))
        assert(dictWithoutLevel.containsKey("lowPowerMode"))

        // Non-null level → key present with the numeric value.
        shadowOf(bm).setIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY, 42)
        val dictWithLevel = BatteryInfoProvider.collectAsDict(context)
        assertEquals(42.0, dictWithLevel["batteryLevel"])
    }
}
