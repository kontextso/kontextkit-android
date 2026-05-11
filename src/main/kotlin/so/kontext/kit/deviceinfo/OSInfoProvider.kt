package so.kontext.kit.deviceinfo

import android.content.Context
import android.os.Build
import java.util.TimeZone

/**
 * Provides OS-level metadata (name, version, locale, timezone) for ad
 * targeting. Centralised here so every Android-using SDK (sdk-kotlin,
 * sdk-react-native, sdk-flutter) reports values that match the server's
 * `osSchema` — in particular a **BCP-47** locale tag and a lowercase
 * platform name.
 *
 * Mirrors `KontextKit/ios/Sources/DeviceInfo/OSInfoProvider.swift`.
 */
public object OSInfoProvider {

    public data class OsInfo(
        /** Always "android" — matches the server's `osSchema` example. */
        val name: String,
        /** e.g. "14". */
        val version: String,
        /** BCP-47 tag, e.g. "en-US" (NOT POSIX "en_US"). */
        val locale: String,
        /** IANA timezone id, e.g. "Europe/Prague". */
        val timezone: String,
    )

    public fun collect(context: Context): OsInfo = OsInfo(
        // Lowercase to match the server's osSchema example ("ios" / "android").
        name = "android",
        // .orEmpty() guards against rare AOSP forks where RELEASE is null
        // despite the @NonNull annotation in framework headers.
        version = Build.VERSION.RELEASE.orEmpty(),
        // Read from the per-context configuration (NOT Locale.getDefault())
        // so per-app language overrides reach ad targeting:
        //   - Android 13+ system per-app language (Settings → Apps → language)
        //   - Runtime AppCompatDelegate.setApplicationLocales(...)
        // Both update configuration.locales reliably; Locale.getDefault() is
        // best-effort.
        locale = context.resources.configuration.locales[0].toLanguageTag(),
        timezone = TimeZone.getDefault().id,
    )

    /** Dictionary representation for bridge layers (RN, Flutter). */
    public fun collectAsDict(context: Context): Map<String, Any> {
        val info = collect(context)
        return mapOf(
            "name" to info.name,
            "version" to info.version,
            "locale" to info.locale,
            "timezone" to info.timezone,
        )
    }
}
