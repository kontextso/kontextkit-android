package so.kontext.kit.util

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * UUID version 7 (RFC 9562): 48 bits of Unix-epoch milliseconds, then random bits, so ids sort by
 * the millisecond they were made in; within one millisecond, or after the clock was set back, their
 * order is random. `InstallIdProvider` makes the install id with it; an SDK makes its own time-ordered
 * ids (a session id) the same way. Mirrors kontextkit-ios `UUIDv7`.
 */
public object UuidV7 {

    /**
     * Shared, thread-safe PRNG. `SecureRandom` is documented thread-safe and
     * is intended to be reused — instantiating one per `generate()` call would
     * re-seed from `/dev/urandom` every time, which is measurably slow on
     * older Android devices.
     */
    private val secureRandom: SecureRandom = SecureRandom()

    /**
     * A new UUID v7 as a lowercase canonical string (8-4-4-4-12 hex):
     *
     *   48-bit big-endian Unix-epoch milliseconds | version (0111) | 12 random bits |
     *   variant (10) | 62 random bits
     *
     * Time-ordered, so server-side sorts and B-tree indexes stay friendly.
     */
    @Suppress("MagicNumber") // bit positions and masks defined by RFC 9562 §5.7
    public fun generate(): String {
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
        secureRandom.nextBytes(random)
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
}
