package so.kontext.kit.deviceinfo

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class OSInfoProviderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `collect returns name as android`() {
        val info = OSInfoProvider.collect(context)
        assert(info.name == "android")
    }

    @Test
    fun `collect returns non-null version`() {
        val info = OSInfoProvider.collect(context)
        // Robolectric's Build.VERSION.RELEASE is non-null on supported SDKs.
        assert(info.version.isNotEmpty())
    }

    @Test
    fun `collect returns BCP-47 locale (hyphen, not underscore)`() {
        val info = OSInfoProvider.collect(context)
        assert(!info.locale.contains('_')) { "Expected BCP-47 (hyphen), got: ${info.locale}" }
    }

    @Test
    fun `collect returns IANA timezone id`() {
        val info = OSInfoProvider.collect(context)
        assert(info.timezone.isNotEmpty())
    }

    @Test
    fun `collect picks up locale from per-context configuration (per-app override)`() {
        // Simulates Android 13+ system per-app language or
        // AppCompatDelegate.setApplicationLocales(...) — both surface via
        // configuration.locales, NOT Locale.getDefault().
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(Locale("cs", "CZ")))
        val overridden = context.createConfigurationContext(config)

        val info = OSInfoProvider.collect(overridden)
        assert(info.locale == "cs-CZ") { "Expected cs-CZ, got: ${info.locale}" }
    }

    @Test
    fun `collectAsDict mirrors collect`() {
        val info = OSInfoProvider.collect(context)
        val dict = OSInfoProvider.collectAsDict(context)
        assert(dict["name"] == info.name)
        assert(dict["version"] == info.version)
        assert(dict["locale"] == info.locale)
        assert(dict["timezone"] == info.timezone)
    }
}
