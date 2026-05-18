package so.kontext.kit.deviceinfo

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mirrors `InstallIdProviderTests.swift` (kontextkit-ios), plus an
 * Android-specific check that the canonical-shape validator rejects
 * `UUID.fromString`-acceptable shorthand (`"1-2-3-4-5"`).
 *
 * Robolectric is needed because the production code reads/writes
 * `SharedPreferences`. A `@Before` block clears the prefs file each
 * run so cross-test contamination can't leak — the prefs file is a
 * real file backed by Robolectric's shadow filesystem and the
 * `kontextso` file is shared across tests in the same Robolectric
 * process.
 */
@RunWith(RobolectricTestRunner::class)
class InstallIdProviderTest {

    private val uuidV7Pattern: Regex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private val prefsFile = "kontextso"
    private val storageKey = "installId"

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any value carried over from a previous test in this
        // Robolectric process. SharedPreferences are backed by a real
        // file under Robolectric's shadow filesystem.
        prefs(context).edit().clear().apply()
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)

    // ---- uuidv7 generator -----------------------------------------------------

    @Test
    fun `uuidv7 produces canonical v7 shape`() {
        val id = InstallIdProvider.uuidv7()
        assertTrue("expected v7 shape, got: $id", uuidV7Pattern.matches(id))
    }

    @Test
    fun `uuidv7 encodes current timestamp in prefix`() {
        val before = System.currentTimeMillis()
        val id = InstallIdProvider.uuidv7()
        val after = System.currentTimeMillis()
        // First 12 hex chars (without the hyphen) are the 48-bit timestamp.
        val tsHex = id.substring(0, 8) + id.substring(9, 13)
        val ts = tsHex.toLong(radix = 16)
        assertTrue("ts $ts should be >= before $before", ts >= before)
        assertTrue("ts $ts should be <= after $after", ts <= after)
    }

    @Test
    fun `uuidv7 emits time-ordered IDs across distinct milliseconds`() {
        val a = InstallIdProvider.uuidv7()
        // Spin until the millisecond changes so the timestamp prefix differs.
        val baseline = System.currentTimeMillis()
        while (System.currentTimeMillis() == baseline) { /* spin */ }
        val b = InstallIdProvider.uuidv7()
        assertTrue("expected $a < $b lexicographically", a < b)
    }

    // ---- getOrCreate ----------------------------------------------------------

    @Test
    fun `getOrCreate generates and persists on first call`() {
        val id = InstallIdProvider.getOrCreate(context)
        assertTrue("expected v7 shape, got: $id", uuidV7Pattern.matches(id))
        assertEquals(id, prefs(context).getString(storageKey, null))
    }

    @Test
    fun `getOrCreate returns same value on subsequent calls`() {
        val first = InstallIdProvider.getOrCreate(context)
        val second = InstallIdProvider.getOrCreate(context)
        assertEquals(first, second)
    }

    @Test
    fun `getOrCreate reuses value already in storage`() {
        val seeded = "01890000-0000-7000-8000-000000000000"
        prefs(context).edit().putString(storageKey, seeded).apply()
        assertEquals(seeded, InstallIdProvider.getOrCreate(context))
    }

    @Test
    fun `getOrCreate accepts non-v7 UUID for forward compat`() {
        // v4 UUID — the validator is intentionally version-agnostic so a
        // future generator change doesn't invalidate IDs already on disk.
        val v4 = "550e8400-e29b-41d4-a716-446655440000"
        prefs(context).edit().putString(storageKey, v4).apply()
        assertEquals(v4, InstallIdProvider.getOrCreate(context))
    }

    // ---- malformed-value overwrite -------------------------------------------

    @Test
    fun `getOrCreate overwrites empty stored value`() {
        assertOverwritten("")
    }

    @Test
    fun `getOrCreate overwrites non-UUID stored value`() {
        assertOverwritten("lol-not-a-uuid")
    }

    @Test
    fun `getOrCreate overwrites truncated UUID`() {
        assertOverwritten("01890000-0000-7000-8000-00000000")
    }

    @Test
    fun `getOrCreate overwrites UUID with trailing extras`() {
        assertOverwritten("01890000-0000-7000-8000-000000000000-extra")
    }

    @Test
    fun `getOrCreate overwrites JSON-shaped stored value`() {
        assertOverwritten("""{"foo":"bar"}""")
    }

    @Test
    fun `getOrCreate overwrites UUID with non-hex chars`() {
        assertOverwritten("0189zzzz-0000-7000-8000-000000000000")
    }

    @Test
    fun `getOrCreate overwrites UUID-fromString shorthand`() {
        // Android-specific: java.util.UUID.fromString accepts "1-2-3-4-5"
        // as a valid UUID. The shape validator must reject anything that
        // doesn't match the strict 8-4-4-4-12 hex layout — otherwise the
        // generator could store a non-canonical value and round-trip it
        // forever.
        assertOverwritten("1-2-3-4-5")
    }

    private fun assertOverwritten(malformed: String) {
        prefs(context).edit().putString(storageKey, malformed).apply()
        val id = InstallIdProvider.getOrCreate(context)
        assertTrue("expected v7 shape, got: $id", uuidV7Pattern.matches(id))
        assertNotEquals(malformed, id)
        // Replacement is persisted so subsequent calls see the same value.
        assertEquals(id, prefs(context).getString(storageKey, null))
    }

    // ---- isCanonicalUuid (direct) --------------------------------------------

    @Test
    fun `isCanonicalUuid accepts well-formed UUIDs of any version`() {
        assertTrue(InstallIdProvider.isCanonicalUuid("01890000-0000-7000-8000-000000000000"))
        assertTrue(InstallIdProvider.isCanonicalUuid("550e8400-e29b-41d4-a716-446655440000"))
        // Case-insensitive (java.util.UUID toString is lowercase, but stored
        // values from an older generator may be uppercase).
        assertTrue(InstallIdProvider.isCanonicalUuid("550E8400-E29B-41D4-A716-446655440000"))
    }

    @Test
    fun `isCanonicalUuid rejects shorthand and malformed inputs`() {
        assertEquals(false, InstallIdProvider.isCanonicalUuid(""))
        assertEquals(false, InstallIdProvider.isCanonicalUuid("1-2-3-4-5"))
        assertEquals(false, InstallIdProvider.isCanonicalUuid("0189zzzz-0000-7000-8000-000000000000"))
        assertEquals(false, InstallIdProvider.isCanonicalUuid("01890000-0000-7000-8000-00000000"))
        assertEquals(false, InstallIdProvider.isCanonicalUuid("01890000-0000-7000-8000-000000000000-extra"))
    }
}
