package so.kontext.kit.omsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Wire-format → typed-enum conversion. Mirrors iOS `OMCreativeType`
 * decoding semantics: only `"display"` and `"video"` are valid,
 * everything else (including `null`) maps to `null` so the caller can
 * short-circuit OMID for unknown creative shapes.
 */
class OmCreativeTypeTest {

    @Test
    fun `fromString video returns VIDEO`() {
        assertEquals(OmCreativeType.VIDEO, OmCreativeType.fromString("video"))
    }

    @Test
    fun `fromString display returns DISPLAY`() {
        assertEquals(OmCreativeType.DISPLAY, OmCreativeType.fromString("display"))
    }

    @Test
    fun `fromString null returns null`() {
        // Caller short-circuits OMID when the bid carries no creativeType field.
        assertNull(OmCreativeType.fromString(null))
    }

    @Test
    fun `fromString unknown returns null (not silent default to display)`() {
        // A typo in the wire format must NOT silently degrade to display —
        // returning null lets the caller skip OMID for the unrecognised
        // shape rather than reporting incorrect impressions to the IAB
        // verification scripts.
        assertNull(OmCreativeType.fromString("vidoe"))
        assertNull(OmCreativeType.fromString("native"))
        assertNull(OmCreativeType.fromString(""))
    }

    @Test
    fun `wireValue matches the IAB-spec strings exactly`() {
        // `wireValue` is what we'd round-trip back to the server. Lock it
        // so a future enum-name refactor doesn't accidentally change the
        // wire format.
        assertEquals("display", OmCreativeType.DISPLAY.wireValue)
        assertEquals("video", OmCreativeType.VIDEO.wireValue)
    }

    @Test
    fun `fromString is case-sensitive (matches iOS Swift enum decoding)`() {
        // Swift Decodable matches enum cases case-sensitively; we mirror
        // that. Capitalised wire values are wrong server-side.
        assertNull(OmCreativeType.fromString("Video"))
        assertNull(OmCreativeType.fromString("DISPLAY"))
    }
}
