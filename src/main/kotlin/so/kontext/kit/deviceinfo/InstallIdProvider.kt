package so.kontext.kit.deviceinfo

import android.content.Context
import so.kontext.kit.util.UuidV7

/**
 * Per-app-install identifier. Generated as a UUID v7 on first SDK use,
 * persisted to a dedicated `SharedPreferences` file, and attached to every
 * ad-server request (`/init`, `/preload`, `/error`, `/debug`) so the server
 * can key pacing, frequency caps, and per-install diagnostics to a stable
 * client identity independent of `conversationId` or `userId`.
 *
 * Survives app launches; resets only when the user uninstalls the app or
 * clears app data. The on-the-wire DTO field is `installId` — the same
 * field name iOS `InstallIdProvider` writes and the same field name
 * `@kontextso/sdk-js` writes, so web, iOS, and Android installs share the
 * same shape in the request body. Per-platform local-storage layout
 * differs and is intentionally not part of the shared contract: iOS uses
 * a flat `UserDefaults` key (`kontextso.installId`); sdk-js uses
 * `localStorage` key `kontextso:installId`; Android uses a dedicated
 * `kontextso` `SharedPreferences` file with the `installId` key.
 *
 * Storage location: a dedicated `kontextso` prefs file rather than the
 * host app's default prefs. Keeps the install ID out of any prefs file
 * the IAB TCF library reads/writes through
 * `PreferenceManager.getDefaultSharedPreferences`, and gives the host
 * app a single file name to clear if they ever need to reset the ID.
 */
public object InstallIdProvider {

    private const val PREFS_FILE: String = "kontextso"
    private const val STORAGE_KEY: String = "installId"

    private val canonicalUuidRegex: Regex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Returns the persisted install ID, generating + storing one on first
     * call. The stored value is validated against the canonical UUID shape
     * (8-4-4-4-12 hex) and overwritten if corrupted — guards against
     * accidental tampering, partial preferences-file migration, or a
     * future change to the generator.
     */
    public fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val existing = prefs.getString(STORAGE_KEY, null)
        if (existing != null && isCanonicalUuid(existing)) return existing
        val fresh = UuidV7.generate()
        prefs.edit().putString(STORAGE_KEY, fresh).apply()
        return fresh
    }

    /**
     * Validates any canonical UUID shape (8-4-4-4-12 hex). Intentionally
     * not version- or variant-locked so a future change to the generator
     * (e.g. v8) doesn't invalidate the IDs already in users' preferences.
     * `UUID.fromString` is too lenient (accepts `"1-2-3-4-5"`), so the
     * regex enforces exact group widths.
     */
    internal fun isCanonicalUuid(value: String): Boolean = canonicalUuidRegex.matches(value)
}
