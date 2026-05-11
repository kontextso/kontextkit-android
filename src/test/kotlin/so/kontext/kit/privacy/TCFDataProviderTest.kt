package so.kontext.kit.privacy

// Requires: JUnit 4, Robolectric 4.x, androidx.test on the test classpath.
// Run with: ./gradlew :KontextKit:testDebugUnitTest --tests "so.kontext.kit.TCFDataProviderTest"

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TCFDataProviderTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clear shared preferences before each test
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().apply()
    }

    @Test
    fun `returns consent string when IABTCF_TCString is set`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("IABTCF_TCString", "test-consent-string").apply()

        val result = TCFDataProvider.collect(context)
        assertEquals("test-consent-string", result.gdprConsent)
    }

    @Test
    fun `returns gdpr 1 when IABTCF_gdprApplies is 1`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("IABTCF_gdprApplies", 1).apply()

        val result = TCFDataProvider.collect(context)
        assertEquals(1, result.gdpr)
    }

    @Test
    fun `returns gdpr 0 when IABTCF_gdprApplies is 0`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("IABTCF_gdprApplies", 0).apply()

        val result = TCFDataProvider.collect(context)
        assertEquals(0, result.gdpr)
    }

    @Test
    fun `returns null values when no TCF keys are present`() {
        val result = TCFDataProvider.collect(context)
        assertNull(result.gdpr)
        assertNull(result.gdprConsent)
    }

    @Test
    fun `returns both gdpr and consent string when both keys are set`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putInt("IABTCF_gdprApplies", 1)
            .putString("IABTCF_TCString", "full-consent-string")
            .apply()

        val result = TCFDataProvider.collect(context)
        assertEquals(1, result.gdpr)
        assertEquals("full-consent-string", result.gdprConsent)
    }

    @Test
    fun `returns null gdprConsent when IABTCF_TCString is not set but gdprApplies is`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putInt("IABTCF_gdprApplies", 1).apply()

        val result = TCFDataProvider.collect(context)
        assertEquals(1, result.gdpr)
        assertNull(result.gdprConsent)
    }

    @Test
    fun `rejects empty TCString`() {
        // CMP wrote an empty string — invalid per IAB spec, treat as absent.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("IABTCF_TCString", "").apply()

        val result = TCFDataProvider.collect(context)
        assertNull(result.gdprConsent)
    }

    @Test
    fun `rejects whitespace-only TCString`() {
        // Whitespace-only is also invalid — same defensive normalisation as iOS.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putString("IABTCF_TCString", "   ").apply()

        val result = TCFDataProvider.collect(context)
        assertNull(result.gdprConsent)
    }

    @Test
    fun `rejects out-of-range gdprApplies values`() {
        // Per IAB TCF v2.2, gdprApplies must be 0 or 1. Buggy CMPs writing
        // anything else (5, -1, 2, …) decay to null rather than being
        // forwarded to the ad server as junk.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.edit().putInt("IABTCF_gdprApplies", 5).apply()
        assertNull(TCFDataProvider.collect(context).gdpr)

        prefs.edit().putInt("IABTCF_gdprApplies", -1).apply()
        assertNull(TCFDataProvider.collect(context).gdpr)

        prefs.edit().putInt("IABTCF_gdprApplies", 2).apply()
        assertNull(TCFDataProvider.collect(context).gdpr)
    }

    @Test
    fun `accepts gdprApplies stored as a String`() {
        // Some misbehaving CMPs write the value as a String. `getInt` would
        // throw ClassCastException — defensive read parses it instead.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.edit().putString("IABTCF_gdprApplies", "1").apply()
        assertEquals(1, TCFDataProvider.collect(context).gdpr)

        prefs.edit().putString("IABTCF_gdprApplies", "0").apply()
        assertEquals(0, TCFDataProvider.collect(context).gdpr)

        // Out-of-range String still rejected.
        prefs.edit().putString("IABTCF_gdprApplies", "5").apply()
        assertNull(TCFDataProvider.collect(context).gdpr)

        // Unparseable String → null.
        prefs.edit().putString("IABTCF_gdprApplies", "abc").apply()
        assertNull(TCFDataProvider.collect(context).gdpr)
    }

    @Test
    fun `accepts gdprApplies stored as a Long when value fits in Int`() {
        // SharedPreferences offers no `putLong` for our key, but `getAll`
        // can surface Long values when keys are written by other code paths
        // sharing the same prefs file. 0/1 Longs round-trip cleanly.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.edit().putLong("IABTCF_gdprApplies", 1L).apply()
        assertEquals(1, TCFDataProvider.collect(context).gdpr)

        prefs.edit().putLong("IABTCF_gdprApplies", 0L).apply()
        assertEquals(0, TCFDataProvider.collect(context).gdpr)
    }

    @Test
    fun `rejects gdprApplies Long that overflows Int range`() {
        // A misbehaving CMP writing `Long.MAX_VALUE` must NOT silently
        // truncate to Int.MAX_VALUE (or worse, an in-range value via
        // overflow). The `raw.toInt().takeIf { it.toLong() == raw }` guard
        // catches the lossy conversion and decays to null.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.edit().putLong("IABTCF_gdprApplies", Long.MAX_VALUE).apply()
        assertNull(TCFDataProvider.collect(context).gdpr)

        prefs.edit().putLong("IABTCF_gdprApplies", Long.MIN_VALUE).apply()
        assertNull(TCFDataProvider.collect(context).gdpr)

        // Out-of-range Long values still rejected by the {0,1} filter even
        // if they fit in Int.
        prefs.edit().putLong("IABTCF_gdprApplies", 5L).apply()
        assertNull(TCFDataProvider.collect(context).gdpr)
    }

    @Test
    fun `accepts gdprApplies stored as a Boolean`() {
        // Some CMPs write a Bool — coerced to 1/0.
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.edit().putBoolean("IABTCF_gdprApplies", true).apply()
        assertEquals(1, TCFDataProvider.collect(context).gdpr)

        prefs.edit().putBoolean("IABTCF_gdprApplies", false).apply()
        assertEquals(0, TCFDataProvider.collect(context).gdpr)
    }

    @Test
    fun `collectAsDict emits ad-server-matching keys (gdpr, gdprConsent)`() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit()
            .putInt("IABTCF_gdprApplies", 1)
            .putString("IABTCF_TCString", "consent-xyz")
            .apply()

        val dict = TCFDataProvider.collectAsDict(context)
        // Wire keys match the kontext ad-server's regulatorySchema (openRTB-style),
        // not the IAB TCF storage spec's wire names — keeps bridges aligned with
        // the request shape Preload actually sends.
        assertEquals("consent-xyz", dict["gdprConsent"])
        assertEquals(1, dict["gdpr"])
    }

    @Test
    fun `collectAsDict emits null entries when no TCF keys are present`() {
        val dict = TCFDataProvider.collectAsDict(context)
        // Keys are present but values are null — matches iOS NSNull semantics.
        assert(dict.containsKey("gdprConsent"))
        assert(dict.containsKey("gdpr"))
        assertNull(dict["gdprConsent"])
        assertNull(dict["gdpr"])
    }
}
