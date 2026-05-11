package so.kontext.kit.deviceinfo

import android.content.Context
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AppInfoProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `collect returns non-empty bundleId`() {
        val info = AppInfoProvider.collect(context)
        assert(info.bundleId.isNotEmpty())
    }

    @Test
    fun `collect returns non-empty version`() {
        val info = AppInfoProvider.collect(context)
        assert(info.version.isNotEmpty())
    }

    @Test
    fun `collect returns firstInstallTime as positive epoch ms or null`() {
        val info = AppInfoProvider.collect(context)
        if (info.firstInstallTime != null) {
            assert(info.firstInstallTime!! > 0L)
        }
    }

    @Test
    fun `collect returns lastUpdateTime as positive epoch ms or null`() {
        // Android-only field — sourced from PackageInfo.lastUpdateTime.
        // Same null-or-positive contract as firstInstallTime.
        val info = AppInfoProvider.collect(context)
        if (info.lastUpdateTime != null) {
            assert(info.lastUpdateTime!! > 0L)
        }
    }

    @Test
    fun `processStartMs is captured as a plausible recent timestamp`() {
        // Initialised on first reference to AppInfoProvider; should be
        // very close to "now" — captured during this test process's lifetime.
        // 60s lower bound stays valid forever (no year-2020 magic number to
        // grow stale).
        val now = System.currentTimeMillis()
        assert(AppInfoProvider.processStartMs in (now - 60_000L)..now) {
            "processStartMs=${AppInfoProvider.processStartMs} not within last 60s of now=$now"
        }
    }

    @Test
    fun `collect returns 0_0_0 fallback when versionName is null`() {
        // Force versionName=null via the PackageManager shadow so the
        // fallback path is exercised explicitly (otherwise the test
        // depends on whatever default Robolectric ships with).
        val pi = PackageInfo().apply {
            packageName = context.packageName
            versionName = null
        }
        shadowOf(context.packageManager).installPackage(pi)

        assertEquals("0.0.0", AppInfoProvider.collect(context).version)
    }

    @Test
    fun `collectAsDict mirrors collect with iOS-matching keys`() {
        val info = AppInfoProvider.collect(context)
        val dict = AppInfoProvider.collectAsDict(context)
        assert(dict["bundleId"] == info.bundleId)
        assert(dict["version"] == info.version)
        assert(dict["processStartMs"] == AppInfoProvider.processStartMs)
        // firstInstallTime / lastUpdateTime are omitted when null
        // (matches iOS behaviour — explicit-absence over null sentinels).
        if (info.firstInstallTime != null) {
            assert(dict["firstInstallTime"] == info.firstInstallTime)
        } else {
            assert(!dict.containsKey("firstInstallTime"))
        }
        if (info.lastUpdateTime != null) {
            assert(dict["lastUpdateTime"] == info.lastUpdateTime)
        } else {
            assert(!dict.containsKey("lastUpdateTime"))
        }
    }
}
