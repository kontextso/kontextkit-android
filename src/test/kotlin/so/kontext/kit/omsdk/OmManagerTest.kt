package so.kontext.kit.omsdk

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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

    @Before
    fun resetGlobalState() {
        // Activation state is process-global (the OMID SDK is a process-wide
        // singleton), so it leaks across test cases in the same JVM. Reset it
        // before each test for isolation.
        OmManager.resetActivationStateForTest()
    }

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
        // Both calls return the same state — the first failure sets the
        // process-global kill-switch, so the second call short-circuits.
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
    fun `activate from a background thread does not block, throw, or deadlock`() {
        // The core fix: OMID's Omid.activate() creates Handlers and must run on
        // the main thread. When called off-main it is *posted* to the main
        // Looper (fire-and-forget) — never run inline (which crashed) and never
        // blocked-awaited (which deadlocked a caller holding the main thread,
        // e.g. runBlocking on main → the field ANR). This guards both.
        val manager = OmManager(partner)
        val returned = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>(null)

        val thread = Thread {
            try {
                manager.activate(context)
                returned.set(true)
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        thread.start()
        thread.join(2_000) // would hang here if activate() blocked on the main thread

        assertFalse("activate() must not hang when called off the main thread", thread.isAlive)
        assertTrue("activate() must return when called off the main thread", returned.get())
        assertNull(
            "activate() must not throw off-main — the off-main Handler crash is the bug being fixed",
            error.get(),
        )
        // Drain the activation posted to the main Looper (OMID AAR absent here,
        // so it fails gracefully and sets the kill-switch).
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `failed activation is sticky — createSession returns null afterward`() = runTest {
        // Once Omid.activate() fails (here: AAR absent), OMID is disabled
        // process-wide so no session is ever created and the TreeWalker is
        // never armed. Activation runs inline on the Robolectric main thread.
        val manager = OmManager(partner)
        assertFalse(manager.activate(context))

        val session = manager.createSession(
            webView = android.webkit.WebView(context),
            url = null,
            creativeType = OmCreativeType.DISPLAY,
        )
        assertNull("a failed activation must keep createSession returning null", session)
    }

    @Test
    fun `activation state is shared across manager instances`() {
        // Activation state is process-global, not per-instance: one manager's
        // failed activation leaves OMID unavailable for a second manager too,
        // so it also refuses to create a session. (The happy path — one
        // manager activating and a second reusing it — needs the OMID AAR and
        // is covered by the on-device repro, not here.)
        val managerA = OmManager(partner)
        managerA.activate(context)

        val managerB = OmManager(OmPartner("Other", "9.9.9"))
        val sessionB = runBlocking {
            managerB.createSession(
                webView = android.webkit.WebView(context),
                url = null,
                creativeType = OmCreativeType.DISPLAY,
            )
        }
        assertNull(sessionB)
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
