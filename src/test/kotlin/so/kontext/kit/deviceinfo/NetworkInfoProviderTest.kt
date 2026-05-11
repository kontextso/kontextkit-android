package so.kontext.kit.deviceinfo

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class NetworkInfoProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val cachedUserAgentField =
        NetworkInfoProvider::class.java.getDeclaredField("cachedUserAgent").apply { isAccessible = true }

    @After
    fun tearDown() {
        // Reset the singleton cache between tests so cache-mutation tests
        // don't bleed into the others. NetworkInfoProvider is an `object`,
        // so its state persists across the JVM.
        cachedUserAgentField.set(NetworkInfoProvider, null)
    }

    @Test
    fun `collect returns valid network type`() {
        val info = NetworkInfoProvider.collect(context)
        assert(info.type in listOf("wifi", "cellular", "ethernet", "other"))
    }

    @Test
    fun `collect returns detail as valid string or null`() {
        val info = NetworkInfoProvider.collect(context)
        if (info.detail != null) {
            assert(info.detail in listOf("gprs", "edge", "2g", "3g", "hspa", "lte", "5g"))
        }
    }

    @Test
    fun `carrier returns operator name when set and on cellular`() {
        // Default Robolectric env reports cellular transport, so carrier
        // should pass through unchanged when operatorName is non-empty.
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setNetworkOperatorName("Vodafone")

        assertEquals("Vodafone", NetworkInfoProvider.collect(context).carrier)
    }

    @Test
    fun `carrier is null when network operator name is empty (pins the empty-string filter)`() {
        // v4 added `?.takeIf { it.isNotEmpty() }` over v3's bare passthrough —
        // empty `networkOperatorName` happens with no SIM but cellular
        // transport active. Without the filter this would leak "" to the
        // wire.
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        shadowOf(tm).setNetworkOperatorName("")

        assertNull(NetworkInfoProvider.collect(context).carrier)
    }

    @Test
    fun `collect returns userAgent as string or null`() {
        val info = NetworkInfoProvider.collect(context)
        // userAgent may be null in the test environment when WebView isn't
        // initialised; otherwise it must be a non-empty string.
        assert(info.userAgent == null || info.userAgent!!.isNotEmpty())
    }

    @Test
    fun `userAgent uses cached value across collect calls`() {
        // Pin the v3+iOS parity fix: WebSettings.getDefaultUserAgent must
        // not be re-invoked once a value is cached. Force the cache to a
        // sentinel via reflection — if the cache is read first, the
        // sentinel comes through; if the impl re-computes, real WebView
        // UA wins and the assertion fails.
        cachedUserAgentField.set(NetworkInfoProvider, "test-sentinel-ua")

        val info = NetworkInfoProvider.collect(context)
        assertEquals("test-sentinel-ua", info.userAgent)
    }

    @Test
    fun `userAgent populates cache after first successful read`() {
        cachedUserAgentField.set(NetworkInfoProvider, null)

        val first = NetworkInfoProvider.collect(context).userAgent
        // Robolectric's WebView may return null in some envs; only assert
        // population when the read succeeded.
        if (first != null) {
            val cachedAfter = cachedUserAgentField.get(NetworkInfoProvider) as String?
            assertEquals(
                "First successful UA read should populate the cache",
                first,
                cachedAfter,
            )
        }
    }
}
