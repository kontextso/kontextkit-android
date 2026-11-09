package so.kontext.kit.util

import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors `UUIDv7Tests.swift` (kontextkit-ios). */
class UuidV7Test {

    private val uuidV7Pattern: Regex =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    @Test
    fun `uuidv7 produces canonical v7 shape`() {
        val id = UuidV7.generate()
        assertTrue("expected v7 shape, got: $id", uuidV7Pattern.matches(id))
    }

    @Test
    fun `uuidv7 encodes current timestamp in prefix`() {
        val before = System.currentTimeMillis()
        val id = UuidV7.generate()
        val after = System.currentTimeMillis()
        // First 12 hex chars (without the hyphen) are the 48-bit timestamp.
        val tsHex = id.substring(0, 8) + id.substring(9, 13)
        val ts = tsHex.toLong(radix = 16)
        assertTrue("ts $ts should be >= before $before", ts >= before)
        assertTrue("ts $ts should be <= after $after", ts <= after)
    }

    @Test
    fun `uuidv7 emits time-ordered IDs across distinct milliseconds`() {
        val a = UuidV7.generate()
        // Spin until the millisecond changes so the timestamp prefix differs.
        val baseline = System.currentTimeMillis()
        while (System.currentTimeMillis() == baseline) { /* spin */ }
        val b = UuidV7.generate()
        assertTrue("expected $a < $b lexicographically", a < b)
    }
}
