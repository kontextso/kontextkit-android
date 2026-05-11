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
}
