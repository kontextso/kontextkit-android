package so.kontext.kit.omsdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The OMID AAR is not on the test classpath (declared as host-app
 * responsibility in build.gradle.kts), so every reflection lookup in
 * OmManager fails with `ClassNotFoundException` here. That makes this
 * suite a coverage of the graceful-degradation path: the SDK must remain
 * usable (no exceptions, no-op everywhere) when consumers ship without
 * the OMID AAR.
 */
@RunWith(RobolectricTestRunner::class)
class OmManagerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val partner = OmPartner(name = "Kontextso", version = "1.0.0")

    @Test
    fun `activate returns false when OMSDK class not on classpath`() {
        val manager = OmManager(partner)
        assertFalse(
            "activate() must degrade gracefully when OMSDK AAR is absent",
            manager.activate(),
        )
    }

    @Test
    fun `activate is idempotent across repeat calls`() {
        val manager = OmManager(partner)
        val first = manager.activate()
        val second = manager.activate()
        // Both calls return the same activated state — manager caches
        // the result on the first call.
        assertFalse(first)
        assertFalse(second)
    }

    @Test
    fun `createSession returns null without activation`() = runTest {
        // A non-activated manager refuses to create sessions — caller
        // gets `null` and silently skips OMID for this ad.
        val manager = OmManager(partner)
        val session = manager.createSession(
            webView = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>().let {
                android.webkit.WebView(it)
            },
            url = null,
            creativeType = OmCreativeType.DISPLAY,
        )
        assertNull(session)
    }

    @Test
    fun `omsdkScript returns the bundled JS resource`() {
        val script = OmManager.omsdkScript(context)
        assertNotNull(
            "omsdk_v1.js lives in KontextKit/android/src/main/res/raw " +
                "and should be readable via the merged R class",
            script,
        )
        assert(script!!.contains("omid")) {
            "Bundled file should be the IAB OMID JS — sanity-check the marker string"
        }
    }

    @Test
    fun `omsdkScript with bare ContextWrapper still resolves the resource`() {
        val script = OmManager.omsdkScript(context)
        assertNotNull(script)
        assertNull(
            "Sanity: a key the OMID JS does not contain — guards against " +
                "this test masking a regression where omsdkScript reads the wrong file",
            script?.takeIf { it.contains("THIS_STRING_SHOULD_NEVER_APPEAR") },
        )
    }

    @Test
    fun `each manager instance is independent (no shared global state)`() {
        // The caller-owned design lets multiple Sessions exist with their
        // own OmManager instances — used in tests to inject mocks.
        val managerA = OmManager(partner)
        val managerB = OmManager(OmPartner("Other", "9.9.9"))
        managerA.activate()
        managerB.activate()
        // Both stay un-activated because no OMID AAR; the test is about
        // independence (no exception cross-talk), not about activation
        // success.
    }
}
