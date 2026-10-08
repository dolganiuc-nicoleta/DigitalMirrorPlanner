package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TitleKeyTest {

    @Test
    fun `ignora majusculele spatiile si punctuatia`() {
        val expected = TitleKey.of("Laborator broker")
        assertEquals(expected, TitleKey.of("  laborator   BROKER! "))
        assertEquals(expected, TitleKey.of("Laborator-broker"))
    }

    @Test
    fun `ignora diacriticele`() {
        assertEquals(TitleKey.of("Lucru la licenta"), TitleKey.of("Lucru la licență"))
        assertEquals("curatenie", TitleKey.of("Curățenie"))
    }

    @Test
    fun `titlurile diferite raman diferite`() {
        assertNotEquals(TitleKey.of("Laborator PAD"), TitleKey.of("Laborator broker"))
    }
}