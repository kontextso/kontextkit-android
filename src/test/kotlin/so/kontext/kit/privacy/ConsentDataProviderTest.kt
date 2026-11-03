package so.kontext.kit.privacy

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The same cases as iOS's `ConsentDataProviderTests`, on the app's default `SharedPreferences`. */
@RunWith(RobolectricTestRunner::class)
class ConsentDataProviderTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)

    private fun collect() = ConsentDataProvider.collect(context)

    @Test
    fun `nothing stored reads as all null`() {
        assertEquals(ConsentDataProvider.ConsentData(null, null, null, null, null), collect())
    }

    @Test
    fun `the TC string is read, and an empty or blank one is null`() {
        prefs().edit().putString("IABTCF_TCString", "CO9-VxxO9-VxxAQAAA==").commit()
        assertEquals("CO9-VxxO9-VxxAQAAA==", collect().gdprConsent)
        prefs().edit().putString("IABTCF_TCString", "").commit()
        assertNull(collect().gdprConsent)
        prefs().edit().putString("IABTCF_TCString", "   ").commit()
        assertNull(collect().gdprConsent)
    }

    @Test
    fun `gdprApplies is read whatever type the CMP stored`() {
        prefs().edit().putInt("IABTCF_gdprApplies", 1).commit()
        assertEquals(1, collect().gdpr)
        prefs().edit().putInt("IABTCF_gdprApplies", 0).commit()
        assertEquals(0, collect().gdpr)
        prefs().edit().putBoolean("IABTCF_gdprApplies", true).commit()
        assertEquals(1, collect().gdpr)
        prefs().edit().putString("IABTCF_gdprApplies", " 1 ").commit()
        assertEquals(1, collect().gdpr)
        prefs().edit().putLong("IABTCF_gdprApplies", 0L).commit()
        assertEquals(0, collect().gdpr)
    }

    @Test
    fun `gdprApplies other than 0 or 1 is null`() {
        for (value in listOf(5, -1, 2)) {
            prefs().edit().putInt("IABTCF_gdprApplies", value).commit()
            assertNull("$value", collect().gdpr)
        }
        for (value in listOf("5", "abc")) {
            prefs().edit().putString("IABTCF_gdprApplies", value).commit()
            assertNull(value, collect().gdpr)
        }
        prefs().edit().putFloat("IABTCF_gdprApplies", 1f).commit()
        assertNull("a float is not a CMP's value", collect().gdpr)
    }

    @Test
    fun `the GPP string and its sections are read`() {
        prefs().edit()
            .putString("IABGPP_HDR_GppString", "DBABMA~CPXxRfAPXxRfAAfKABENB-CgAAAAAAAAAAYgAAAAAAAA")
            .putString("IABGPP_GppSID", "2_7")
            .commit()
        val consent = collect()
        assertEquals("DBABMA~CPXxRfAPXxRfAAfKABENB-CgAAAAAAAAAAYgAAAAAAAA", consent.gpp)
        assertEquals(listOf(2, 7), consent.gppSid)
    }

    @Test
    fun `GPP sections are read as CMPs write them`() {
        prefs().edit().putString("IABGPP_GppSID", "2,7").commit()
        assertEquals(listOf(2, 7), collect().gppSid)
        prefs().edit().putInt("IABGPP_GppSID", 7).commit()
        assertEquals(listOf(7), collect().gppSid)
        prefs().edit().putString("IABGPP_GppSID", "2_x_7").commit()
        assertEquals("parts that are not numbers are skipped, as on iOS", listOf(2, 7), collect().gppSid)
        prefs().edit().putString("IABGPP_GppSID", "").commit()
        assertNull(collect().gppSid)
        prefs().edit().putString("IABGPP_GppSID", "a_b").commit()
        assertNull(collect().gppSid)
    }

    @Test
    fun `the US Privacy string is read, and a blank one is null`() {
        prefs().edit().putString("IABUSPrivacy_String", "1YNN").commit()
        assertEquals("1YNN", collect().usPrivacy)
        prefs().edit().putString("IABUSPrivacy_String", " ").commit()
        assertNull(collect().usPrivacy)
    }

    @Test
    fun `a value of another type than expected is null, never a crash`() {
        prefs().edit()
            .putInt("IABTCF_TCString", 3)
            .putBoolean("IABGPP_HDR_GppString", true)
            .putLong("IABUSPrivacy_String", 9L)
            .commit()
        val consent = collect()
        assertNull(consent.gdprConsent)
        assertNull(consent.gpp)
        assertNull(consent.usPrivacy)
    }

    @Test
    fun `the map form has the regulatory schema's keys`() {
        prefs().edit()
            .putInt("IABTCF_gdprApplies", 1)
            .putString("IABTCF_TCString", "tc")
            .putString("IABGPP_HDR_GppString", "g")
            .putString("IABGPP_GppSID", "2")
            .putString("IABUSPrivacy_String", "1YNN")
            .commit()
        assertEquals(
            mapOf("gdpr" to 1, "gdprConsent" to "tc", "gpp" to "g", "gppSid" to listOf(2), "usPrivacy" to "1YNN"),
            ConsentDataProvider.collectAsDict(context),
        )
    }
}
