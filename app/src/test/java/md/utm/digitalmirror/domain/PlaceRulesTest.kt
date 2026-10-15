package md.utm.digitalmirror.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceRulesTest {

    @Test
    fun `raza se tine intre 50 si 500 de metri`() {
        assertEquals(50, PlaceRules.clampRadius(10))
        assertEquals(100, PlaceRules.clampRadius(100))
        assertEquals(500, PlaceRules.clampRadius(2000))
    }

    @Test
    fun `locul se poate salva doar cu nume si punct ales`() {
        assertEquals("Dă un nume locului.", PlaceRules.problem("  ", true))
        assertEquals("Atinge harta sau caută o adresă ca să alegi locul.", PlaceRules.problem("Facultate", false))
        assertNull(PlaceRules.problem("Facultate", true))
        assertEquals(
            "Numele poate avea cel mult 40 de caractere.",
            PlaceRules.problem("a".repeat(41), true)
        )
    }

    @Test
    fun `coordonatele trebuie sa fie pe glob`() {
        assertTrue(PlaceRules.isValidCoordinate(47.0105, 28.8638))
        assertFalse(PlaceRules.isValidCoordinate(91.0, 0.0))
        assertFalse(PlaceRules.isValidCoordinate(0.0, 181.0))
    }

    @Test
    fun `distanta dintre doua puncte apropiate este corecta`() {
        // un grad de latitudine = aprox. 111,2 km
        val d = PlaceRules.distanceMeters(47.0, 28.0, 48.0, 28.0)
        assertEquals(111_195.0, d, 200.0)
        assertEquals(0.0, PlaceRules.distanceMeters(47.0, 28.0, 47.0, 28.0), 0.001)
    }

    @Test
    fun `un punct la 60 m este in locul cu raza 100 dar nu in cel cu raza 50`() {
        // 0,00054 grade de latitudine = aprox. 60 m
        val lat = 47.0 + 0.00054
        assertTrue(PlaceRules.contains(47.0, 28.0, 100, lat, 28.0))
        assertFalse(PlaceRules.contains(47.0, 28.0, 50, lat, 28.0))
    }

    @Test
    fun `distanta se scrie in metri sau kilometri`() {
        assertEquals("850 m", PlaceRules.distanceLabel(850.4))
        assertEquals("1,2 km", PlaceRules.distanceLabel(1234.0))
    }
}