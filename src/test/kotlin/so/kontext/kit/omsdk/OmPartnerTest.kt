package so.kontext.kit.omsdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OmPartnerTest {

    @Test
    fun `holds name and version fields`() {
        val partner = OmPartner(name = "Kontextso", version = "1.0.0")
        assertEquals("Kontextso", partner.name)
        assertEquals("1.0.0", partner.version)
    }

    @Test
    fun `equality compares both name and version`() {
        // IAB Tech Lab registers partners as (name, version) pairs; two
        // releases of the same SDK have the same name but different version
        // and must NOT compare equal — otherwise a caching layer keyed on
        // OmPartner would silently serve the wrong version's session.
        val a = OmPartner("Kontextso", "1.0.0")
        val b = OmPartner("Kontextso", "1.0.0")
        val differentVersion = OmPartner("Kontextso", "1.0.1")
        val differentName = OmPartner("Other", "1.0.0")

        assertEquals(a, b)
        assertNotEquals(a, differentVersion)
        assertNotEquals(a, differentName)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = OmPartner("Kontextso", "1.0.0")
        val b = OmPartner("Kontextso", "1.0.0")
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `copy preserves unspecified fields`() {
        val original = OmPartner("Kontextso", "1.0.0")
        val bumped = original.copy(version = "1.0.1")
        assertEquals("Kontextso", bumped.name)
        assertEquals("1.0.1", bumped.version)
        // Original is unchanged — data class copy is immutable.
        assertEquals("1.0.0", original.version)
    }
}
