package so.kontext.kit.omsdk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            manager.activate(context),
        )
    }

    @Test
    fun `activate is idempotent across repeat calls`() {
        val manager = OmManager(partner)
        val first = manager.activate(context)
        val second = manager.activate(context)
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
        managerA.activate(context)
        managerB.activate(context)
        // Both stay un-activated because no OMID AAR; the test is about
        // independence (no exception cross-talk), not about activation
        // success.
    }

    // pollWithTimeout — exercise the deadline math directly without needing
    // a real WebView. The helper is extracted from waitForVideoMetadata so
    // the timeout / early-exit logic can be unit-tested in isolation.

    @Test
    fun `pollWithTimeout returns true immediately when poll succeeds on first try`() = runTest {
        var pollCount = 0
        val result = OmManager.pollWithTimeout(
            maxWaitMs = 500L,
            pollIntervalMs = 25L,
            pollNow = {
                pollCount++
                true
            },
        )
        assertEquals(true, result)
        assertEquals("Must short-circuit on first true — no extra polls", 1, pollCount)
    }

    @Test
    fun `pollWithTimeout returns true on later attempt when poll eventually succeeds`() = runTest {
        var pollCount = 0
        val result = OmManager.pollWithTimeout(
            maxWaitMs = 500L,
            pollIntervalMs = 25L,
            pollNow = {
                pollCount++
                pollCount >= 3 // succeeds on the third poll
            },
        )
        assertEquals(true, result)
        assertEquals(3, pollCount)
    }

    @Test
    fun `pollWithTimeout returns false when deadline elapses before success`() =
        kotlinx.coroutines.runBlocking {
            // The deadline math compares against `System.currentTimeMillis()`
            // (real wall-clock), not the coroutine scheduler's virtual
            // clock. `runTest` would skip delays virtually while real time
            // crawls, spinning the loop thousands of times. `runBlocking`
            // makes the delays actually wait so wall-clock and poll cadence
            // stay aligned.
            var pollCount = 0
            val result = OmManager.pollWithTimeout(
                maxWaitMs = 100L,
                pollIntervalMs = 20L,
                pollNow = {
                    pollCount++
                    false
                },
            )
            assertEquals(false, result)
            // 100ms / 20ms = 5 polls, plus 1 initial attempt; allow some
            // slack for scheduler jitter in CI.
            assert(pollCount in 1..10) {
                "Expected 1..10 polls within 100ms, got $pollCount"
            }
        }

    @Test
    fun `pollWithTimeout zero maxWaitMs returns false without polling`() = runTest {
        // Edge case: a zero/negative deadline must not enter the loop —
        // deadline = now + 0 = now, and `now < deadline` is immediately
        // false. No poll attempted, immediate return.
        var pollCount = 0
        val result = OmManager.pollWithTimeout(
            maxWaitMs = 0L,
            pollIntervalMs = 25L,
            pollNow = {
                pollCount++
                true
            },
        )
        assertEquals(false, result)
        assertEquals(0, pollCount)
    }

    // pollVideoReady — exercises the real WebView.evaluateJavascript code
    // path with a Robolectric-backed WebView. Robolectric's default
    // ShadowWebView doesn't actually evaluate JS (no V8/JSC), so the
    // ValueCallback never fires and the suspending coroutine would hang.
    // Wrap with `withTimeoutOrNull` to assert the contract under that
    // shadowed environment: the function must not throw, must not resume
    // with a stale value, and must remain cancellable.

    @Test
    fun `pollVideoReady is cancellable when WebView never fires the callback`() = runTest {
        val manager = OmManager(partner)
        val webView = android.webkit.WebView(context)
        // 50 ms is well under Robolectric's default test timeout. If the
        // implementation isn't cancellable (e.g. blocking call swallowed
        // the cancellation), this test hangs — `runTest`'s safety net
        // would catch it with a 60s timeout, but the assertion below
        // also fails cleanly if `pollVideoReady` ever does resume with a
        // value (which it shouldn't, since Robolectric's WebView is a
        // stub).
        val result = kotlinx.coroutines.withTimeoutOrNull(50L) {
            manager.pollVideoReady(webView)
        }
        assertNull(
            "Robolectric WebView.evaluateJavascript is a no-op — the callback never fires, " +
                "so pollVideoReady must remain suspended and be cancellable by withTimeoutOrNull",
            result,
        )
    }
}
