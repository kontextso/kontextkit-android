package so.kontext.kit.deviceinfo

// Requires: JUnit 4, Robolectric 4.x, androidx.test on the test classpath.
// Run with: ./gradlew :KontextKit:testDebugUnitTest --tests "so.kontext.kit.BrightnessManagerTest"

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrightnessManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    // -- get --

    @Test
    fun `get returns 0-100 percentage from raw 0-255 setting`() {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            128,
        )

        val brightness = BrightnessManager.get(context)
        // 128 / 255 * 100 ~= 50.196
        assertEquals(50.196, brightness, 0.01)
    }

    @Test
    fun `get returns 0 for minimum brightness`() {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            0,
        )

        assertEquals(0.0, BrightnessManager.get(context), 0.001)
    }

    @Test
    fun `get returns 100 for maximum brightness`() {
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            255,
        )

        assertEquals(100.0, BrightnessManager.get(context), 0.001)
    }

    @Test
    fun `get returns value in valid range when setting is missing`() {
        // If SCREEN_BRIGHTNESS is unreadable, implementation falls back to 50.
        // Robolectric may or may not throw; either way the value must be 0-100.
        val brightness = BrightnessManager.get(context)
        assertTrue("Brightness should be >= 0", brightness >= 0.0)
        assertTrue("Brightness should be <= 100", brightness <= 100.0)
    }

    // -- set --

    @Test
    fun `set returns the post-clamp applied value`() {
        assertEquals(50.0, BrightnessManager.set(context, 50.0), 0.01)
        assertEquals(0.0, BrightnessManager.set(context, 0.0), 0.01)
        assertEquals(100.0, BrightnessManager.set(context, 100.0), 0.01)
    }

    @Test
    fun `set clamps values below zero`() {
        assertEquals(0.0, BrightnessManager.set(context, -50.0), 0.01)
        assertEquals(0.0, BrightnessManager.set(context, -9999.0), 0.01)
    }

    @Test
    fun `set clamps values above 100`() {
        assertEquals(100.0, BrightnessManager.set(context, 150.0), 0.01)
        assertEquals(100.0, BrightnessManager.set(context, 9999.0), 0.01)
    }

    @Test
    fun `set writes the expected raw value to SCREEN_BRIGHTNESS`() {
        // 50.0 percent → 127 raw (because 0.5 * 255 = 127.5, .toInt() truncates).
        BrightnessManager.set(context, 50.0)
        assertEquals(
            127,
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
        )

        BrightnessManager.set(context, 100.0)
        assertEquals(
            255,
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
        )

        BrightnessManager.set(context, 0.0)
        assertEquals(
            0,
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
        )
    }

    @Test
    fun `set then get round-trips within tolerance`() {
        // .toInt() truncation in percentToRaw means a 75.0 round-trip lands
        // at ~74.9, not exactly 75. Tolerance accounts for that one-step gap.
        BrightnessManager.set(context, 75.0)
        assertEquals(75.0, BrightnessManager.get(context), 0.4)
    }

    // -- helpers (internal, exercised directly so the rounding edges are pinned) --

    @Test
    fun `percentToRaw maps endpoints exactly`() {
        assertEquals(0, BrightnessManager.percentToRaw(0.0))
        assertEquals(255, BrightnessManager.percentToRaw(100.0))
    }

    @Test
    fun `percentToRaw truncates toward zero at midpoint`() {
        // 0.5 * 255 = 127.5; .toInt() truncates to 127, not rounds to 128.
        assertEquals(127, BrightnessManager.percentToRaw(50.0))
    }

    @Test
    fun `rawToPercent maps endpoints exactly`() {
        assertEquals(0.0, BrightnessManager.rawToPercent(0), 0.0001)
        assertEquals(100.0, BrightnessManager.rawToPercent(255), 0.0001)
    }

    @Test
    fun `rawToPercent maps midpoint to expected fraction`() {
        // 128 / 255 * 100 ≈ 50.196 — same number the public-API get test asserts.
        assertEquals(50.196, BrightnessManager.rawToPercent(128), 0.001)
    }
}
