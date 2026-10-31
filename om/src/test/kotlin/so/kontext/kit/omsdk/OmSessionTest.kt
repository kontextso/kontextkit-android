package so.kontext.kit.omsdk

import android.content.Context
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the graceful-degradation path of `OmSession`. The OMID AAR is
 * not on the test classpath (it's a host-app dependency declared in
 * build.gradle.kts), so every `Class.forName(...)` lookup in
 * `OmSession.init` raises `ClassNotFoundException`. The session ends up
 * with `isValid = false` and every public method must be safe to call —
 * no-op'ing instead of throwing — so that consuming SDKs can ship a
 * KontextKit dep without the OMID AAR and still build + run.
 *
 * Two probe values stand in for the "real" partner / WebView arguments:
 *
 * - `dummyPartner = Any()`: triggers the reflective init path (rather
 *   than the early `partner == null` short-circuit), which is the
 *   interesting branch — we want to verify the catch in `init` handles
 *   `ClassNotFoundException` and downgrades to invalid.
 * - A real Robolectric `WebView`: required for the constructor signature
 *   and for `retire()`'s `evaluateJavascript` call; we don't drive any
 *   actual JS interaction.
 */
@RunWith(RobolectricTestRunner::class)
class OmSessionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun webView(): WebView = WebView(context)

    private val dummyPartner: Any = Any()

    @Test
    fun `null partner short-circuits init and yields invalid session`() {
        // The `partner == null` branch logs a warning and skips the entire
        // reflective construction — `session` field stays at its `null`
        // initialiser, `isValid` returns false.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        assertFalse(session.isValid)
    }

    @Test
    fun `non-null partner without OMID AAR catches and yields invalid session`() {
        // When the OMID AAR isn't on the classpath, the first
        // `Class.forName(...)` inside `init` raises ClassNotFoundException
        // — caught as `ReflectiveOperationException`, session set to null.
        // This is the most important test in this file: it verifies the
        // SDK is shippable to consumers who haven't wired the OMID AAR.
        val session = OmSession(
            webView(),
            url = "https://example.com",
            creativeType = OmCreativeType.DISPLAY,
            partner = dummyPartner,
        )
        assertFalse(session.isValid)
    }

    @Test
    fun `init handles VIDEO creative type the same way as DISPLAY`() {
        // Different code path inside init (the VIDEO branch picks
        // `DEFINED_BY_JAVASCRIPT` types), but the same catch downgrades
        // both to invalid when the OMID AAR is missing.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.VIDEO, partner = dummyPartner)
        assertFalse(session.isValid)
    }

    @Test
    fun `start is safe to call on invalid session`() {
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        // No exception — the `session == null` guard logs a warning and
        // returns. Calling start() on a fresh invalid session is part of
        // the consumer SDK's normal happy path when OMID is absent.
        session.start()
        session.start() // idempotent — must remain no-op on repeat
    }

    @Test
    fun `loaded is safe to call on invalid session`() {
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        // `adEvents` is null → method returns via the elvis-return.
        session.loaded()
        session.loaded() // idempotent
    }

    @Test
    fun `impressionOccurred is safe to call on invalid session`() {
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        session.impressionOccurred()
        session.impressionOccurred() // idempotent
    }

    @Test
    fun `retire is safe to call on invalid session`() {
        // retire() runs unconditionally — it doesn't check session
        // validity because the WebView postMessage is independent of the
        // OMID native session. Must not throw.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        session.retire()
        session.retire() // idempotent
    }

    @Test
    fun `finish is safe to call on invalid session`() {
        // finish() short-circuits on `session == null` — must not throw.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        session.finish()
        session.finish() // idempotent
    }

    @Test
    fun `logError is safe to call on invalid session for both error types`() {
        // logError() short-circuits on `session == null`; the `errorType`
        // and `message` arguments are not consulted in that case.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        session.logError(errorType = "video", message = "test error")
        session.logError(errorType = "generic", message = null)
        // Both error type branches are no-op'd before the reflective
        // ErrorType field lookup runs.
        session.logError(errorType = null, message = "")
    }

    @Test
    fun `full lifecycle on invalid session does not throw`() {
        // Exercise the path a consuming SDK runs every ad impression when
        // OMID is absent: construct → start → loaded → impression →
        // retire → finish → logError. All must be safe.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = dummyPartner)
        assertFalse(
            "Sanity: without OMID AAR the session must be invalid for this test to mean anything",
            session.isValid,
        )
        session.start()
        session.loaded()
        session.impressionOccurred()
        session.retire()
        session.finish()
        session.logError("generic", "after finish")
    }

    @Test
    fun `finish clears state so subsequent loaded and impression are no-ops`() {
        // finish() nulls out `session` and `adEvents`. After finish() the
        // `adEvents != null` guards in loaded() / impressionOccurred()
        // ensure we don't try to invoke methods on a torn-down session.
        val session = OmSession(webView(), url = null, creativeType = OmCreativeType.DISPLAY, partner = null)
        session.finish()
        // These would throw NullPointerException if the guards weren't in
        // place; they no-op instead.
        session.loaded()
        session.impressionOccurred()
    }
}
