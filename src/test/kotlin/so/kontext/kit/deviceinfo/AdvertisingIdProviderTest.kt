package so.kontext.kit.deviceinfo

// Requires: JUnit 4, Robolectric 4.x, kotlinx-coroutines-test on the test classpath.
// Run with: ./gradlew :KontextKit:testDebugUnitTest --tests "so.kontext.kit.AdvertisingIdProviderTest"
//
// Note: AdvertisingIdProvider depends on Google Play Services AdvertisingIdClient,
// which is not available in a unit-test environment. These tests verify the error
// path (graceful fallback when Play Services is absent) plus the pure helpers
// (applyLatFilter, normalize) directly.

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AdvertisingIdProviderTest {

    @Test
    fun `collect returns null advertisingId when Play Services is unavailable`() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()

        // In a Robolectric environment Google Play Services is not present,
        // so AdvertisingIdClient.getAdvertisingIdInfo will throw.
        // The implementation catches all exceptions and returns a fallback Result.
        val result = AdvertisingIdProvider.collect(context)

        assertNull(
            "advertisingId should be null when Play Services is unavailable",
            result.advertisingId,
        )
        assertFalse(
            "isLimitAdTrackingEnabled should be false on the fallback path",
            result.isLimitAdTrackingEnabled,
        )
    }

    // -- applyLatFilter (pure, the privacy rule that matters) --

    @Test
    fun `applyLatFilter returns null when LAT enabled, regardless of advertisingId`() {
        // Privacy contract: when the user opted into Limit Ad Tracking, the
        // GAID must not leave the device — even if Play Services hands one back.
        val withLatAndId = AdvertisingIdProvider.Result(
            advertisingId = "abc-123",
            isLimitAdTrackingEnabled = true,
        )
        assertNull(AdvertisingIdProvider.applyLatFilter(withLatAndId))
    }

    @Test
    fun `applyLatFilter passes advertisingId through when LAT disabled`() {
        val withoutLat = AdvertisingIdProvider.Result(
            advertisingId = "abc-123",
            isLimitAdTrackingEnabled = false,
        )
        assertEquals("abc-123", AdvertisingIdProvider.applyLatFilter(withoutLat))
    }

    @Test
    fun `applyLatFilter returns null when no advertisingId regardless of LAT`() {
        val noIdLatOff = AdvertisingIdProvider.Result(
            advertisingId = null,
            isLimitAdTrackingEnabled = false,
        )
        val noIdLatOn = AdvertisingIdProvider.Result(
            advertisingId = null,
            isLimitAdTrackingEnabled = true,
        )
        assertNull(AdvertisingIdProvider.applyLatFilter(noIdLatOff))
        assertNull(AdvertisingIdProvider.applyLatFilter(noIdLatOn))
    }

    // -- normalize (mirrors iOS, defends against zero-UUID + empty wire shapes) --

    @Test
    fun `normalize passes a real GAID through unchanged`() {
        val gaid = "38400000-8cf0-11bd-b23e-10b96e40000d"
        assertEquals(gaid, AdvertisingIdProvider.normalize(gaid))
    }

    @Test
    fun `normalize maps null, empty, and whitespace to null`() {
        assertNull(AdvertisingIdProvider.normalize(null))
        assertNull(AdvertisingIdProvider.normalize(""))
        assertNull(AdvertisingIdProvider.normalize("   "))
        assertNull(AdvertisingIdProvider.normalize("\t  \n"))
    }

    @Test
    fun `normalize maps the zero UUID to null`() {
        // Play Services returns this after the user enabled "Delete advertising
        // ID" on Android 12+ or during a reset — looks like a UUID, identifies
        // nobody.
        assertNull(AdvertisingIdProvider.normalize("00000000-0000-0000-0000-000000000000"))
    }

    @Test
    fun `normalize is case-insensitive for the zero UUID`() {
        // Defensive: real-world GAIDs are lowercase but defending against an
        // upper-cased zero UUID costs nothing.
        assertNull(AdvertisingIdProvider.normalize("00000000-0000-0000-0000-000000000000".uppercase()))
    }

    // -- resolveId (mirrors iOS resolveIds, modulo the IDFV that Android lacks) --

    @Test
    fun `resolveId returns normalised manual override when present`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val gaid = "38400000-8cf0-11bd-b23e-10b96e40000d"
        // Manual wins — system path isn't even consulted (and would be null on
        // Robolectric anyway since Play Services is absent).
        assertEquals(gaid, AdvertisingIdProvider.resolveId(context, gaid))
    }

    @Test
    fun `resolveId falls back to system when manual is null`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // No manual → system path → null on Robolectric (Play Services unavailable).
        assertNull(AdvertisingIdProvider.resolveId(context, null))
        assertNull(AdvertisingIdProvider.resolveId(context))
    }

    @Test
    fun `resolveId rejects empty manual and falls back to system`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        // Buggy publisher: AdsBuilder.advertisingId("") used to forward "" to
        // the server; now it falls back to system (null on Robolectric).
        assertNull(AdvertisingIdProvider.resolveId(context, ""))
    }

    @Test
    fun `resolveId rejects whitespace manual and falls back to system`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNull(AdvertisingIdProvider.resolveId(context, "   "))
        assertNull(AdvertisingIdProvider.resolveId(context, "\t\n  "))
    }

    @Test
    fun `resolveId rejects zero UUID manual and falls back to system`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertNull(AdvertisingIdProvider.resolveId(context, "00000000-0000-0000-0000-000000000000"))
        assertNull(
            AdvertisingIdProvider.resolveId(
                context,
                "00000000-0000-0000-0000-000000000000".uppercase(),
            ),
        )
    }

    @Test
    fun `resolveId works with null context if manual is valid`() {
        // Session can be constructed before context is wired; resolveId then
        // only consults the manual-override path.
        val gaid = "38400000-8cf0-11bd-b23e-10b96e40000d"
        assertEquals(gaid, AdvertisingIdProvider.resolveId(null, gaid))
    }

    @Test
    fun `resolveId returns null with null context and null or invalid manual`() {
        assertNull(AdvertisingIdProvider.resolveId(null, null))
        assertNull(AdvertisingIdProvider.resolveId(null, ""))
        assertNull(AdvertisingIdProvider.resolveId(null, "00000000-0000-0000-0000-000000000000"))
    }
}
