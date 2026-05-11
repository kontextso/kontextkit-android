package so.kontext.kit.ui

// Requires: JUnit 5, Robolectric 4.x, androidx.browser on the test classpath.
// Run with: ./gradlew :KontextKit:testDebugUnitTest --tests "so.kontext.kit.InAppBrowserManagerTest"
//
// Note: InAppBrowserManager.open() launches a CustomTabsIntent which requires an
// Activity context to function properly. These tests use Robolectric's Activity
// controller to provide a realistic Activity context.

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class InAppBrowserManagerTest {

    private fun buildActivity(): Activity = Robolectric.buildActivity(Activity::class.java)
        .create()
        .resume()
        .get()

    // Happy paths -------------------------------------------------------------

    @Test
    fun `open returns success Result with valid https URL`() {
        val result = InAppBrowserManager.open(buildActivity(), "https://example.com")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `open returns success Result with valid http URL`() {
        val result = InAppBrowserManager.open(buildActivity(), "http://example.com")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `open returns success Result with URL containing query parameters`() {
        val result = InAppBrowserManager.open(
            buildActivity(),
            "https://example.com/path?key=value&foo=bar",
        )
        assertTrue(result.isSuccess)
    }

    // Error paths -------------------------------------------------------------

    @Test
    fun `open fails with IllegalStateException when context is not an Activity`() {
        // Custom Tabs needs an Activity to attach to; with a bare application
        // context the manager bails early instead of launching a NEW_TASK
        // intent that would land on an unpredictable surface.
        val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        val result = InAppBrowserManager.open(appContext, "https://example.com")
        assertTrue(result.isFailure)
        assertTrue(
            "Expected IllegalStateException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is IllegalStateException,
        )
    }

    @Test
    fun `open fails with IllegalArgumentException for javascript URL`() {
        // Custom Tabs only handles web URLs — file://, javascript:, intent://
        // etc. would either crash the tab service or open something unsafe.
        val result = InAppBrowserManager.open(buildActivity(), "javascript:alert(1)")
        val error = result.exceptionOrNull()
        assertTrue("Expected IllegalArgumentException", error is IllegalArgumentException)
        assertTrue(
            "Expected scheme in message, got: ${error?.message}",
            error?.message?.contains("javascript") == true,
        )
    }

    @Test
    fun `open fails with IllegalArgumentException for file URL`() {
        val result = InAppBrowserManager.open(buildActivity(), "file:///etc/passwd")
        val error = result.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("file") == true)
    }

    // Bridge variant -----------------------------------------------------------

    @Test
    fun `bridge open rejects with error code when URL is null`() {
        var rejectCode: String? = null
        var rejectMessage: String? = null
        InAppBrowserManager.open(
            context = buildActivity(),
            url = null,
            resolve = { fail("Should not resolve when URL is null") },
            reject = { code, message, _ ->
                rejectCode = code
                rejectMessage = message
            },
        )
        assertEquals("IN_APP_BROWSER_ERROR", rejectCode)
        assertEquals("URL is required", rejectMessage)
    }

    @Test
    fun `bridge open rejects with error code when URL is empty`() {
        var rejected = false
        InAppBrowserManager.open(
            context = buildActivity(),
            url = "",
            resolve = { fail("Should not resolve when URL is empty") },
            reject = { _, _, _ -> rejected = true },
        )
        assertTrue(rejected)
    }

    @Test
    fun `bridge open forwards the IllegalArgumentException when scheme is invalid`() {
        // The bridge variant forwards the underlying Throwable as the
        // reject's third argument so Promise consumers can recover the
        // exception type and message if they want.
        var rejectThrowable: Throwable? = null
        InAppBrowserManager.open(
            context = buildActivity(),
            url = "javascript:alert(1)",
            resolve = { fail("Should not resolve for invalid scheme") },
            reject = { _, _, t -> rejectThrowable = t },
        )
        assertNotNull(rejectThrowable)
        assertTrue(rejectThrowable is IllegalArgumentException)
    }

    @Test
    fun `bridge open resolves with true on valid https URL`() {
        var resolvedValue: Any? = null
        InAppBrowserManager.open(
            context = buildActivity(),
            url = "https://example.com",
            resolve = { resolvedValue = it },
            reject = { _, _, _ -> fail("Should not reject for a valid URL") },
        )
        assertEquals(true, resolvedValue)
    }

    // Auto-dismiss state machine -----------------------------------------------
    //
    // After a tab opens, the manager registers an ActivityLifecycleCallback
    // that auto-dismisses (i.e. clears `isCustomTabOpen` and unregisters
    // itself) when the user returns from the tab. The state machine has a
    // subtle quirk: the **first** onActivityResumed after launch is the
    // host Activity's own resume (because the launch flow itself resumes
    // it briefly before the Custom Tab takes over); we have to skip that
    // one and act on the **second** resume (the real return).

    @Test
    fun `successful open flips isCustomTabOpen to true`() {
        resetState()
        val result = InAppBrowserManager.open(buildActivity(), "https://example.com")
        assertTrue(result.isSuccess)
        assertTrue(
            "open() must mark the manager as having a live tab",
            readIsCustomTabOpen(),
        )
    }

    @Test
    fun `auto-dismiss skips the first resume and acts on the second`() {
        resetState()
        val activity = buildActivity()
        val application = activity.application
        InAppBrowserManager.open(activity, "https://example.com")

        // First resume — the host activity's own resume during the launch
        // flow. The listener short-circuits via `skipFirstResume` and
        // leaves the state untouched.
        fireResumed(application, activity)
        assertTrue(
            "First resume is the host activity's own — must be skipped",
            readIsCustomTabOpen(),
        )

        // Second resume — the real return from the Custom Tab. The
        // listener now flips `isCustomTabOpen` to false and unregisters
        // itself (verified separately by `state survives a no-op …`).
        fireResumed(application, activity)
        assertFalse(
            "Second resume must trigger auto-dismiss",
            readIsCustomTabOpen(),
        )
    }

    @Test
    fun `listener unregisters after auto-dismiss so later resumes do nothing`() {
        resetState()
        val activity = buildActivity()
        val application = activity.application
        InAppBrowserManager.open(activity, "https://example.com")

        // First skip + second dismiss.
        fireResumed(application, activity)
        fireResumed(application, activity)
        assertFalse(readIsCustomTabOpen())

        // From here on the listener should be unregistered. Simulate a
        // later open() that flips the flag back to true — if the original
        // listener were still alive it would (on its third resume) trip
        // and clear the flag again on the very next activity resume,
        // breaking the new tab's auto-dismiss.
        setIsCustomTabOpen(true)
        fireResumed(application, activity)
        assertTrue(
            "After dismiss the listener must be unregistered — a stale " +
                "listener would clear the flag on the next resume regardless of state",
            readIsCustomTabOpen(),
        )
    }

    // ---- helpers --------------------------------------------------------

    private val isCustomTabOpenField =
        InAppBrowserManager::class.java
            .getDeclaredField("isCustomTabOpen")
            .apply { isAccessible = true }

    private fun readIsCustomTabOpen(): Boolean =
        isCustomTabOpenField.getBoolean(InAppBrowserManager)

    private fun setIsCustomTabOpen(value: Boolean) {
        isCustomTabOpenField.setBoolean(InAppBrowserManager, value)
    }

    /** `InAppBrowserManager` is an object — reset its singleton state per test. */
    private fun resetState() {
        setIsCustomTabOpen(false)
    }

    /**
     * Fires `onActivityResumed` on every callback currently registered
     * with the Application. The manager registers an anonymous inner
     * class via `application.registerActivityLifecycleCallbacks(...)` —
     * we mirror what the OS's ActivityThread does at runtime by reading
     * the Application's private `mActivityLifecycleCallbacks` list and
     * dispatching to each. Snapshot the list first so a callback that
     * unregisters itself during dispatch can't ConcurrentModification us.
     */
    private fun fireResumed(application: android.app.Application, activity: Activity) {
        val field = android.app.Application::class.java
            .getDeclaredField("mActivityLifecycleCallbacks")
            .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val callbacks = field.get(application)
            as java.util.ArrayList<android.app.Application.ActivityLifecycleCallbacks>
        callbacks.toList().forEach { it.onActivityResumed(activity) }
    }
}
