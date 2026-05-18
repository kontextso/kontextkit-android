package so.kontext.kit.deviceinfo

import android.content.Context
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Per-app-install identifier. Generated as a UUID v7 on first SDK use,
 * persisted to a dedicated `SharedPreferences` file, and attached to every
 * ad-server request (`/init`, `/preload`, `/error`, `/debug`) so the server
 * can key pacing, frequency caps, and per-install diagnostics to a stable
 * client identity independent of `conversationId` or `userId`.
 *
 * Survives app launches; resets only when the user uninstalls the app or
 * clears app data. Mirrors iOS `InstallIdProvider` (which reads/writes
 * `UserDefaults.standard` under the same `kontextso:installId` field name)
 * and the `kontextso:installId` localStorage key used by `@kontextso/sdk-js`
 * so web and native installs share the same shape on the wire.
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
        val fresh = uuidv7()
        prefs.edit().putString(STORAGE_KEY, fresh).apply()
        return fresh
    }

    /**
     * Generates a UUID v7 string per RFC 9562:
     *
     *   48-bit big-endian Unix-epoch milliseconds | version (0111) | 12 random bits |
     *   variant (10) | 62 random bits
     *
     * Time-ordered, so server-side sorts and B-tree indexes stay friendly.
     */
    @Suppress("MagicNumber") // bit positions and masks defined by RFC 9562 §5.7
    internal fun uuidv7(): String {
        val ts = System.currentTimeMillis()
        val bytes = ByteArray(16)
        // 48-bit timestamp, big-endian.
        bytes[0] = ((ts ushr 40) and 0xff).toByte()
        bytes[1] = ((ts ushr 32) and 0xff).toByte()
        bytes[2] = ((ts ushr 24) and 0xff).toByte()
        bytes[3] = ((ts ushr 16) and 0xff).toByte()
        bytes[4] = ((ts ushr 8) and 0xff).toByte()
        bytes[5] = (ts and 0xff).toByte()
        // Random bytes for the remaining 10 octets.
        val random = ByteArray(10)
        SecureRandom().nextBytes(random)
        System.arraycopy(random, 0, bytes, 6, 10)
        // Version 7 in the high nibble of byte 6.
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x70).toByte()
        // Variant 10 in the high two bits of byte 8.
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        val buffer = ByteBuffer.wrap(bytes)
        val mostSig = buffer.long
        val leastSig = buffer.long
        return UUID(mostSig, leastSig).toString()
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
